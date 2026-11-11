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

@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.google.devtools.ksp.impl.backend.fir

import com.google.devtools.ksp.common.AnyChanges
import com.google.devtools.ksp.common.KSObjectCacheManager
import com.google.devtools.ksp.common.impl.AAResolutionStrategy
import com.google.devtools.ksp.common.impl.CodeGeneratorImpl
import com.google.devtools.ksp.common.impl.JsPlatformInfoImpl
import com.google.devtools.ksp.common.impl.JvmPlatformInfoImpl
import com.google.devtools.ksp.common.impl.NativePlatformInfoImpl
import com.google.devtools.ksp.common.impl.PsiResolutionStrategy
import com.google.devtools.ksp.common.impl.UnknownPlatformInfoImpl
import com.google.devtools.ksp.impl.DualLookupTracker
import com.google.devtools.ksp.impl.ResolverAAImpl
import com.google.devtools.ksp.impl.getPsiFilesFromPaths
import com.google.devtools.ksp.impl.backend.KcExtras
import com.google.devtools.ksp.impl.backend.KspBackend
import com.google.devtools.ksp.impl.backend.KspBackendSession
import com.google.devtools.ksp.impl.symbol.kotlin.Deferrable
import com.google.devtools.ksp.impl.symbol.kotlin.KSFileImpl
import com.google.devtools.ksp.impl.symbol.kotlin.KSFileJavaImpl
import com.google.devtools.ksp.impl.symbol.kotlin.Restorable
import com.google.devtools.ksp.impl.symbol.kotlin.analyze
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.standalone.IncrementalGlobalSearchScope
import com.google.devtools.ksp.standalone.IncrementalJavaFileManager
import com.google.devtools.ksp.standalone.IncrementalKotlinDeclarationProviderFactory
import com.google.devtools.ksp.standalone.IncrementalKotlinPackageProviderFactory
import com.google.devtools.ksp.standalone.JvmDependenciesPackageIndex
import com.google.devtools.ksp.standalone.KspStandaloneDirectInheritorsProvider
import com.google.devtools.ksp.standalone.buildKspLibraryModule
import com.google.devtools.ksp.standalone.buildKspSdkModule
import com.google.devtools.ksp.standalone.buildKspSourceModule
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.Origin
import com.intellij.core.CoreApplicationEnvironment
import com.intellij.diagnostic.PluginException
import com.intellij.diagnostic.PluginProblemReporter
import com.intellij.mock.MockProject
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.PackageIndex
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.impl.ZipHandler
import com.intellij.openapi.vfs.impl.jar.CoreJarFileSystem
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiTreeChangeAdapter
import com.intellij.psi.PsiTreeChangeListener
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.ui.EDT
import java.io.File
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaIdeApi
import org.jetbrains.kotlin.analysis.api.KaImplementationDetail
import org.jetbrains.kotlin.analysis.api.KaPlatformInterface
import org.jetbrains.kotlin.analysis.api.fir.utils.KaFirCacheCleaner
import org.jetbrains.kotlin.analysis.api.platform.KotlinMessageBusProvider
import org.jetbrains.kotlin.analysis.api.platform.KotlinPlatformSettings
import org.jetbrains.kotlin.analysis.api.platform.KotlinProjectMessageBusProvider
import org.jetbrains.kotlin.analysis.api.platform.declarations.*
import org.jetbrains.kotlin.analysis.api.platform.lifetime.KotlinAlwaysAccessibleLifetimeTokenFactory
import org.jetbrains.kotlin.analysis.api.platform.lifetime.KotlinLifetimeTokenFactory
import org.jetbrains.kotlin.analysis.api.platform.modification.KotlinModificationTrackerFactory
import org.jetbrains.kotlin.analysis.api.platform.modification.publishGlobalSourceModuleStateModificationEvent
import org.jetbrains.kotlin.analysis.api.platform.packages.KotlinPackagePartProviderFactory
import org.jetbrains.kotlin.analysis.api.platform.packages.KotlinPackageProviderFactory
import org.jetbrains.kotlin.analysis.api.platform.permissions.KotlinAnalysisPermissionOptions
import org.jetbrains.kotlin.analysis.api.platform.resolution.KaResolutionActivityTracker
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaSourceModule
import org.jetbrains.kotlin.analysis.api.resolve.extensions.KaResolveExtensionProvider
import org.jetbrains.kotlin.analysis.api.standalone.KotlinStaticPackagePartProviderFactory
import org.jetbrains.kotlin.analysis.api.standalone.base.KotlinStandalonePlatformSettings
import org.jetbrains.kotlin.analysis.api.standalone.base.declarations.KotlinStandaloneAnnotationsResolverFactory
import org.jetbrains.kotlin.analysis.api.standalone.base.declarations.KotlinStandaloneDeclarationProviderMerger
import org.jetbrains.kotlin.analysis.api.standalone.base.modification.KotlinStandaloneModificationTrackerFactory
import org.jetbrains.kotlin.analysis.api.standalone.base.permissions.KotlinStandaloneAnalysisPermissionOptions
import org.jetbrains.kotlin.analysis.api.standalone.base.projectStructure.FirStandaloneServiceRegistrar
import org.jetbrains.kotlin.analysis.api.standalone.base.projectStructure.StandaloneProjectFactory
import org.jetbrains.kotlin.analysis.low.level.api.fir.api.getResolutionFacade
import org.jetbrains.kotlin.analysis.low.level.api.fir.lazy.resolve.LLFirResolutionActivityTracker
import org.jetbrains.kotlin.analysis.low.level.api.fir.providers.LLSealedInheritorsProvider
import org.jetbrains.kotlin.analysis.project.structure.builder.KtModuleBuilder
import org.jetbrains.kotlin.analysis.project.structure.builder.KtModuleProviderBuilder
import org.jetbrains.kotlin.cli.jvm.compiler.CliMetadataFinderFactory
import org.jetbrains.kotlin.cli.jvm.compiler.CliVirtualFileFinderFactory
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreApplicationEnvironmentMode
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreProjectEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.setupIdeaStandaloneExecution
import org.jetbrains.kotlin.cli.jvm.index.JvmDependenciesDynamicCompoundIndex
import org.jetbrains.kotlin.config.ApiVersion
import org.jetbrains.kotlin.config.JvmTarget
import org.jetbrains.kotlin.config.KotlinCompilerVersion
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.config.LanguageVersion
import org.jetbrains.kotlin.config.LanguageVersionSettingsImpl
import org.jetbrains.kotlin.diagnostics.KtRegisteredDiagnosticFactoriesStorage
import org.jetbrains.kotlin.fir.declarations.SealedClassInheritorsProvider
import org.jetbrains.kotlin.fir.session.registerResolveComponents
import org.jetbrains.kotlin.load.kotlin.MetadataFinderFactory
import org.jetbrains.kotlin.load.kotlin.PackagePartProvider
import org.jetbrains.kotlin.load.kotlin.VirtualFileFinderFactory
import org.jetbrains.kotlin.platform.CommonPlatforms
import org.jetbrains.kotlin.platform.JsPlatform
import org.jetbrains.kotlin.platform.TargetPlatform
import org.jetbrains.kotlin.platform.js.JsPlatforms
import org.jetbrains.kotlin.platform.jvm.JdkPlatform
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.platform.konan.NativePlatform
import org.jetbrains.kotlin.platform.konan.NativePlatforms
import org.jetbrains.kotlin.platform.wasm.WasmPlatforms
import org.jetbrains.kotlin.psi.KtFile

