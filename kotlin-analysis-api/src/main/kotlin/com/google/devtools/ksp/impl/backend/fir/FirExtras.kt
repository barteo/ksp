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
@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.google.devtools.ksp.impl.backend.fir

import com.google.devtools.ksp.impl.ResolverAAImpl
import com.google.devtools.ksp.impl.backend.KcExtras
import com.google.devtools.ksp.impl.symbol.kotlin.analyze
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiReferenceExpression
import org.jetbrains.kotlin.analysis.api.annotations.KaAnnotationValue
import org.jetbrains.kotlin.analysis.api.fir.evaluate.FirAnnotationValueConverter
import org.jetbrains.kotlin.analysis.api.fir.symbols.KaFirKotlinPropertySymbol
import org.jetbrains.kotlin.analysis.api.fir.symbols.KaFirSymbol
import org.jetbrains.kotlin.analysis.api.fir.symbols.KaFirValueParameterSymbol
import org.jetbrains.kotlin.analysis.api.fir.types.KaFirFunctionType
import org.jetbrains.kotlin.analysis.api.fir.types.KaFirType
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaKotlinPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol
import org.jetbrains.kotlin.analysis.api.types.KaFunctionType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.analysis.decompiler.stub.file.ClsKotlinBinaryClassCache
import org.jetbrains.kotlin.analysis.low.level.api.fir.api.getResolutionFacade
import org.jetbrains.kotlin.asJava.classes.KtLightClassForFacade
import org.jetbrains.kotlin.asJava.findFacadeClass
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.declarations.utils.moduleName
import org.jetbrains.kotlin.fir.java.JavaTypeParameterStack
import org.jetbrains.kotlin.fir.java.toFirExpression
import org.jetbrains.kotlin.fir.resolve.getContainingClassSymbol
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.lazyResolveToPhase
import org.jetbrains.kotlin.fir.types.abbreviatedType
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.isRaw
import org.jetbrains.kotlin.fir.types.typeContext
import org.jetbrains.kotlin.load.java.structure.JavaAnnotationArgument
import org.jetbrains.kotlin.load.kotlin.JvmPackagePartSource
import org.jetbrains.kotlin.load.kotlin.KotlinJvmBinaryClass
import org.jetbrains.kotlin.load.kotlin.KotlinJvmBinarySourceElement
import org.jetbrains.kotlin.load.kotlin.TypeMappingMode
import org.jetbrains.kotlin.load.kotlin.getOptimalModeForReturnType
import org.jetbrains.kotlin.load.kotlin.getOptimalModeForValueParameter
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtFile

/** [KcExtras] for the stock, FIR based Analysis API: the casts KSP used to make inline. */
@OptIn(SymbolInternals::class)
class FirExtras : KcExtras {
    private val classBinaryCache = ClsKotlinBinaryClassCache()

    private fun typeContext() = analyze {
        useSiteModule.getResolutionFacade(ResolverAAImpl.instance.project).useSiteFirSession.typeContext
    }

    override fun optimalModeForReturnType(type: KaType, isAnnotationMethod: Boolean): TypeMappingMode =
        typeContext().getOptimalModeForReturnType((type as KaFirType).coneType, isAnnotationMethod)

    override fun optimalModeForValueParameter(type: KaType): TypeMappingMode =
        typeContext().getOptimalModeForValueParameter((type as KaFirType).coneType)

    override fun isRawType(type: KaType): Boolean = (type as KaFirType).coneType.isRaw()

    override fun abbreviatedTypeAlias(type: KaFunctionType): ClassId? =
        (type as? KaFirFunctionType)?.coneType?.abbreviatedType?.classId

    @Suppress("UNCHECKED_CAST")
    override fun packagePartClass(symbol: KaCallableSymbol): ClassId? =
        ((symbol as? KaFirSymbol<FirCallableSymbol<*>>)?.firSymbol?.containerSource as? JvmPackagePartSource)
            ?.classId

    override fun fileFacadeClass(file: KtFile): FqName? =
        (file.findFacadeClass() as? KtLightClassForFacade)?.facadeClassFqName

    override fun binaryContainerClass(symbol: KaKotlinPropertySymbol): ClassId? =
        when (val containerSource = (symbol as? KaFirKotlinPropertySymbol<*>)?.firSymbol?.containerSource) {
            is JvmPackagePartSource -> containerSource.classId
            is KotlinJvmBinarySourceElement -> containerSource.binaryClass.classId
            else -> null
        }

    override fun javaAnnotationDefault(
        symbol: KaValueParameterSymbol,
        default: JavaAnnotationArgument,
    ): KaAnnotationValue? = (symbol as? KaFirValueParameterSymbol)?.let {
        val firSession = it.firSymbol.fir.moduleData.session
        val expectedTypeRef = it.firSymbol.fir.returnTypeRef
        val expression = default.toFirExpression(firSession, JavaTypeParameterStack.EMPTY, expectedTypeRef, null)
        FirAnnotationValueConverter.toConstantValue(expression, it.builder)
    }

    override fun libraryAnnotationDefault(symbol: KaValueParameterSymbol): KaAnnotationValue? =
        (symbol as? KaFirValueParameterSymbol)?.let {
            // For libraries with metadata older than 2.2, FIR stores an expression stub instead,
            // which does not convert to a value.
            it.firSymbol.fir.defaultValue?.let { defaultValue ->
                FirAnnotationValueConverter.toConstantValue(defaultValue, it.builder)
            }
        }

    override fun resolveTypes(symbol: KaSymbol) {
        (symbol as? KaFirSymbol<*>)?.firSymbol?.lazyResolveToPhase(FirResolvePhase.TYPES)
    }

    override fun libraryModuleName(symbol: KaSymbol): String? {
        val firSymbol = (symbol as? KaFirSymbol<*>)?.firSymbol
        val firClassSymbol = firSymbol?.getContainingClassSymbol()
        return (firClassSymbol?.fir as? FirRegularClass)?.moduleName
    }

    override fun kotlinBinaryClass(file: VirtualFile): KotlinJvmBinaryClass? =
        classBinaryCache.getKotlinBinaryClass(file)

    override fun javaAnnotationClass(annotation: PsiAnnotation): ClassId? = null

    override fun javaEnumEntry(reference: PsiReferenceExpression): CallableId? = null

    override fun javaConstant(reference: PsiReferenceExpression): Any? = null
}
