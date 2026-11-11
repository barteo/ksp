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

import com.google.devtools.ksp.ApiFeatures
import com.google.devtools.ksp.common.AnyChanges
import com.google.devtools.ksp.common.IncrementalContextBase
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
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.StandardFileSystems
import com.intellij.psi.PsiFileSystemItem
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.util.ui.EDT
import org.jetbrains.kotlin.config.KotlinCompilerVersion
import org.jetbrains.kotlin.platform.JsPlatform
import org.jetbrains.kotlin.platform.TargetPlatform
import org.jetbrains.kotlin.platform.jvm.JdkPlatform
import org.jetbrains.kotlin.platform.konan.NativePlatform
import org.jetbrains.kotlin.psi.KtFile
import java.io.File
import java.nio.file.Path

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
        val cachesUpToDate = File(kspConfig.cachesDir, IncrementalContextBase.CACHES_UP_TO_DATE)
        var rebuildEveryRun = false
        try {
            session = backend.open(kspConfig, projectDisposable)
            if (kspConfig.incremental && !session.recordsLookups) {
                // Without the front end's lookups the caches miss the Kotlin sources' dependencies:
                // rebuild, processing all files, and leave the caches marked stale (below) so that
                // the next run rebuilds too. KSP's own lookups (Java sources) are still recorded.
                rebuildEveryRun = true
                cachesUpToDate.delete()
                logger.info("the ${backend.name} backend records no lookups; processing all files")
            }
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
                kspConfig.incrementalContextLoggingOptions,
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
            val processorsRegisteredForUpcomingFeatures = mutableSetOf<SymbolProcessor>()
            val symbolProcessorEnvironment = SymbolProcessorEnvironment(
                kspConfig.processorOptions,
                kspConfig.languageVersion.toKotlinVersion(),
                codeGenerator,
                logger,
                kspConfig.apiVersion.toKotlinVersion(),
                KotlinCompilerVersion.getVersion().toKotlinVersion(),
                targetPlatform.getPlatformInfo(kspConfig),
                KotlinVersion(2, 0),
                registerProcessorForNewFeatures = processorsRegisteredForUpcomingFeatures::add
            )

            // Load and instantiate processors
            val deferredSymbols = mutableMapOf<SymbolProcessor, List<Restorable>>()
            val processors = providers.map { provider ->
                provider.create(symbolProcessorEnvironment).also { deferredSymbols[it] = mutableListOf() }
            }

            // Emit warning for processors not opted in to new features
            computeFeatureOptInWarnings(processors, processorsRegisteredForUpcomingFeatures)
                .forEach { warning -> logger.info(warning, null) }

            fun dropCaches() {
                maybeRunInWriteAction {
                    session.dropCaches()
                }
                KSObjectCacheManager.clear()
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
                    resolutionStrategy,
                    processorsRegisteredForUpcomingFeatures
                )
                ResolverAAImpl.instance = resolver
                ResolverAAImpl.instance.functionAsMemberOfCache = mutableMapOf()
                ResolverAAImpl.instance.propertyAsMemberOfCache = mutableMapOf()

                processors.forEach { processor ->
                    resolver.currentProcessor = processor
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
                runTypeCheck(newKSFiles, project, logger)
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
            if (rebuildEveryRun)
                cachesUpToDate.delete()
            KSObjectCacheManager.clear()
            ResolverAAImpl.tearDown()
            KSPCoreEnvironment.tearDown()
            KcExtras.tearDown()
            maybeRunInWriteAction {
                Disposer.dispose(projectDisposable)
                session?.close()
            }
        }

        return if (logger.hasError) ExitCode.PROCESSING_ERROR else ExitCode.OK
    }

    private fun computeDisabledApiFeatures(apiFeatures: ApiFeatures): List<String> = buildList {
        if (!apiFeatures.enableBackingFields) {
            add(ApiFeatures::enableBackingFields.name)
        }
        if (!apiFeatures.enableContextParameters) {
            add(ApiFeatures::enableContextParameters.name)
        }
    }

    private fun computeFeatureOptInWarnings(
        processors: List<SymbolProcessor>,
        processorsRegisteredForUpcomingFeatures: Set<SymbolProcessor>
    ): List<String> = buildList {
        for (processor in processors) {
            val processorName = processor::class.qualifiedName ?: processor::class.simpleName
            val disabledFeatures = computeDisabledApiFeatures(processor.apiFeatures)
            if (processor in processorsRegisteredForUpcomingFeatures) {
                add(
                    "Processor '$processorName' " +
                        "opted in for upcoming features via 'SymbolProcessorEnvironment.registerProcessorForNewFeatures'. " +
                        "Note that 'SymbolProcessorEnvironment.registerProcessorForNewFeatures' is deprecated; " +
                        "processors should instead migrate to configuring 'SymbolProcessor.apiFeatures'."
                )
            } else if (!processor.apiFeatures.enableBackingFields && !processor.apiFeatures.enableContextParameters) {
                add(
                    "Processor '$processorName' " +
                        "has not opted in for upcoming features yet. " +
                        "It might break in a future version of KSP. " +
                        "To fix this, please update the processor to a version that is " +
                        "compatible with upcoming features."
                )
                add(
                    "Note that 'SymbolProcessorEnvironment.registerProcessorForNewFeatures' is deprecated; " +
                        "processors should instead migrate to configuring 'SymbolProcessor.apiFeatures'."
                )
            }
            if (disabledFeatures.isNotEmpty()) {
                add(
                    "Processor '$processorName' " +
                        "has not enabled the following API features: ${disabledFeatures.joinToString(", ")}. " +
                        "It might break in a future version of KSP. " +
                        "To fix this, please configure the set of 'apiFeatures' in 'SymbolProcessorProvider' " +
                        "when creating the 'SymbolProcessor' (e.g., using 'buildApiFeatures'). " +
                        "Processors should eventually enable all features in 'ApiFeatures'."
                )
            }
        }
    }

    companion object {
        fun List<KSFile>.toKtFiles(project: Project): List<KtFile> =
            getPsiFilesFromPaths<KtFile>(project, this.toPathSet())

        fun List<KSFile>.toPsiJavaFiles(project: Project): List<PsiJavaFile> =
            getPsiFilesFromPaths<PsiJavaFile>(project, this.toPathSet())

        fun List<KSFile>.toPathSet(): Set<Path> = this.map { File(it.filePath).toPath() }.toSet()

        fun runTypeCheck(newKSFiles: List<KSFile>, project: Project, logger: KSPLogger) {
            val (kotlinKSFiles, javaKSFiles) = newKSFiles
                .partition { it.origin == Origin.KOTLIN }
            KspDiagnostics.runTypeCheck(javaKSFiles.toPsiJavaFiles(project), kotlinKSFiles.toKtFiles(project), logger)
        }
    }
}

internal inline fun <reified T : PsiFileSystemItem> getPsiFilesFromPaths(
    project: Project,
    paths: Set<Path>,
): List<T> {
    val fs = StandardFileSystems.local()
    val psiManager = PsiManager.getInstance(project)
    return buildList {
        for (path in paths) {
            val vFile = fs.findFileByPath(path.toString()) ?: continue
            val psiFileSystemItem =
                if (vFile.isDirectory)
                    psiManager.findDirectory(vFile) as? T
                else
                    psiManager.findFile(vFile) as? T
            psiFileSystemItem?.let { add(it) }
        }
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