/** The stock backend: the standalone, FIR based Analysis API. */
class StandaloneBackend : KspBackend {
    override val name: String get() = "aa"

    override fun open(config: KSPConfig, disposable: Disposable): KspBackendSession {
        setupIdeaStandaloneExecution()
        synchronized(activeExecutionsLock) {
            activeExecutions++
        }
        return try {
            StandaloneSession(config, disposable)
        } catch (t: Throwable) {
            endExecution(null)
            throw t
        }
    }

    internal companion object {
        private val activeExecutionsLock = Any()
        private var activeExecutions = 0

        /**
         * Ends a session opened by [open]. The application environment, and with it the jar file
         * system, is shared by concurrent KSP executions, so its caches are only cleared when the
         * last active execution ends.
         */
        fun endExecution(env: KotlinCoreProjectEnvironment?) {
            synchronized(activeExecutionsLock) {
                if (--activeExecutions == 0) {
                    (env?.environment?.jarFileSystem as? CoreJarFileSystem)?.clearHandlersCache()
                    // CoreJarFileSystem.myHandlers uses ConcurrentFactoryMap, which can construct
                    // duplicate CoreJarHandler instances on concurrent lookups of an uncached JAR
                    // and discard the loser via putIfAbsent. Because each CoreJarHandler opens a
                    // ZipFile in ZipHandler's static cache (keyed by handler identity),
                    // clearHandlersCache() only closes the winning handler. Also clear
                    // ZipHandler's cache to close any orphaned ZipFile handles when the
                    // application environment stays alive across runs.
                    ZipHandler.clearFileAccessorCache()
                }
            }
        }
    }
}

