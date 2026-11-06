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

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiAnnotation
import org.jetbrains.kotlin.analysis.api.annotations.KaAnnotationValue
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaKotlinPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol
import org.jetbrains.kotlin.analysis.api.types.KaFunctionType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.load.java.structure.JavaAnnotationArgument
import org.jetbrains.kotlin.load.kotlin.KotlinJvmBinaryClass
import org.jetbrains.kotlin.load.kotlin.TypeMappingMode
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtFile

/**
 * The facts KSP needs that the public Analysis API doesn't offer.
 *
 * Stock KSP gets them by casting Analysis API objects to their FIR
 * implementation classes; an Analysis API implementation that isn't FIR
 * based (kc) provides them through this interface instead. Each method
 * documents the FIR expression it stands for in [fir.FirExtras].
 *
 * Each KSP run sets [instance], for its thread, to its backend's implementation.
 */
interface KcExtras {
    /** `typeContext.getOptimalModeForReturnType(type.coneType, isAnnotationMethod)`. */
    fun optimalModeForReturnType(type: KaType, isAnnotationMethod: Boolean): TypeMappingMode

    /** `typeContext.getOptimalModeForValueParameter(type.coneType)`. */
    fun optimalModeForValueParameter(type: KaType): TypeMappingMode

    /** Whether [type] is a Java raw type: `type.coneType.isRaw()`. */
    fun isRawType(type: KaType): Boolean

    /** The type alias a function type was written as: `coneType.abbreviatedType?.classId`. */
    fun abbreviatedTypeAlias(type: KaFunctionType): ClassId?

    /**
     * The facade class of a top-level library callable: the class id of the
     * `JvmPackagePartSource` that is its `containerSource`, else null.
     */
    fun packagePartClass(symbol: KaCallableSymbol): ClassId?

    /**
     * The facade class of a source file's top-level callables: the
     * `facadeClassFqName` of its `KtLightClassForFacade`, else null.
     */
    fun fileFacadeClass(file: KtFile): FqName?

    /**
     * The class a library property is compiled into: the class id of its
     * `containerSource`, a `JvmPackagePartSource` or a
     * `KotlinJvmBinarySourceElement`, else null.
     */
    fun binaryContainerClass(symbol: KaKotlinPropertySymbol): ClassId?

    /**
     * The default value of a Java annotation method as an annotation value:
     * [default] (or an unknown argument when it is null) converted with
     * `toFirExpression` and `FirAnnotationValueConverter`.
     */
    fun javaAnnotationDefault(symbol: KaValueParameterSymbol, default: JavaAnnotationArgument?): KaAnnotationValue?

    /** Makes the symbol's types available: `firSymbol.lazyResolveToPhase(TYPES)`. */
    fun resolveTypes(symbol: KaSymbol)

    /**
     * The module name recorded in the metadata of the class containing a
     * library declaration, or null when there is none.
     */
    fun libraryModuleName(symbol: KaSymbol): String?

    /** Reads the Kotlin class in a class file: `ClsKotlinBinaryClassCache.getKotlinBinaryClass`. */
    fun kotlinBinaryClass(file: VirtualFile): KotlinJvmBinaryClass?

    /**
     * The class of a Java annotation that `resolveAnnotationType()` doesn't
     * resolve, else null. With FIR, light classes make every Kotlin class
     * resolvable from Java, so this is always null there.
     */
    fun javaAnnotationClass(annotation: PsiAnnotation): ClassId?

    companion object {
        private val current: ThreadLocal<KcExtras> = ThreadLocal()

        var instance: KcExtras
            get() = current.get() ?: error("no KSP run on this thread")
            set(value) = current.set(value)

        fun tearDown() = current.remove()
    }
}
