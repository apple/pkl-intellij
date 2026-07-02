/**
 * Copyright © 2026 Apple Inc. and the Pkl project authors. All rights reserved.
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
package org.pkl.intellij.resolve

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import org.pkl.intellij.packages.dto.PklProject
import org.pkl.intellij.psi.enclosingModule
import org.pkl.intellij.psi.pklBaseModule

object DocCommentResolvers {
  fun resolveLink(psiManager: PsiManager, link: String, position: PsiElement): PsiElement? {
    val context = position.enclosingModule?.pklProject
    return when {
      link.contains('.') -> resolveQualifiedLink(psiManager, link, position, context)
      else -> resolveUnqualifiedLink(psiManager, link, position, context)
    }
  }

  private fun resolveQualifiedLink(
    psiManager: PsiManager,
    link: String,
    position: PsiElement,
    context: PklProject?
  ): PsiElement? {
    val parts = link.split('.')
    val base = psiManager.project.pklBaseModule
    val propertyOrMethod = parts.last()
    val isProperty = !propertyOrMethod.endsWith("()")
    val memberName = if (isProperty) propertyOrMethod else propertyOrMethod.dropLast(2)
    val visitor = ResolveVisitors.firstElementNamed(memberName, base, false)
    Resolvers.resolveQualifiedDocCommentMemberLink(
      link,
      position,
      isProperty,
      base,
      visitor,
      context
    )
    return visitor.result
  }

  private fun resolveUnqualifiedLink(
    psiManager: PsiManager,
    link: String,
    position: PsiElement,
    context: PklProject?
  ): PsiElement? {
    val isProperty = !link.endsWith("()")
    val memberName = if (isProperty) link else link.dropLast(2)
    val base = psiManager.project.pklBaseModule
    val visitor =
      ResolveVisitors.firstElementNamed(
        memberName,
        base,
      )
    return Resolvers.resolveUnqualifiedAccess(
      position,
      null,
      isProperty,
      base,
      mapOf(),
      visitor,
      context
    )
    // search for type in supermodules
    ?: if (isProperty)
        Resolvers.resolveUnqualifiedTypeName(position, base, mapOf(), visitor, context)
      else null
  }
}