@OptIn(KaPlatformInterface::class)
@Suppress("UnstableApiUsage")
private class StandaloneSession(kspConfig: KSPConfig, projectDisposable: Disposable) : KspBackendSession {
    private val env: KotlinCoreProjectEnvironment
    private val modules: List<KaModule>
    override val project: Project
    override val module: KaSourceModule
    override val extras: KcExtras = FirExtras()
    private val javaFileManager: IncrementalJavaFileManager?
    private val psiManager: PsiManager

    init {
        val (project, env, modules) = createAASession(kspConfig, projectDisposable)
        this.env = env
        this.modules = modules
        this.project = project
        psiManager = PsiManager.getInstance(project)
        // KspModuleBuilder ensures this is always a KtSourceModule
        module = modules.single() as KaSourceModule
        javaFileManager = if (kspConfig is KSPJvmConfig) {
            project.getService(IncrementalJavaFileManager::class.java)
        } else {
            null
        }
    }

    override fun sourceFiles(): List<KSFile> = prepareAllKSFiles(env, modules, javaFileManager)

    override fun beginRound(lookupTracker: DualLookupTracker) {
        // FirSession in AA is created lazily. Getting it instantiates module providers, which requires source roots
        // to be resolved. Therefore, due to the implementation, it has to be registered repeatedly after the files
        // are created.
        val firSession = module.getResolutionFacade(project)
        firSession.useSiteFirSession.registerResolveComponents(
            KtRegisteredDiagnosticFactoriesStorage(),
            lookupTracker
        )
    }

    override fun dropCaches() {
        project.publishGlobalSourceModuleStateModificationEvent()
        psiManager.dropPsiCaches()
    }

    override fun addFiles(kotlinFiles: List<File>, javaFiles: List<File>): List<KSFile> =
        prepareNewKSFiles(env, javaFileManager, kotlinFiles, javaFiles)

    override fun close() {
        StandaloneBackend.endExecution(env)
    }

