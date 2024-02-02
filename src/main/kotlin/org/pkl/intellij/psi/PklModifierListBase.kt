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
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.parentOfType

abstract class PklModifierListBase(node: ASTNode) : PklAstWrapperPsiElement(node), PklModifierList {
  override val elements: Sequence<PsiElement>
    get() = childSeq

  override fun addModifier(type: IElementType, project: Project) {
    val modifier = PklPsiFactory.createModifier(type.toString(), project)
    val sortOrder = modifier.sortOrder
    val anchor = elements.find { it.sortOrder >= sortOrder }
    if (anchor != null) {
      addBefore(modifier, anchor)
    } else {
      if (parentOfType<PklModifierListOwner>() is PklProperty && elements.lastOrNull() == null) {
        // workaround for an issue where prepending a property with a modifier fails to add
        // whitespace
        node.addLeaf(modifier.elementType!!, modifier.text + " ", elements.lastOrNull()?.node)
      } else {
        addAfter(modifier, elements.lastOrNull())
      }
    }
  }

  private val PsiElement.sortOrder: Int
    get() =
      when (elementType) {
        PklElementTypes.ABSTRACT -> 0
        PklElementTypes.OPEN -> 0
        PklElementTypes.FIXED -> 0
        PklElementTypes.CONST -> 0
        PklElementTypes.HIDDEN -> 1
        PklElementTypes.LOCAL -> 1
        PklElementTypes.EXTERNAL -> 2
        else -> 3
      }
}
