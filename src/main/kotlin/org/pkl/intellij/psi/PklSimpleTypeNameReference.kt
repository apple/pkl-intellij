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
import org.pkl.intellij.resolve.ResolveVisitors
import org.pkl.intellij.resolve.Resolvers

/** May refer to a class, type alias, module (via import), or type variable. */
class PklSimpleTypeNameReference(private val simpleTypeName: PklSimpleTypeName) :
  PsiReferenceBase<PklSimpleTypeName>(simpleTypeName), PklReference {

  override fun getRangeInElement(): TextRange =
    ElementManipulators.getValueTextRange(simpleTypeName)

  override fun getCanonicalText(): String = simpleTypeName.identifier.text

  override fun resolveContextual(context: PklProject?): PklElement? {
    val typeName = simpleTypeName.parentOfType<PklTypeName>() ?: return null
    val moduleName = typeName.moduleName
    val simpleTypeNameText = simpleTypeName.identifier.text

    if (moduleName != null) {
      return moduleName.resolve(context)?.cache(context)?.types?.get(simpleTypeNameText)
    }

    val base = typeName.project.pklBaseModule
    return Resolvers.resolveUnqualifiedTypeName(
      simpleTypeName,
      base,
      mapOf(),
      ResolveVisitors.firstElementNamed(simpleTypeNameText, base),
      context
    )
  }

  // returns PklTypeOrModule or PklTypeParameter
  override fun resolve(): PklElement? {
    return resolveContextual(simpleTypeName.enclosingModule?.pklProject)
  }
}
