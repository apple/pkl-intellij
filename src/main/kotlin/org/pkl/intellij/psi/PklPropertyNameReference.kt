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
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.util.parentOfTypes
import org.pkl.intellij.packages.dto.PklProject
import org.pkl.intellij.resolve.PklResolveResult
import org.pkl.intellij.resolve.ResolveVisitor
import org.pkl.intellij.resolve.ResolveVisitors
import org.pkl.intellij.resolve.Resolvers
import org.pkl.intellij.type.computeThisType
import org.pkl.intellij.util.unexpectedType

class PklPropertyNameReference(private val propertyName: PklPropertyName) :
  PsiPolyVariantReferenceBase<PklPropertyName>(propertyName), PklReference {

  override fun getRangeInElement(): TextRange = ElementManipulators.getValueTextRange(propertyName)

  override fun resolveContextual(context: PklProject?): PklElement? {
    val name = propertyName.identifier.text
    val base = propertyName.project.pklBaseModule
    return doResolve(
      ResolveVisitors.firstElementNamed(
        name,
        base,
      ),
      context
    )
  }

  override fun resolve(): PklElement? = resolveContextual(propertyName.enclosingModule?.pklProject)

  override fun multiResolve(incompleteCode: Boolean): Array<PklResolveResult> {
    val name = propertyName.identifier.text
    val base = propertyName.project.pklBaseModule
    val context = propertyName.enclosingModule?.pklProject
    return doResolve(ResolveVisitors.resolveResultsNamed(name, base), context)
  }

  private fun <R> doResolve(visitor: ResolveVisitor<R>, context: PklProject?): R {
    val base = propertyName.project.pklBaseModule

    return when (val property = propertyName.parentOfTypes(PklProperty::class)) {
      is PklProperty ->
        when {
          property.type != null -> return visitor.result // defined here, no need to visit
          property.isLocal -> return visitor.result // defined here, no need to visit
          else -> {
            val receiverType = property.computeThisType(base, mapOf(), context)
            Resolvers.resolveQualifiedAccess(receiverType, true, base, visitor, context)
          }
        }
      else -> unexpectedType(null)
    }
  }
}
