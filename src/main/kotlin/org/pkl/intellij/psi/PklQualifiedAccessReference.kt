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

import com.intellij.openapi.util.TextRange
import com.intellij.psi.*
import com.intellij.psi.util.parentOfType
import org.pkl.intellij.packages.dto.PklProject
import org.pkl.intellij.resolve.PklResolveResult
import org.pkl.intellij.resolve.ResolveVisitors
import org.pkl.intellij.resolve.Resolvers

class PklQualifiedAccessReference(private val accessName: PklQualifiedAccessName) :
  PsiPolyVariantReferenceBase<PklQualifiedAccessName>(accessName), PklReference {

  override fun getRangeInElement(): TextRange = ElementManipulators.getValueTextRange(accessName)

  override fun resolveContextual(context: PklProject?): PsiElement? {
    val accessExpr = accessName.parentOfType<PklQualifiedAccessExpr>() ?: return null
    val base = accessExpr.project.pklBaseModule
    val visitor =
      ResolveVisitors.firstElementNamed(
        accessExpr.memberNameText,
        base,
      )
    if (accessExpr.receiverExpr is PklModuleExpr) {
      val result =
        Resolvers.resolveUnqualifiedAccess(
          accessExpr.containingFile,
          null,
          accessExpr.argumentList == null,
          base,
          mapOf(),
          visitor,
          context
        )
      if (result != null) return result
    }
    return accessExpr.resolve(base, null, mapOf(), visitor, context)
  }

  override fun resolve(): PsiElement? = resolveContextual(null)

  override fun multiResolve(incompleteCode: Boolean): Array<PklResolveResult> {
    val accessExpr =
      accessName.parentOfType<PklQualifiedAccessExpr>() ?: return PklResolveResult.EMPTY_ARRAY
    val base = accessExpr.project.pklBaseModule
    return accessExpr.resolve(
      base,
      null,
      mapOf(),
      ResolveVisitors.resolveResultsNamed(accessExpr.memberNameText, base),
      accessExpr.enclosingModule?.pklProject
    )
  }
}
