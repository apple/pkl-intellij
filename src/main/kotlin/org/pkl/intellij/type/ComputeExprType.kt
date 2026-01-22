/**
 * Copyright © 2024-2025 Apple Inc. and the Pkl project authors. All rights reserved.
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
@file:Suppress("DuplicatedCode")

package org.pkl.intellij.type

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.RecursionManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.TokenType
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.ParameterizedCachedValueProvider
import org.pkl.intellij.PklLanguage
import org.pkl.intellij.cacheKeyService
import org.pkl.intellij.packages.dto.PklProject
import org.pkl.intellij.packages.pklProjectService
import org.pkl.intellij.psi.*
import org.pkl.intellij.resolve.ResolveVisitors

private val logger: Logger = Logger.getInstance("org.pkl.intellij.type")

private val exprTypeProvider:
  ParameterizedCachedValueProvider<Type, Pair<PsiElement, PklProject?>> =
  ParameterizedCachedValueProvider { (elem, context) ->
    val project = elem.project
    val result = elem.doComputeExprType(project.pklBaseModule, mapOf(), context)
    val dependencies = buildList {
      add(PsiManager.getInstance(project).modificationTracker.forLanguage(PklLanguage))
      if (context != null) {
        add(project.pklProjectService)
      }
    }
    CachedValueProvider.Result.create(result, dependencies)
  }

fun PsiElement?.computeExprType(
  base: PklBaseModule,
  bindings: TypeParameterBindings,
  context: PklProject?
): Type {
  return when {
    this == null || this !is PklExpr -> Type.Unknown
    bindings.isEmpty() ->
      CachedValuesManager.getManager(base.project)
        .getParameterizedCachedValue(
          this,
          project.cacheKeyService.getKey("PsiElement.computeExprType", context),
          exprTypeProvider,
          false,
          this to context
        )
    else -> doComputeExprType(base, bindings, context)
  }
}

private fun PsiElement.doComputeExprType(
  base: PklBaseModule,
  bindings: TypeParameterBindings,
  context: PklProject?
): Type {
  return RecursionManager.doPreventingRecursion(this, false) {
    when (this) {
      is PklUnqualifiedAccessExpr -> {
        val visitor =
          ResolveVisitors.typeOfFirstElementNamed(
            memberNameText,
            argumentList,
            base,
            isNullSafeAccess,
            false
          )
        resolve(base, null, bindings, visitor, context)
      }
      is PklQualifiedAccessExpr -> {
        val visitor =
          ResolveVisitors.typeOfFirstElementNamed(
            memberNameText,
            argumentList,
            base,
            isNullSafeAccess,
            false
          )
        resolve(base, null, bindings, visitor, context)
      }
      is PklSuperAccessExpr -> {
        val visitor =
          ResolveVisitors.typeOfFirstElementNamed(
            memberNameText,
            argumentList,
            base,
            isNullSafeAccess,
            false
          )
        resolve(base, null, bindings, visitor, context)
      }
      is PklTrueLiteral,
      is PklFalseLiteral -> base.booleanType
      is PklStringLiteral -> this.content.computeStringLiteralType(base, context)
      is PklMlStringLiteral -> this.content.computeStringLiteralType(base, context)
      is PklNullLiteral -> base.nullType
      // TODO: consider having separate tokens/rules for `Int` and `Float`
      is PklNumberLiteral -> {
        val text = number.text
        when {
          text.startsWith("0x") -> base.intType
          text.contains('.') || text.contains('e', true) -> base.floatType
          else -> base.intType
        }
      }

      // inferring Listing/Mapping type parameters from elements/entries is tricky
      // because the latter are in turn inferred from Listing/Mapping types (e.g., in PklNewExpr)
      is PklAmendExpr -> parentExpr.computeExprType(base, bindings, context).amended(base, context)
      is PklNewExpr ->
        (type?.toType(base, bindings, context) ?: inferExprTypeFromContext(base, bindings, context))
          .instantiated(base, context)
      is PklThisExpr -> computeThisType(base, bindings, context)
      is PklOuterExpr -> computeOuterType(base, bindings, context)
      is PklSubscriptBinExpr -> {
        val receiverType = leftExpr.computeExprType(base, bindings, context)
        doComputeSubscriptExprType(receiverType, base, context)
      }
      is PklSuperSubscriptExpr -> {
        val receiverType = computeThisType(base, bindings, context)
        doComputeSubscriptExprType(receiverType, base, context)
      }
      is PklEqualityBinExpr -> base.booleanType
      is PklComparisonBinExpr -> base.booleanType
      is PklLogicalAndBinExpr -> base.booleanType
      is PklLogicalOrBinExpr -> base.booleanType
      is PklLogicalNotExpr -> base.booleanType
      is PklTypeTestExpr -> base.booleanType
      is PklTypeCastExpr -> type.toType(base, bindings, context)
      is PklModuleExpr -> enclosingModule?.computeResolvedImportType(base, mapOf(), context)
          ?: Type.Unknown
      is PklUnaryMinusExpr -> {
        when (expr.computeExprType(base, bindings, context)) {
          base.intType -> base.intType
          base.booleanType -> base.booleanType
          else -> Type.Unknown
        }
      }
      is PklAdditiveBinExpr -> {
        val leftType = leftExpr.computeExprType(base, bindings, context)
        val rightType = rightExpr.computeExprType(base, bindings, context)
        val op = operator.elementType
        when (leftType) {
          base.intType ->
            when (rightType) {
              base.intType -> base.intType
              base.booleanType -> base.booleanType
              else -> Type.Unknown
            }
          base.floatType ->
            when (rightType) {
              base.intType,
              base.floatType -> base.floatType
              else -> Type.Unknown
            }
          base.stringType ->
            if (op == PklElementTypes.PLUS) {
              when (rightType) {
                base.stringType,
                is Type.StringLiteral -> base.stringType
                else -> Type.Unknown
              }
            } else Type.Unknown
          base.bytesType ->
            if (op == PklElementTypes.PLUS && rightType == base.bytesType) {
              base.bytesType
            } else Type.Unknown
          is Type.StringLiteral ->
            if (op == PklElementTypes.PLUS) {
              when (rightType) {
                base.stringType -> base.stringType
                is Type.StringLiteral -> Type.StringLiteral(leftType.value + rightType.value)
                else -> Type.Unknown
              }
            } else Type.Unknown
          Type.Unknown ->
            // could be more aggressive here and try to infer the result type from the right type
            // (e.g., unknown + float = float)
            Type.Unknown
          else -> {
            val leftClassType =
              leftType.toClassType(base, context) ?: return@doPreventingRecursion Type.Unknown
            val rightClassType =
              rightType.toClassType(base, context) ?: return@doPreventingRecursion Type.Unknown
            when {
              leftClassType.classEquals(base.listType) ->
                if (op == PklElementTypes.PLUS) {
                  when {
                    rightClassType.classEquals(base.listType) ||
                      rightClassType.classEquals(base.setType) -> {
                      val typeArgs =
                        Type.union(
                          leftClassType.typeArguments[0],
                          rightClassType.typeArguments[0],
                          base,
                          context
                        )
                      base.listType.withTypeArguments(typeArgs)
                    }
                    else -> Type.Unknown
                  }
                } else Type.Unknown
              leftClassType.classEquals(base.setType) ->
                if (op == PklElementTypes.PLUS) {
                  when {
                    rightClassType.classEquals(base.listType) ||
                      rightClassType.classEquals(base.setType) -> {
                      val typeArgs =
                        Type.union(
                          leftClassType.typeArguments[0],
                          rightClassType.typeArguments[0],
                          base,
                          context
                        )
                      base.setType.withTypeArguments(typeArgs)
                    }
                    else -> Type.Unknown
                  }
                } else Type.Unknown
              leftClassType.classEquals(base.mapType) ->
                if (op == PklElementTypes.PLUS) {
                  when {
                    rightClassType.classEquals(base.mapType) -> {
                      val keyTypeArgs =
                        Type.union(
                          leftClassType.typeArguments[0],
                          rightClassType.typeArguments[0],
                          base,
                          context
                        )
                      val valueTypeArgs =
                        Type.union(
                          leftClassType.typeArguments[1],
                          rightClassType.typeArguments[1],
                          base,
                          context
                        )
                      base.mapType.withTypeArguments(keyTypeArgs, valueTypeArgs)
                    }
                    else -> Type.Unknown
                  }
                } else Type.Unknown
              else -> Type.Unknown
            }
          }
        }
      }
      is PklMultiplicativeBinExpr -> {
        val leftType = leftExpr.computeExprType(base, bindings, context)
        val rightType = rightExpr.computeExprType(base, bindings, context)
        when (operator.elementType) {
          PklElementTypes.MUL ->
            when (leftType) {
              base.intType ->
                when (rightType) {
                  base.intType -> base.intType
                  base.floatType -> base.floatType
                  else -> Type.Unknown
                }
              base.floatType ->
                when (rightType) {
                  base.intType,
                  base.floatType,
                  Type.Unknown -> base.floatType
                  else -> Type.Unknown
                }
              Type.Unknown -> {
                when (rightType) {
                  base.floatType -> base.floatType
                  else -> Type.Unknown
                }
              }
              else -> Type.Unknown
            }
          PklElementTypes.DIV ->
            when (leftType) {
              base.intType,
              base.floatType,
              Type.Unknown ->
                when (rightType) {
                  base.intType,
                  base.floatType,
                  Type.Unknown -> base.floatType
                  else -> Type.Unknown
                }
              else -> Type.Unknown
            }
          PklElementTypes.INT_DIV ->
            when (leftType) {
              base.intType,
              base.floatType,
              Type.Unknown ->
                when (rightType) {
                  base.intType,
                  base.floatType,
                  Type.Unknown -> base.intType
                  else -> Type.Unknown
                }
              else -> Type.Unknown
            }
          PklElementTypes.MOD ->
            when (leftType) {
              base.intType ->
                when (rightType) {
                  base.intType -> base.intType
                  base.floatType -> base.floatType
                  else -> Type.Unknown
                }
              base.floatType ->
                when (rightType) {
                  base.intType,
                  base.floatType,
                  Type.Unknown -> base.floatType
                  else -> Type.Unknown
                }
              Type.Unknown ->
                when (rightType) {
                  base.floatType -> base.floatType
                  else -> Type.Unknown
                }
              else -> Type.Unknown
            }
          else -> {
            // rdar://74188588 (not sure how this can happen)
            logger.error("Unexpected multiplicative operator: ${operator.elementType}")
            Type.Unknown
          }
        }
      }
      is PklExponentiationBinExpr -> base.numberType
      is PklLetExpr -> bodyExpr.computeExprType(base, bindings, context)
      is PklThrowExpr -> Type.Nothing
      is PklTraceExpr -> expr.computeExprType(base, bindings, context)
      is PklImportExpr -> resolve(context).computeResolvedImportType(base, mapOf(), false, context)
      is PklReadExpr -> {
        val result =
          when (val resourceUriExpr = expr) {
            is PklStringLiteral -> inferResourceType(resourceUriExpr, base)
            // support `read("env:" + ...)`
            is PklAdditiveBinExpr -> {
              when (val leftExpr = resourceUriExpr.leftExpr) {
                is PklStringLiteral -> inferResourceType(leftExpr, base)
                else -> Type.union(base.stringType, base.resourceType, base, context)
              }
            }
            else -> Type.union(base.stringType, base.resourceType, base, context)
          }
        if (isNullable) result.nullable(base, context)
        else if (isGlob) base.mappingType.withTypeArguments(base.stringType, result) else result
      }
      is PklIfExpr ->
        Type.union(
          thenExpr.computeExprType(base, bindings, context),
          elseExpr.computeExprType(base, bindings, context),
          base,
          context
        )
      is PklNullCoalesceBinExpr ->
        Type.union(
          leftExpr.computeExprType(base, bindings, context).nonNull(base, context),
          rightExpr.computeExprType(base, bindings, context),
          base,
          context
        )
      is PklNonNullAssertionExpr ->
        expr.computeExprType(base, bindings, context).nonNull(base, context)
      is PklPipeBinExpr -> {
        val rightType = rightExpr.computeExprType(base, bindings, context)
        val classType = rightType.toClassType(base, context)
        when {
          classType != null && classType.isFunctionType -> classType.typeArguments.last()
          rightType == Type.Unknown -> Type.Unknown
          else -> Type.Unknown
        }
      }
      is PklFunctionLiteral -> {
        val parameterTypes = parameterList.elements.map { it.type.toType(base, bindings, context) }
        val returnType = expr.computeExprType(base, bindings, context)
        when (parameterTypes.size) {
          0 -> base.function0Type.withTypeArguments(parameterTypes + returnType)
          1 -> base.function1Type.withTypeArguments(parameterTypes + returnType)
          2 -> base.function2Type.withTypeArguments(parameterTypes + returnType)
          3 -> base.function3Type.withTypeArguments(parameterTypes + returnType)
          4 -> base.function4Type.withTypeArguments(parameterTypes + returnType)
          5 -> base.function5Type.withTypeArguments(parameterTypes + returnType)
          else ->
            base.functionType.withTypeArguments(
              listOf(returnType)
            ) // approximation (invalid Pkl code)
        }
      }
      is PklParenthesizedExpr -> expr.computeExprType(base, bindings, context)
      else -> Type.Unknown
    }
  }
    ?: Type.Unknown
}

/**
 * Computes `"foobar"|"foobaz"` in the case of `"foo\(barbaz)"`, where `barbaz` computes to type
 * `"bar"|"baz"`.
 */