    @OptIn(KaExperimentalApi::class, KaImplementationDetail::class, KaIdeApi::class)
    private fun createAASession(
        kspConfig: KSPConfig,
        projectDisposable: Disposable,
    ): Triple<MockProject, KotlinCoreProjectEnvironment, List<KaModule>> {
        val kotlinCoreProjectEnvironment: KotlinCoreProjectEnvironment =
            StandaloneProjectFactory.createProjectEnvironment(
                projectDisposable,
                KotlinCoreApplicationEnvironmentMode.Production
            )

        val project: MockProject = kotlinCoreProjectEnvironment.project

        @Suppress("UnstableApiUsage")
        CoreApplicationEnvironment.registerExtensionPoint(
            project.extensionArea,
            KaResolveExtensionProvider.EP_NAME.name,
            KaResolveExtensionProvider::class.java
        )

        // replaces buildKtModuleProviderByCompilerConfiguration(compilerConfiguration)
        val projectStructureProvider = KtModuleProviderBuilder(
            kotlinCoreProjectEnvironment.environment, project
        ).apply {
            val platform = when (kspConfig) {
                is KSPJvmConfig -> {
                    val jvmTarget = JvmTarget.fromString(kspConfig.jvmTarget) ?: JvmTarget.DEFAULT
                    JvmPlatforms.jvmPlatformByTargetVersion(jvmTarget)
                }

                is KSPJsConfig -> when (kspConfig.backend) {
                    "WASM" -> WasmPlatforms.wasmJs
                    "JS" -> JsPlatforms.defaultJsPlatform
                    else -> throw IllegalArgumentException("Unknown JS backend: ${kspConfig.backend}")
                }

                is KSPNativeConfig -> NativePlatforms.nativePlatformByTargetNames(listOf(kspConfig.targetName))
                is KSPCommonConfig -> CommonPlatforms.defaultCommonPlatform
                else -> throw IllegalArgumentException("Unknown platform for config: $kspConfig")
            }

            fun KtModuleBuilder.addModuleDependencies(moduleName: String) {
                addRegularDependency(
                    buildKspLibraryModule {
                        this.platform = platform
                        addBinaryRoots(kspConfig.libraries.map { it.toPath() })
                        libraryName = "Libraries for $moduleName"
                    }
                )

                addFriendDependency(
                    buildKspLibraryModule {
                        this.platform = platform
                        addBinaryRoots(kspConfig.friends.map { it.toPath() })
                        libraryName = "Friends of $moduleName"
                    }
                )

                if (kspConfig is KSPJvmConfig && kspConfig.jdkHome != null) {
                    addRegularDependency(
                        buildKspSdkModule {
                            this.platform = platform
                            addBinaryRootsFromJdkHome(kspConfig.jdkHome!!.toPath(), isJre = false)
                            libraryName = "JDK for $moduleName"
                        }
                    )
                }
            }

            buildKspSourceModule {
                val languageVersion = LanguageVersion.fromFullVersionString(kspConfig.languageVersion)!!
                val apiVersion = LanguageVersion.fromFullVersionString(kspConfig.apiVersion)!!
                languageVersionSettings = LanguageVersionSettingsImpl(
                    languageVersion,
                    ApiVersion.createByLanguageVersion(apiVersion),
                    specificFeatures = mapOf(
                        LanguageFeature.AnnotationDefaultTargetMigrationWarning to LanguageFeature.State.ENABLED,
                        LanguageFeature.PropertyParamAnnotationDefaultTargetMode to LanguageFeature.State.ENABLED
                    )
                )

                this.platform = platform
                this.moduleName = kspConfig.moduleName

                addModuleDependencies(moduleName)

                // Single file java source roots are added in reinitJavaFileManager() later.
                val roots = mutableListOf<File>()
                roots.addAll(kspConfig.sourceRoots)
                roots.addAll(kspConfig.commonSourceRoots)
                if (kspConfig is KSPJvmConfig) {
                    roots.addAll(kspConfig.javaSourceRoots)
                }
                addSourceRoots(roots.map { it.toPath() })
            }.apply(::addModule)

            this.platform = platform
        }.build()

        // register services and build session
        val ktModuleProviderImpl = projectStructureProvider
        val modules = ktModuleProviderImpl.allModules
        val allSourceFiles = ktModuleProviderImpl.allSourceFiles
        StandaloneProjectFactory.registerServicesForProjectEnvironment(
            kotlinCoreProjectEnvironment,
            projectStructureProvider,
        )
        val ktFiles = allSourceFiles.filterIsInstance<KtFile>()
        val libraryRoots = StandaloneProjectFactory.getAllBinaryRoots(modules, kotlinCoreProjectEnvironment.environment)
        val createPackagePartProvider =
            StandaloneProjectFactory.createPackagePartsProvider(
                libraryRoots,
            )

        kotlinCoreProjectEnvironment.registerApplicationServices(
            org.jetbrains.kotlin.analysis.api.permissions.KaAnalysisPermissionRegistry::class.java,
            org.jetbrains.kotlin.analysis.api.impl.base.permissions.KaBaseAnalysisPermissionRegistry::class.java
        )
        kotlinCoreProjectEnvironment.registerApplicationServices(
            KotlinAnalysisPermissionOptions::class.java,
            KotlinStandaloneAnalysisPermissionOptions::class.java
        )
        kotlinCoreProjectEnvironment.registerApplicationServices(
            KaResolutionActivityTracker::class.java,
            LLFirResolutionActivityTracker::class.java
        )

        kotlinCoreProjectEnvironment.registerApplicationServices(
            PluginProblemReporter::class.java,
            PluginProblemReporter { message, cause, _ ->
                PluginException(message, cause, null)
            }::class.java
        )

        registerProjectServices(
            kspConfig,
            kotlinCoreProjectEnvironment,
            ktFiles,
            createPackagePartProvider,
            libraryRoots.map { it.file }.distinct(),
        )

        CoreApplicationEnvironment.registerExtensionPoint(
            project.extensionArea, PsiTreeChangeListener.EP.name, PsiTreeChangeAdapter::class.java
        )
        return Triple(
            kotlinCoreProjectEnvironment.project,
            kotlinCoreProjectEnvironment,
            modules
        )
    }

