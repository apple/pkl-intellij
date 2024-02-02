/**
 * Copyright © 2024 Apple Inc. and the Pkl project authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.pkl.intellij.type

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.parentOfType
import org.pkl.intellij.PklLanguage
import org.pkl.intellij.psi.*
import org.pkl.intellij.resolve.ResolveVisitors

// TODO: Returns upper bounds for some binary expression operands,
//  but since this isn't communicated to the caller,
//  ExprAnnotator doesn't report warning on successful type check.
//  Example: `Duration|DataSize + Duration|DataSize` type checks w/o warning.
fun PklExpr?.inferExprTypeFromContext(
  base: PklBaseModule,
  bindings: TypeParameterBindings,
  resolveTypeParamsInParamTypes: Boolean = true,
  canInferParentExpr: Boolean = true
): Type =
  when {
    this == null -> Type.Unknown
    bindings.isEmpty() && resolveTypeParamsInParamTypes && canInferParentExpr -> {
      val project = base.project
      val cacheManager = CachedValuesManager.getManager(project)
      val psiManager = PsiManager.getInstance(project)
      cacheManager.getCachedValue(this) {
        CachedValueProvider.Result.create(
          doInferExprTypeFromContext(project.pklBaseModule, mapOf(), parent, true),
          psiManager.modificationTracker.forLanguage(PklLanguage)
        )
      }
    }
    else ->
      doInferExprTypeFromContext(
        base,
        bindings,
        parent,
        resolveTypeParamsInParamTypes,
        canInferParentExpr
      )
  }

private fun PklExpr?.doInferExprTypeFromContext(
  base: PklBaseModule,
  bindings: TypeParameterBindings,
  parent: PsiElement?,
  resolveTypeParamsInParamTypes: Boolean = true,
  canInferParentExpr: Boolean = true
): Type {
  if (this == null || parent !is PklElement) return Type.Unknown

  val expr = this

  val result =
    parent.accept(
      object : PklVisitor<Type>() {
        override fun visitExpr(parent: PklExpr): Type = Type.Unknown

        override fun visitObjectEntry(parent: PklObjectEntry): Type {
          return when (expr) {
            parent.keyExpr -> {
              val enclosingObjectType =
                parent.computeThisType(base, bindings).toClassType(base) ?: return Type.Unknown
              when {
                enclosingObjectType.classEquals(base.listingType) -> base.intType
                enclosingObjectType.classEquals(base.mappingType) ->
                  enclosingObjectType.typeArguments[0]
                else -> Type.Unknown
              }
            }
            parent.valueExpr -> {
              val defaultExpectedType by lazy {
                parent.computeResolvedImportType(base, bindings, canInferExprBody = false)
              }
              // special support for converters
              return if (
                // optimization: only compute type if within a property called "converters"
                expr.parentOfType<PklProperty>()?.name == "converters" &&
                  parent.keyExpr
                    ?.computeThisType(base, bindings)
                    ?.isSubtypeOf(base.valueRenderer, base) == true
              ) {
                val keyExpr =
                  (parent.keyExpr as? PklUnqualifiedAccessExpr) ?: return defaultExpectedType
                val visitor = ResolveVisitors.firstElementNamed(keyExpr.text, base, true)
                val resolved = keyExpr.resolve(base, null, bindings, visitor)
                if (resolved is PklClass) {
                  base.function1Type.withTypeArguments(Type.Class(resolved), base.anyType)
                } else {
                  defaultExpectedType
                }
              } else {
                defaultExpectedType
              }
            }
            else -> Type.Unknown // parse error
          }
        }

        override fun visitObjectSpread(parent: PklObjectSpread): Type {
          return when (expr) {
            parent.expr -> {
              val enclosingObjectType =
                parent.computeThisType(base, bindings).toClassType(base) ?: return base.iterableType
              val baseExpected = base.spreadType(enclosingObjectType)
              if (parent.isNullable) baseExpected.nullable(base) else baseExpected
            }
            else -> base.iterableType
          }
        }

        override fun visitMemberPredicate(parent: PklMemberPredicate): Type =
          when (expr) {
            parent.conditionExpr -> base.booleanType
            parent.valueExpr ->
              parent.computeResolvedImportType(base, bindings, canInferExprBody = false)
            else -> Type.Unknown // parse error
          }

        override fun visitWhenGenerator(parent: PklWhenGenerator): Type =
          when (expr) {
            parent.conditionExpr -> base.booleanType
            else -> Type.Unknown // parse error
          }

        override fun visitForGenerator(parent: PklForGenerator): Type =
          when (expr) {
            parent.iterableExpr -> base.iterableType
            else -> Type.Unknown // parse error
          }

        override fun visitArgumentList(parent: PklArgumentList): Type {
          val argIndex = parent.elements.indexOfFirst { it === expr }
          if (argIndex == -1) return Type.Unknown

          val accessExpr = parent.parent as PklAccessExpr
          val visitor =
            ResolveVisitors.paramTypesOfFirstMethodNamed(
              accessExpr.memberNameText,
              base,
              resolveTypeParamsInParamTypes
            )
          val paramTypes = accessExpr.resolve(base, null, bindings, visitor)
          if (paramTypes.isNullOrEmpty()) return Type.Unknown

          base.varArgsType?.let { varArgsType ->
            if (argIndex >= paramTypes.lastIndex) {
              val lastParamType = paramTypes.last()
              if (lastParamType is Type.Class && lastParamType.classEquals(varArgsType)) {
                return lastParamType.typeArguments[0]
              }
            }
          }

          return paramTypes.getOrNull(argIndex) ?: Type.Unknown
        }

        override fun visitSubscriptBinExpr(parent: PklSubscriptBinExpr): Type {
          return when (expr) {
            parent.leftExpr -> base.subscriptableType
            else -> {
              doVisitSubscriptExpr(parent.leftExpr.computeExprType(base, bindings))
            }
          }
        }

        // computes the type of `y` in `x[y]` given the type of `x`
        private fun doVisitSubscriptExpr(subscriptableType: Type): Type {
          return when (val unaliasedType = subscriptableType.unaliased(base)) {
            base.stringType -> base.intType
            base.dynamicType -> Type.Unknown
            is Type.Class -> {
              when {
                unaliasedType.classEquals(base.listType) -> base.intType
                unaliasedType.classEquals(base.setType) -> base.intType
                unaliasedType.classEquals(base.mapType) -> unaliasedType.typeArguments[0]
                unaliasedType.classEquals(base.listingType) -> base.intType
                unaliasedType.classEquals(base.mappingType) -> unaliasedType.typeArguments[0]
                else -> Type.Unknown // unsupported type
              }
            }
            is Type.Union ->
              Type.union(
                doVisitSubscriptExpr(unaliasedType.leftType),
                doVisitSubscriptExpr(unaliasedType.rightType),
                base
              )
            else -> Type.Unknown // unsupported type
          }
        }

        override fun visitExponentiationBinExpr(parent: PklExponentiationBinExpr): Type {
          return when (expr) {
            parent.leftExpr ->
              Type.union(base.numberType, base.dataSizeType, base.durationType, base)
            else -> base.numberType
          }
        }

        override fun visitMultiplicativeBinExpr(parent: PklMultiplicativeBinExpr): Type {
          return doVisitMultiplicativeBinExpr(
            parent.otherExpr(expr).computeExprType(base, bindings)
          )
        }

        private fun doVisitMultiplicativeBinExpr(otherType: Type): Type {
          return when (val unaliasedType = otherType.unaliased(base)) {
            base.durationType -> Type.union(base.numberType, base.durationType, base)
            base.dataSizeType -> Type.union(base.numberType, base.dataSizeType, base)
            is Type.Union ->
              Type.union(
                doVisitMultiplicativeBinExpr(unaliasedType.leftType),
                doVisitMultiplicativeBinExpr(unaliasedType.rightType),
                base
              )
            // int/float/number/unsupported type
            else -> base.multiplicativeOperandType
          }
        }

        override fun visitAdditiveBinExpr(parent: PklAdditiveBinExpr): Type {
          return doVisitAdditiveBinExpr(parent.otherExpr(expr).computeExprType(base, bindings))
        }

        private fun doVisitAdditiveBinExpr(otherType: Type): Type {
          return when (val unaliasedType = otherType.unaliased(base)) {
            base.stringType -> base.stringType
            base.intType,
            base.floatType,
            base.numberType -> base.numberType
            base.durationType -> base.durationType
            base.dataSizeType -> base.dataSizeType
            is Type.Class ->
              when {
                unaliasedType.classEquals(base.listType) ||
                  unaliasedType.classEquals(base.setType) ||
                  unaliasedType.classEquals(base.collectionType) -> base.collectionType
                unaliasedType.classEquals(base.mapType) -> base.mapType
                // unsupported type
                else -> base.additiveOperandType
              }
            is Type.Union ->
              Type.union(
                doVisitAdditiveBinExpr(unaliasedType.leftType),
                doVisitAdditiveBinExpr(unaliasedType.rightType),
                base
              )
            // unsupported type
            else -> base.additiveOperandType
          }
        }

        override fun visitComparisonBinExpr(parent: PklComparisonBinExpr): Type {
          return doVisitComparisonBinExpr(parent.otherExpr(expr).computeExprType(base, bindings))
        }

        private fun doVisitComparisonBinExpr(otherType: Type): Type {
          return when (val unaliasedType = otherType.unaliased(base)) {
            base.stringType -> base.stringType
            base.intType,
            base.floatType,
            base.numberType -> base.numberType
            base.durationType -> base.durationType
            base.dataSizeType -> base.dataSizeType
            is Type.Union ->
              Type.union(
                doVisitComparisonBinExpr(unaliasedType.leftType),
                doVisitComparisonBinExpr(unaliasedType.rightType),
                base
              )
            else -> base.comparableType // unsupported type
          }
        }

        override fun visitLogicalAndBinExpr(parent: PklLogicalAndBinExpr): Type = base.booleanType

        override fun visitPipeBinExpr(parent: PklPipeBinExpr): Type =
          when (expr) {
            parent.rightExpr -> {
              val paramType = parent.leftExpr.computeExprType(base, mapOf())
              val returnType = inferParentExpr(parent)
              Type.function1(paramType, returnType, base)
            }
            parent.leftExpr -> doVisitPipeBinExpr(parent.rightExpr.computeExprType(base, mapOf()))
            else -> Type.Unknown // parse error
          }

        private fun doVisitPipeBinExpr(rightExprType: Type): Type {
          return when (val unaliasedType = rightExprType.unaliased(base)) {
            is Type.Class ->
              when {
                unaliasedType.classEquals(base.function1Type) -> unaliasedType.typeArguments[0]
                else -> Type.Unknown // unsupported type
              }
            is Type.Union ->
              Type.union(
                doVisitPipeBinExpr(unaliasedType.leftType),
                doVisitPipeBinExpr(unaliasedType.rightType),
                base
              )
            else -> Type.Unknown // unsupported type
          }
        }

        override fun visitIfExpr(parent: PklIfExpr): Type =
          when (expr) {
            parent.conditionExpr -> base.booleanType
            parent.thenExpr,
            parent.elseExpr -> inferParentExpr(parent)
            else -> Type.Unknown
          }

        override fun visitLetExpr(parent: PklLetExpr): Type =
          when (expr) {
            parent.varExpr -> parent.typedIdentifier?.type.toType(base, bindings)
            parent.bodyExpr -> inferParentExpr(parent)
            else -> Type.Unknown
          }

        override fun visitParenthesizedExpr(parent: PklParenthesizedExpr): Type {
          return doInferExprTypeFromContext(
            base,
            bindings,
            parent.parent,
            resolveTypeParamsInParamTypes,
            canInferParentExpr
          )
        }

        override fun visitReadExpr(parent: PklReadExpr): Type = base.stringType

        override fun visitThrowExpr(parent: PklThrowExpr): Type = base.stringType

        override fun visitUnaryMinusExpr(parent: PklUnaryMinusExpr): Type =
          base.multiplicativeOperandType

        override fun visitLogicalNotExpr(parent: PklLogicalNotExpr): Type = base.booleanType

        private fun inferParentExpr(parent: PklExpr) =
          when {
            canInferParentExpr ->
              parent.doInferExprTypeFromContext(
                base,
                bindings,
                parent.parent,
                resolveTypeParamsInParamTypes,
                true
              )
            else -> Type.Unknown
          }
      }
    )

  return result ?: parent.computeResolvedImportType(base, bindings, canInferExprBody = false)
}
