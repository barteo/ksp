/*
 * Copyright 2026 Google LLC
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
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
package com.google.devtools.ksp.impl.backend

import com.google.devtools.ksp.impl.DualLookupTracker
import com.google.devtools.ksp.processing.KSPConfig
import com.google.devtools.ksp.symbol.KSFile
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import org.jetbrains.kotlin.analysis.api.projectStructure.KaSourceModule
import java.io.File
import java.util.ServiceLoader

/**
 * An implementation of the Analysis API that KSP can run on.
 *
 * KSP's processing loop ([com.google.devtools.ksp.impl.KotlinSymbolProcessing])
 * only needs a [KspBackendSession]: a project whose `analyze` calls see the
 * module being processed, and a way to add the files each round generates.
 * The stock backend, "aa", is the standalone, FIR based Analysis API; "kc"
 * runs on the kc compiler through `libkc`.
 *
 * Backends are found with [ServiceLoader]; the system property `ksp.backend`
 * picks one by [name], else the first one found is used.
 */
interface KspBackend {
    /** The name `-Dksp.backend=` selects this backend by. */
    val name: String

    /**
     * Opens a session on the module [config] describes. Resources the
     * session registers are disposed with [disposable].
     */
    fun open(config: KSPConfig, disposable: Disposable): KspBackendSession

    companion object {
        const val PROPERTY = "ksp.backend"

        fun load(classLoader: ClassLoader = KspBackend::class.java.classLoader): KspBackend {
            val backends = ServiceLoader.load(KspBackend::class.java, classLoader).toList()
            val wanted = System.getProperty(PROPERTY)
            return if (wanted != null) {
                backends.firstOrNull { it.name == wanted }
                    ?: error("KSP backend '$wanted' not found; have ${backends.map { it.name }}")
            } else {
                backends.firstOrNull() ?: error("no ${KspBackend::class.java.name} on the class path")
            }
        }
    }
}

/** The analysis state of one KSP run. All calls are made in the run's thread. */
interface KspBackendSession {
    /** The project `analyze` resolves PSI in. */
    val project: Project

    /** The module KSP processes. */
    val module: KaSourceModule

    /** Facts the public Analysis API doesn't provide. */
    val extras: KcExtras

    /** The module's source files: Kotlin and, on the JVM, Java. Called once. */
    fun sourceFiles(): List<KSFile>

    /** Called before each round, with the tracker the round records lookups in. */
    fun beginRound(lookupTracker: DualLookupTracker)

    /** Invalidates analysis caches after a round generated files; called in a write action. */
    fun dropCaches()

    /** Adds the files a round generated to the module, returning their [KSFile]s. */
    fun addFiles(kotlinFiles: List<File>, javaFiles: List<File>): List<KSFile>

    /** Releases what the session holds; called in a write action. */
    fun close()
}