    private fun <T> KotlinCoreProjectEnvironment.registerApplicationServices(
        serviceInterface: Class<T>,
        serviceImplementation: Class<out T>
    ) {
        val application = environment.application
        if (application.getServiceIfCreated(serviceInterface) == null) {
            KotlinCoreEnvironment.underApplicationLock {
                if (application.getServiceIfCreated(serviceInterface) == null) {
                    application.registerService(serviceInterface, serviceImplementation)
                }
            }
        }
    }

    // TODO: org.jetbrains.kotlin.analysis.providers.impl.KotlinStatic*
    private fun registerProjectServices(
        kspConfig: KSPConfig,
        kotlinCoreProjectEnvironment: KotlinCoreProjectEnvironment,
        ktFiles: List<KtFile>,
        packagePartProvider: (GlobalSearchScope) -> PackagePartProvider,
        libraryRoots: List<VirtualFile> = emptyList(),
    ) {
        val project = kotlinCoreProjectEnvironment.project
        project.apply {
            registerService(
                KotlinMessageBusProvider::class.java,
                KotlinProjectMessageBusProvider::class.java
            )
            FirStandaloneServiceRegistrar.registerProjectServices(project)
            FirStandaloneServiceRegistrar.registerProjectExtensionPoints(project)
            FirStandaloneServiceRegistrar.registerProjectModelServices(
                project,
                kotlinCoreProjectEnvironment.parentDisposable
            )

            registerService(
                KotlinModificationTrackerFactory::class.java,
                KotlinStandaloneModificationTrackerFactory::class.java
            )
            registerService(
                KotlinLifetimeTokenFactory::class.java,
                KotlinAlwaysAccessibleLifetimeTokenFactory::class.java
            )

            // Despite being a static implementation, this is only used by IDE tests
            registerService(
                KotlinAnnotationsResolverFactory::class.java,
                KotlinStandaloneAnnotationsResolverFactory(project, ktFiles)
            )
            registerService(
                KotlinDeclarationProviderFactory::class.java,
                IncrementalKotlinDeclarationProviderFactory(this, kotlinCoreProjectEnvironment.environment)
            )
            registerService(
                KotlinDirectInheritorsProvider::class.java,
                KspStandaloneDirectInheritorsProvider::class.java
            )
            registerService(
                KotlinDeclarationProviderMerger::class.java,
                KotlinStandaloneDeclarationProviderMerger(this)
            )
            // Package provider factory requires binary library roots to discover packages
            // declared in precompiled KLIBs (e.g. for Kotlin/Native and JS/Wasm targets).
            registerService(
                KotlinPackageProviderFactory::class.java,
                IncrementalKotlinPackageProviderFactory(project, libraryRoots)
            )

            registerService(
                SealedClassInheritorsProvider::class.java,
                LLSealedInheritorsProvider::class.java,
            )

            registerService(
                KotlinPackagePartProviderFactory::class.java,
                KotlinStaticPackagePartProviderFactory(packagePartProvider)
            )

            registerService(
                KotlinPlatformSettings::class.java,
                KotlinStandalonePlatformSettings()
            )
            // replace KaFirStopWorldCacheCleaner with no op implementation
            @OptIn(KaImplementationDetail::class)
            registerService(KaFirCacheCleaner::class.java, NoOpCacheCleaner::class.java)

            if (kspConfig is KSPJvmConfig) {
                val rootsIndex = JvmDependenciesDynamicCompoundIndex(true)
                val fileFinderFactory = CliVirtualFileFinderFactory(rootsIndex, false, null)
                // VirtualFileFinderFactory, MetadataFinderFactory, and PackageIndex are already
                // registered during project environment creation with separate indices, so we use
                // replaceService to point them at our own custom rootsIndex. Replacing them here is
                // safe because consumers look up these services lazily when analysis begins in
                // execute(), and all classpath/source roots are indexed in rootsIndex.
                replaceService(VirtualFileFinderFactory::class.java, fileFinderFactory)
                replaceService(
                    MetadataFinderFactory::class.java,
                    CliMetadataFinderFactory(fileFinderFactory)
                )
                replaceService(PackageIndex::class.java, JvmDependenciesPackageIndex(rootsIndex))
                registerService(
                    IncrementalJavaFileManager::class.java,
                    IncrementalJavaFileManager(kotlinCoreProjectEnvironment, rootsIndex)
                )
            }
        }
    }

