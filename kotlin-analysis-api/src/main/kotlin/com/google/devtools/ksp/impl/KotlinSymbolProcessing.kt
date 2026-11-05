/*
 * Copyright 2022 Google LLC
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.devtools.ksp.impl

import com.google.devtools.ksp.common.AnyChanges
import com.google.devtools.ksp.common.KSObjectCacheManager
import com.google.devtools.ksp.common.impl.AAResolutionStrategy
import com.google.devtools.ksp.common.impl.CodeGeneratorImpl
import com.google.devtools.ksp.common.impl.JsPlatformInfoImpl
import com.google.devtools.ksp.common.impl.JvmPlatformInfoImpl
import com.google.devtools.ksp.common.impl.NativePlatformInfoImpl
import com.google.devtools.ksp.common.impl.PsiResolutionStrategy
import com.google.devtools.ksp.common.impl.UnknownPlatformInfoImpl
import com.google.devtools.ksp.impl.backend.KcExtras
import com.google.devtools.ksp.impl.backend.KspBackend
import com.google.devtools.ksp.impl.backend.KspBackendSession
import com.google.devtools.ksp.impl.symbol.kotlin.Deferrable
import com.google.devtools.ksp.impl.symbol.kotlin.Restorable
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.Origin
import com.intellij.mock.MockProject
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.util.Disposer
import com.intellij.util.ui.EDT
import org.jetbrains.kotlin.config.KotlinCompilerVersion
import org.jetbrains.kotlin.platform.JsPlatform
import org.jetbrains.kotlin.platform.TargetPlatform
import org.jetbrains.kotlin.platform.jvm.JdkPlatform
import org.jetbrains.kotlin.platform.konan.NativePlatform
import java.io.File

class KotlinSymbolProcessing(
    val kspConfig: KSPConfig,
    val symbolProcessorProviders: List<SymbolProcessorProvider>,
    val logger: KSPLogger,
    val backend: KspBackend = KspBackend.load(),
) {
    enum class ExitCode(
        val code: Int
    ) {
        OK(0),

        // Whenever there are some error messages.
        PROCESSING_ERROR(1),

        // Let exceptions pop through to the caller. Don't catch and convert them to, e.g., INTERNAL_ERROR.
    }

    init {
        // We depend on swing (indirectly through PSI or something), so we want to declare headless mode,
        // to avoid accidentally starting the UI thread. But, don't set it if it was set externally.
        if (System.getProperty("java.awt.headless") == null) {
            System.setProperty("java.awt.headless", "true")
        }
    }

    // TODO: performance
    fun execute(): ExitCode {
        val logger = object : KSPLogger by logger {
            var hasError: Boolean = false

            override fun error(message: String, symbol: KSNode?) {
                hasError = true
                logger.error(message, symbol)
            }

            override fun warn(message: String, symbol: KSNode?) {
                if (kspConfig.allWarningsAsErrors)
                    hasError = true
                logger.warn(message, symbol)
            }
        }

        val projectDisposable: Disposable = Disposer.newDisposable("StandaloneAnalysisAPISession.project")
        var session: KspBackendSession? = null
        try {
            session = backend.open(kspConfig, projectDisposable)
            KcExtras.instance = session.extras
            val project = session.project
            // Initializes it
            KSPCoreEnvironment(project as MockProject)

            val providers: List<SymbolProcessorProvider> = symbolProcessorProviders
            ResolverAAImpl.ktModule = session.module
            ResolverAAImpl.kspConfig = kspConfig

            // Initializing environments
            val allKSFiles = session.sourceFiles()
            val anyChangesWildcard = AnyChanges(kspConfig.projectBaseDir)
            val codeGenerator = CodeGeneratorImpl(
                kspConfig.classOutputDir,
                { if (kspConfig is KSPJvmConfig) kspConfig.javaOutputDir else kspConfig.kotlinOutputDir },
                kspConfig.kotlinOutputDir,
                kspConfig.resourceOutputDir,
                kspConfig.projectBaseDir,
                anyChangesWildcard,
                allKSFiles,
                kspConfig.incremental
            )

            val dualLookupTracker = DualLookupTracker()
            val incrementalContext = IncrementalContextAA(
                kspConfig.incremental,
                dualLookupTracker,
                File(anyChangesWildcard.filePath).relativeTo(kspConfig.projectBaseDir),
                kspConfig.incrementalLog,
                kspConfig.projectBaseDir,
                kspConfig.cachesDir,
                kspConfig.outputBaseDir,
                kspConfig.modifiedSources,
                kspConfig.removedSources,
                kspConfig.changedClasses,
            )
            var allDirtyKSFiles = incrementalContext.calcDirtyFiles(allKSFiles).toList()
            var newKSFiles = allDirtyKSFiles

            val targetPlatform = ResolverAAImpl.ktModule.targetPlatform
            val symbolProcessorEnvironment = SymbolProcessorEnvironment(
                kspConfig.processorOptions,
                kspConfig.languageVersion.toKotlinVersion(),
                codeGenerator,
                logger,
                kspConfig.apiVersion.toKotlinVersion(),
                KotlinCompilerVersion.getVersion().toKotlinVersion(),
                targetPlatform.getPlatformInfo(kspConfig),
                KotlinVersion(2, 0)
            )

            // Load and instantiate processsors
            val deferredSymbols = mutableMapOf<SymbolProcessor, List<Restorable>>()
            val processors = providers.map { provider ->
                provider.create(symbolProcessorEnvironment).also { deferredSymbols[it] = mutableListOf() }
            }

            fun dropCaches() {
                maybeRunInWriteAction {
                    session.dropCaches()
                    KSObjectCacheManager.clear()
                }
            }

            // Run processors until either
            // 1) there is an error
            // 2) there is no more new files.
            while (!logger.hasError) {
                session.beginRound(dualLookupTracker)

                val resolutionStrategy =
                    if (kspConfig.experimentalPsiResolution)
                        PsiResolutionStrategy(newKSFiles, deferredSymbols)
                    else
                        AAResolutionStrategy(newKSFiles, deferredSymbols)

                val resolver = ResolverAAImpl(
                    allDirtyKSFiles,
                    project,
                    incrementalContext,
                    resolutionStrategy
                )
                ResolverAAImpl.instance = resolver
                ResolverAAImpl.instance.functionAsMemberOfCache = mutableMapOf()
                ResolverAAImpl.instance.propertyAsMemberOfCache = mutableMapOf()

                processors.forEach { processor ->
                    incrementalContext.closeFilesOnException {
                        deferredSymbols[processor] =
                            processor.process(resolver)
                                .filter { it.origin == Origin.KOTLIN || it.origin == Origin.JAVA }
                                .filterIsInstance<Deferrable>()
                                .mapNotNull(Deferrable::defer)
                    }
                    if (!deferredSymbols.containsKey(processor) || deferredSymbols[processor]!!.isEmpty()) {
                        deferredSymbols.remove(processor)
                    }
                }

                val allKSFilesPointers = allDirtyKSFiles.filterIsInstance<Deferrable>().map { it.defer() }

                if (logger.hasError || codeGenerator.generatedFile.isEmpty()) {
                    break
                }

                dropCaches()

                newKSFiles = session.addFiles(
                    codeGenerator.generatedFile.filter { it.extension.lowercase() == "kt" },
                    codeGenerator.generatedFile.filter { it.extension.lowercase() == "java" },
                )
                // Now that caches are dropped, KtSymbols and KS* are invalid. They need to be restored from deferred.
                // Do not replace `!!` with `?.`. Implementations of KSFile in KSP2 must implement Deferrable and
                // return non-null.
                allDirtyKSFiles = allKSFilesPointers.map { it!!.restore() as KSFile } + newKSFiles
                incrementalContext.registerGeneratedFiles(newKSFiles)
                codeGenerator.closeFiles()
            }

            // Call onError() or finish()
            if (logger.hasError) {
                processors.forEach(SymbolProcessor::onError)
            } else {
                processors.forEach(SymbolProcessor::finish)
            }

            if (!logger.hasError) {
                incrementalContext.updateCachesAndOutputs(
                    allDirtyKSFiles,
                    codeGenerator.outputs,
                    codeGenerator.sourceToOutputs
                )
            } else {
                incrementalContext.closeFiles()
            }

            dropCaches()
            codeGenerator.closeFiles()
        } finally {
            maybeRunInWriteAction {
                session?.close()
                Disposer.dispose(projectDisposable)
                ResolverAAImpl.tearDown()
                KSPCoreEnvironment.tearDown()
                KcExtras.tearDown()
            }
        }

        return if (logger.hasError) ExitCode.PROCESSING_ERROR else ExitCode.OK
    }
}

fun String?.toKotlinVersion(): KotlinVersion {
    if (this == null)
        return KotlinVersion.CURRENT

    return split('-').first().split('.').map { it.toInt() }.let {
        when (it.size) {
            1 -> KotlinVersion(it[0], 0, 0)
            2 -> KotlinVersion(it[0], it[1], 0)
            3 -> KotlinVersion(it[0], it[1], it[2])
            else -> KotlinVersion.CURRENT
        }
    }
}

fun TargetPlatform.getPlatformInfo(kspConfig: KSPConfig): List<PlatformInfo> =
    componentPlatforms.map { platform ->
        when (platform) {
            is JdkPlatform -> JvmPlatformInfoImpl(
                platformName = platform.platformName,
                jvmTarget = platform.targetVersion.toString(),
                jvmDefaultMode = (kspConfig as? KSPJvmConfig)?.jvmDefaultMode ?: "disable"
            )

            is JsPlatform -> JsPlatformInfoImpl(
                platformName = platform.platformName
            )

            is NativePlatform -> NativePlatformInfoImpl(
                platformName = platform.platformName,
                targetName = platform.targetName
            )
            // Unknown platform; toString() may be more informative than platformName
            else -> UnknownPlatformInfoImpl(platform.toString())
        }
    }

internal fun <R> maybeRunInWriteAction(f: () -> R) {
    synchronized(EDT::class.java) {
        if (!EDT.isCurrentThreadEdt())
            updateEdt()
        if (ApplicationManager.getApplication() != null) {
            runWriteAction {
                f()
            }
        } else {
            f()
        }
    }
}

// EDT.updateEdt() makes the current thread the EDT. It is internal, and the
// IntelliJ core bundled in kotlin-compiler lacks it, so it is called
// reflectively, falling back to setting the field it sets.
private fun updateEdt() {
    val edt = EDT::class.java
    try {
        edt.getDeclaredMethod("updateEdt").apply { isAccessible = true }.invoke(null)
    } catch (_: NoSuchMethodException) {
        edt.getDeclaredField("myEventDispatchThread").apply { isAccessible = true }.set(null, Thread.currentThread())
    }
}
