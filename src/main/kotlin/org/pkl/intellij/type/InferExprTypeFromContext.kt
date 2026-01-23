/**
 * Copyright © 2024-2026 Apple Inc. and the Pkl project authors. All rights reserved.
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
import com.intellij.psi.util.ParameterizedCachedValueProvider
import com.intellij.psi.util.parentOfType
import org.pkl.intellij.PklLanguage
import org.pkl.intellij.cacheKeyService
import org.pkl.intellij.packages.dto.PklProject
import org.pkl.intellij.packages.pklProjectService
import org.pkl.intellij.psi.*
import org.pkl.intellij.resolve.ResolveVisitors

private val inferExprTypeProvider:
  ParameterizedCachedValueProvider<Type, Pair<PklExpr, PklProject?>> =
  ParameterizedCachedValueProvider { (expr, context) ->
    val project = expr.project
    val result =
      expr.doInferExprTypeFromContext(
        project.pklBaseModule,
        mapOf(),
        expr.parent,
        context,
        true,
      )
    val dependencies = buildList {
      add(PsiManager.getInstance(project).modificationTracker.forLanguage(PklLanguage))
      if (context != null) {
        add(project.pklProjectService)
      }
    }
    CachedValueProvider.Result.create(result, dependencies)
  }

// TODO: Returns upper bounds for some binary expression operands,
//  but since this isn't communicated to the caller,
//  ExprAnnotator doesn't report warning on successful type check.
//  Example: `Duration|DataSize + Duration|DataSize` type checks w/o warning.
fun PklExpr?.inferExprTypeFromContext(
  base: PklBaseModule,
  bindings: TypeParameterBindings,
  context: PklProject?,
  resolveTypeParamsInParamTypes: Boolean = true,
  canInferParentExpr: Boolean = true
): Type =
  when {
    this == null -> Type.Unknown
    bindings.isEmpty() && resolveTypeParamsInParamTypes && canInferParentExpr ->
      CachedValuesManager.getManager(base.project)
        .getParameterizedCachedValue(
          this,
          project.cacheKeyService.getKey("PklExpr.inferExprTypeFromContext", context),
          inferExprTypeProvider,
          false,
          this to context
        )
    else ->
      doInferExprTypeFromContext(
        base,
        bindings,
        parent,
        context,
        resolveTypeParamsInParamTypes,
        canInferParentExpr
      )
  }

private fun PklExpr?.doInferExprTypeFromContext(
  base: PklBaseModule,
  bindings: TypeParameterBindings,
  parent: PsiElement?,
  context: PklProject?,
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
                parent.computeThisType(base, bindings, context).toClassType(base, context)
                  ?: return Type.Unknown
              when {
                enclosingObjectType.classEquals(base.listingType) -> base.intType
                enclosingObjectType.classEquals(base.mappingType) ->
                  enclosingObjectType.typeArguments[0]
                else -> Type.Unknown
              }
            }
            parent.valueExpr -> {
              val defaultExpectedType by lazy {
                parent.computeResolvedImportType(base, bindings, context, canInferExprBody = false)
              }
              // special support for converters/convertPropertyTransformers
              if (
                // optimization: only compute type if within a property called "converters" or
                // "convertPropertyTransformers" in a BaseValueRenderer subclass
                parent.keyExpr
                  ?.computeThisType(base, bindings, context)
                  ?.isSubtypeOf(base.baseValueRenderer, base, context) == true
              ) {
                val keyExpr =
                  (parent.keyExpr as? PklUnqualifiedAccessExpr) ?: return defaultExpectedType
                val visitor = ResolveVisitors.firstElementNamed(keyExpr.memberNameText, base, true)
                val resolvedKeyClass =
                  keyExpr.resolve(base, null, bindings, visitor, context) as? PklClass
                when (expr.parentOfType<PklProperty>()?.name) {
                  "converters" ->
                    resolvedKeyClass?.let {
                      base.function1Type.withTypeArguments(Type.Class(it), base.anyType)
                    }
                      ?: defaultExpectedType
                  "convertPropertyTransformers" ->
                    resolvedKeyClass?.let { base.mixinType.withTypeArguments(Type.Class(it)) }
                      ?: defaultExpectedType
                  else -> defaultExpectedType
                }
              } else {
                defaultExpectedType
              }
            }
            else -> Type.Unknown // parse error
          }
        }

        override fun visitObjectSpread(parent: PklObjectSpread): Type {
          val underlyingType =
            when (expr) {
              parent.expr -> {
                val enclosingObjectType =
                  parent.computeThisType(base, bindings, context).toClassType(base, context)
                if (enclosingObjectType == null) base.iterableType
                else base.spreadType(enclosingObjectType)
              }
              else -> base.iterableType
            }
          return if (parent.isNullable) underlyingType.nullable(base, context) else underlyingType
        }

        override fun visitMemberPredicate(parent: PklMemberPredicate): Type =
          when (expr) {
            parent.conditionExpr -> base.booleanType
            parent.valueExpr ->
              parent.computeResolvedImportType(base, bindings, context, canInferExprBody = false)
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
          val paramTypes = accessExpr.resolve(base, null, bindings, visitor, context)
          if (paramTypes.isNullOrEmpty()) return Type.Unknown

          if (argIndex >= paramTypes.lastIndex) {
            val lastParamType = paramTypes.last()
            if (lastParamType is Type.Class && lastParamType.classEquals(base.varArgsType)) {
              return lastParamType.typeArguments[0]
            }
          }

          return paramTypes.getOrNull(argIndex) ?: Type.Unknown
        }

        override fun visitSubscriptBinExpr(parent: PklSubscriptBinExpr): Type {
          return when (expr) {
            parent.leftExpr -> base.subscriptableType
            else -> {
              doVisitSubscriptExpr(parent.leftExpr.computeExprType(base, bindings, context))
            }
          }
        }

        // computes the type of `y` in `x[y]` given the type of `x`
        private fun doVisitSubscriptExpr(subscriptableType: Type): Type {
          return when (val unaliasedType = subscriptableType.unaliased(base, context)) {
            base.stringType -> base.intType
            base.dynamicType -> Type.Unknown
            is Type.Class -> {
              when {
                unaliasedType.classEquals(base.listType) -> base.intType
                unaliasedType.classEquals(base.setType) -> base.intType
                unaliasedType.classEquals(base.mapType) -> unaliasedType.typeArguments[0]
                unaliasedType.classEquals(base.listingType) -> base.intType
                unaliasedType.classEquals(base.mappingType) -> unaliasedType.typeArguments[0]
                base.bytesType != null && unaliasedType.classEquals(base.bytesType) -> base.intType
                else -> Type.Unknown // unsupported type
              }
            }
            is Type.Union ->
              Type.union(
                doVisitSubscriptExpr(unaliasedType.leftType),
                doVisitSubscriptExpr(unaliasedType.rightType),
                base,
                context
              )
            else -> Type.Unknown // unsupported type
          }
        }

        override fun visitExponentiationBinExpr(parent: PklExponentiationBinExpr): Type {
          return when (expr) {
            parent.leftExpr ->
              Type.union(base.numberType, base.dataSizeType, base.durationType, base, context)
            else -> base.numberType
          }
        }

        override fun visitMultiplicativeBinExpr(parent: PklMultiplicativeBinExpr): Type {
          return doVisitMultiplicativeBinExpr(
            parent.otherExpr(expr).computeExprType(base, bindings, context)
          )
        }

        private fun doVisitMultiplicativeBinExpr(otherType: Type): Type {
          return when (val unaliasedType = otherType.unaliased(base, context)) {
            base.durationType -> Type.union(base.numberType, base.durationType, base, context)
            base.dataSizeType -> Type.union(base.numberType, base.dataSizeType, base, context)
            is Type.Union ->
              Type.union(
                doVisitMultiplicativeBinExpr(unaliasedType.leftType),
                doVisitMultiplicativeBinExpr(unaliasedType.rightType),
                base,
                context
              )
            // int/float/number/unsupported type
            else -> base.multiplicativeOperandType
          }
        }

        override fun visitAdditiveBinExpr(parent: PklAdditiveBinExpr): Type {
          return doVisitAdditiveBinExpr(
            parent.otherExpr(expr).computeExprType(base, bindings, context)
          )
        }

        private fun doVisitAdditiveBinExpr(otherType: Type): Type {
          return when (val unaliasedType = otherType.unaliased(base, context)) {
            base.stringType -> base.stringType
            base.intType,
            base.floatType,
            base.numberType -> base.numberType
            base.durationType -> base.durationType
            base.dataSizeType -> base.dataSizeType
            base.bytesType -> base.bytesType
                ?:
                // if we fall through, both bytesType and unaliasedType is [null] (possible when Pkl
                // < 0.29)
                base.additiveOperandType
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
                base,
                context
              )
            // unsupported type
            else -> base.additiveOperandType
          }
        }

        override fun visitComparisonBinExpr(parent: PklComparisonBinExpr): Type {
          return doVisitComparisonBinExpr(
            parent.otherExpr(expr).computeExprType(base, bindings, context)
          )
        }

        private fun doVisitComparisonBinExpr(otherType: Type): Type {
          return when (val unaliasedType = otherType.unaliased(base, context)) {
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
                base,
                context
              )
            else -> base.comparableType // unsupported type
          }
        }

        override fun visitLogicalAndBinExpr(parent: PklLogicalAndBinExpr): Type = base.booleanType

        override fun visitPipeBinExpr(parent: PklPipeBinExpr): Type =
          when (expr) {
            parent.rightExpr -> {
              val paramType = parent.leftExpr.computeExprType(base, mapOf(), context)
              val returnType = inferParentExpr(parent)
              Type.function1(paramType, returnType, base)
            }
            parent.leftExpr ->
              doVisitPipeBinExpr(parent.rightExpr.computeExprType(base, mapOf(), context))
            else -> Type.Unknown // parse error
          }

        private fun doVisitPipeBinExpr(rightExprType: Type): Type {
          return when (val unaliasedType = rightExprType.unaliased(base, context)) {
            is Type.Class ->
              when {
                unaliasedType.classEquals(base.function1Type) -> unaliasedType.typeArguments[0]
                else -> Type.Unknown // unsupported type
              }
            is Type.Union ->
              Type.union(
                doVisitPipeBinExpr(unaliasedType.leftType),
                doVisitPipeBinExpr(unaliasedType.rightType),
                base,
                context
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
            parent.varExpr -> parent.typedIdentifier?.type.toType(base, bindings, context)
            parent.bodyExpr -> inferParentExpr(parent)
            else -> Type.Unknown
          }

        override fun visitParenthesizedExpr(parent: PklParenthesizedExpr): Type {
          return doInferExprTypeFromContext(
            base,
            bindings,
            parent.parent,
            context,
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
                context,
                resolveTypeParamsInParamTypes,
                true
              )
            else -> Type.Unknown
          }
      }
    )

  return result
    ?: parent.computeResolvedImportType(base, bindings, context, canInferExprBody = false)
}