    @OptIn(KaExperimentalApi::class)
    private fun prepareAllKSFiles(
        kotlinCoreProjectEnvironment: KotlinCoreProjectEnvironment,
        modules: List<KaModule>,
        javaFileManager: IncrementalJavaFileManager?,
    ): List<KSFile> {
        val project = kotlinCoreProjectEnvironment.project
        val ktFiles = mutableSetOf<KtFile>()
        val javaFiles = mutableSetOf<PsiJavaFile>()
        modules.filterIsInstance<KaSourceModule>().forEach { kaSourceModule ->
            kaSourceModule.psiRoots.forEach { psiRoot ->
                when (psiRoot) {
                    is KtFile -> ktFiles.add(psiRoot)
                    is PsiJavaFile -> if (javaFileManager != null) javaFiles.add(psiRoot)
                }
            }
        }

        // Update Kotlin providers for newly generated source files.
        (
            project.getService(
                KotlinDeclarationProviderFactory::class.java
            ) as IncrementalKotlinDeclarationProviderFactory
            ).update(ktFiles)
        (
            project.getService(
                KotlinPackageProviderFactory::class.java
            ) as IncrementalKotlinPackageProviderFactory
            ).update(ktFiles)

        javaFileManager?.initialize(modules, javaFiles)

        return ktFiles.map { analyze { KSFileImpl.getCached(it.symbol) } } +
            javaFiles.map { KSFileJavaImpl.getCached(it) }
    }

    private fun prepareNewKSFiles(
        kotlinCoreProjectEnvironment: KotlinCoreProjectEnvironment,
        javaFileManager: IncrementalJavaFileManager?,
        newKotlinFiles: List<File>,
        newJavaFiles: List<File>,
    ): List<KSFile> {
        val project = kotlinCoreProjectEnvironment.project
        val ktFiles = getPsiFilesFromPaths<KtFile>(
            project,
            newKotlinFiles.map { it.toPath() }.toSet()
        ).toSet()
        val javaFiles = if (javaFileManager != null) {
            getPsiFilesFromPaths<PsiJavaFile>(
                project,
                newJavaFiles.map { it.toPath() }.toSet()
            ).toSet()
        } else emptySet()

        // Add new files to content scope.
        val contentScope = ResolverAAImpl.ktModule.contentScope as IncrementalGlobalSearchScope
        contentScope.addAll(ktFiles.map { it.virtualFile })
        contentScope.addAll(javaFiles.map { it.virtualFile })

        // Update Kotlin providers for newly generated source files.
        (
            project.getService(
                KotlinDeclarationProviderFactory::class.java
            ) as IncrementalKotlinDeclarationProviderFactory
            ).update(ktFiles)
        (
            project.getService(
                KotlinPackageProviderFactory::class.java
            ) as IncrementalKotlinPackageProviderFactory
            ).update(ktFiles)

        // Update Java providers for newly generated source files.
        javaFileManager?.add(javaFiles)

        return ktFiles.map { analyze { KSFileImpl.getCached(it.symbol) } } +
            javaFiles.map { KSFileJavaImpl.getCached(it) }
    }
}

/*
* NoOp implementation of the KaFirCacheCleaner
*
* The stop world cache cleaner that is registered by default [KaFirStopWorldCacheCleaner] can
* get analysis session into an invalid state which leads to build failures.
*/
@OptIn(KaImplementationDetail::class)
class NoOpCacheCleaner : KaFirCacheCleaner {
    override fun enterAnalysis() {}
    override fun exitAnalysis() {}
    override fun scheduleCleanup() {}
}

private fun <T : Any> MockProject.replaceService(
    serviceInterface: Class<T>,
    serviceImplementation: T,
) {
    picoContainer.unregisterComponent(serviceInterface.name)
    registerService(serviceInterface, serviceImplementation)
}
