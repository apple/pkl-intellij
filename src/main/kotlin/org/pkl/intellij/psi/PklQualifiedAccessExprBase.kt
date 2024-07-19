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
package org.pkl.intellij.psi

import com.intellij.lang.ASTNode
import org.pkl.intellij.packages.dto.PklProject
import org.pkl.intellij.resolve.ResolveVisitor
import org.pkl.intellij.resolve.Resolvers
import org.pkl.intellij.type.Type
import org.pkl.intellij.type.TypeParameterBindings
import org.pkl.intellij.type.computeExprType

abstract class PklQualifiedAccessExprBase(node: ASTNode) :
  PklAstWrapperPsiElement(node), PklQualifiedAccessExpr {
  override fun <R> resolve(
    base: PklBaseModule,
    receiverType: Type?,
    bindings: TypeParameterBindings,
    visitor: ResolveVisitor<R>,
    context: PklProject?
  ): R {
    val myReceiverType = receiverType ?: receiverExpr.computeExprType(base, bindings, context)
    return Resolvers.resolveQualifiedAccess(
      myReceiverType,
      isPropertyAccess,
      base,
      visitor,
      context
    )
  }
}
