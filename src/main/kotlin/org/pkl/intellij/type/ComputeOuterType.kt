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
@file:Suppress("DuplicatedCode")

package org.pkl.intellij.type

import com.intellij.psi.PsiElement
import org.pkl.intellij.packages.dto.PklProject
import org.pkl.intellij.psi.*

/**
 * Computes the type of `outer` for the given PSI element.
 *
 * The `outer` keyword refers to the enclosing object scope that is one level up from
 * the immediate object scope. This function walks up the PSI tree to find the immediate
 * enclosing object, then continues walking to find the next enclosing object and returns
 * its type.
 */
fun PsiElement.computeOuterType(
  base: PklBaseModule,
  bindings: TypeParameterBindings,
  context: PklProject?
): Type {
  var element: PsiElement? = this
  var memberPredicateExprSeen = false
  var objectBodySeen = false
  var skipNextObjectBody = false
  var foundFirstObject = false

  while (element != null) {
    when (element) {
      is PklAmendExpr,
      is PklNewExpr -> {
        if (objectBodySeen) {
          if (foundFirstObject) {
            // This is the outer object we're looking for
            val type = element.computeExprType(base, bindings, context).amending(base, context)
            return when {
              memberPredicateExprSeen -> {
                val classType = type.toClassType(base, context) ?: return Type.Unknown
                when {
                  classType.classEquals(base.listingType) -> classType.typeArguments[0]
                  classType.classEquals(base.mappingType) -> classType.typeArguments[1]
                  else -> Type.Unknown
                }
              }
              else -> type
            }
          } else {
            // This is the immediate object, mark it as found and continue searching
            foundFirstObject = true
            objectBodySeen = false
            memberPredicateExprSeen = false
          }
        }
      }
      is PklExpr -> {
        val parent = element.parent
        if (
          parent is PklWhenGenerator && element === parent.conditionExpr ||
            parent is PklForGenerator && element === parent.iterableExpr ||
            parent is PklObjectEntry && element === parent.keyExpr
        ) {
          skipNextObjectBody = true
        } else if (parent is PklMemberPredicate && element === parent.conditionExpr) {
          memberPredicateExprSeen = true
        }
      }
      is PklObjectBody ->
        when {
          skipNextObjectBody -> skipNextObjectBody = false
          else -> objectBodySeen = true
        }
      is PklProperty,
      is PklObjectElement,
      is PklObjectEntry,
      is PklMemberPredicate -> {
        if (objectBodySeen) {
          if (foundFirstObject) {
            // This is the outer object we're looking for
            val type =
              element.computeResolvedImportType(base, bindings, context).amending(base, context)
            return when {
              memberPredicateExprSeen -> {
                val classType = type.toClassType(base, context) ?: return Type.Unknown
                when {
                  classType.classEquals(base.listingType) -> classType.typeArguments[0]
                  classType.classEquals(base.mappingType) -> classType.typeArguments[1]
                  else -> Type.Unknown
                }
              }
              else -> type
            }
          } else {
            // This is the immediate object, mark it as found and continue searching
            foundFirstObject = true
            objectBodySeen = false
            memberPredicateExprSeen = false
          }
        }
      }
      is PklConstrainedType -> {
        if (foundFirstObject) {
          return element.type.toType(base, bindings, context)
        }
      }
      is PklModule,
      is PklClass,
      is PklTypeAlias -> {
        if (foundFirstObject) {
          return element.computeResolvedImportType(base, bindings, context)
        }
      }
      is PklAnnotation -> {
        if (foundFirstObject) {
          return element.typeName?.resolve(context).computeResolvedImportType(base, bindings, context)
        }
      }
    }
    element = element.parent
  }

  return Type.Unknown
}