private fun PklStringContent.computeStringLiteralType(
  base: PklBaseModule,
  context: PklProject?
): Type {
  var stringLiterals = listOf(StringBuilder())
  eachChild { child ->
    when (child.elementType) {
      PklElementTypes.STRING_CHARS,
      TokenType.WHITE_SPACE -> stringLiterals.forEach { it.append(child.text) }
      PklElementTypes.CHAR_ESCAPE -> {
        val text = child.text
        val char =
          when (text[text.lastIndex]) {
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            '\\' -> '\\'
            '"' -> '"'
            else -> throw AssertionError("Unknown char escape: $text")
          }
        stringLiterals.forEach { it.append(char) }
      }
      PklElementTypes.UNICODE_ESCAPE -> {
        val text = child.text
        val index = text.indexOf('{') + 1
        if (index != -1) {
          val hexString = text.substring(index, text.length - 1)
          try {
            stringLiterals.forEach { it.append(Character.toChars(Integer.parseInt(hexString, 16))) }
          } catch (ignored: NumberFormatException) {} catch (ignored: IllegalArgumentException) {}
        }
      }
      PklElementTypes.INTERPOLATION_START,
      PklElementTypes.INTERPOLATION_END -> {}
      else -> {
        val childType = child.computeExprType(base, mapOf(), context)
        when {
          childType is Type.Union && childType.isUnionOfStringLiterals -> {
            // bail out if this results in over 100 string literals to avoid getting too expensive.
            if (childType.cardinality * stringLiterals.size > 100) return base.stringType
            stringLiterals =
              stringLiterals.flatMap { stringLiteral ->
                childType.map { type ->
                  type as Type.StringLiteral
                  StringBuilder(stringLiteral).append(type.value)
                }
              }
          }
          childType is Type.StringLiteral -> {
            stringLiterals.forEach { it.append(childType.value) }
          }
          else -> return base.stringType
        }
      }
    }
  }
  if (stringLiterals.size == 1) {
    return Type.StringLiteral(stringLiterals.single().toString())
  }
  return Type.union(stringLiterals.map { Type.StringLiteral(it.toString()) }, base, context)
}

private fun doComputeSubscriptExprType(
  receiverType: Type,
  base: PklBaseModule,
  context: PklProject?
) =
  when (receiverType) {
    is Type.StringLiteral -> base.stringType
    else -> {
      val receiverClassType = receiverType.toClassType(base, context)
      when {
        receiverClassType == null -> Type.Unknown
        receiverClassType.classEquals(base.stringType) -> base.stringType
        receiverClassType.classEquals(base.listType) -> receiverClassType.typeArguments[0]
        receiverClassType.classEquals(base.setType) -> receiverClassType.typeArguments[0]
        receiverClassType.classEquals(base.listingType) -> receiverClassType.typeArguments[0]
        receiverClassType.classEquals(base.mapType) -> receiverClassType.typeArguments[1]
        receiverClassType.classEquals(base.mappingType) -> receiverClassType.typeArguments[1]
        base.bytesType != null && receiverClassType.classEquals(base.bytesType) -> base.uint8Type
        else -> Type.Unknown
      }
    }
  }
