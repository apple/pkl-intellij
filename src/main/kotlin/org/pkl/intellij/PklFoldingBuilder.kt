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
package org.pkl.intellij

import com.intellij.lang.ASTNode
import com.intellij.lang.folding.CustomFoldingBuilder
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.pkl.intellij.psi.*

// https://www.jetbrains.org/intellij/sdk/docs/reference_guide/custom_language_support/additional_minor_features.html
// should certain expressions, such as method/property bodies and let expressions, also be folded?
class PklFoldingBuilder : CustomFoldingBuilder() {
  override fun buildLanguageFoldRegions(
    result: MutableList<FoldingDescriptor>,
    root: PsiElement,
    document: Document,
    quick: Boolean
  ) {

    if (root !is PklModule) return

    if (quick) {
      root.docComment?.let { result.add(FoldingDescriptor(it, it.textRange)) }
      return
    }

    PsiTreeUtil.processElements(root) { element ->
      val elementType = element.elementType

      if (
        elementType === PklElementTypes.DOC_COMMENT ||
          elementType === PklElementTypes.BLOCK_COMMENT ||
          element is PklClassBody ||
          element is PklObjectBody
      ) {
        result.add(FoldingDescriptor(element, element.textRange))
      }

      true
    }
  }

  override fun getLanguagePlaceholderText(node: ASTNode, range: TextRange): String? =
    when (node.elementType) {
      PklElementTypes.DOC_COMMENT -> "///..."
      PklElementTypes.BLOCK_COMMENT -> "/*...*/"
      PklElementTypes.CLASS_BODY,
      PklElementTypes.OBJECT_BODY -> "{...}"
      else -> null
    }

  override fun isRegionCollapsedByDefault(node: ASTNode): Boolean {
    return node.elementType == PklElementTypes.DOC_COMMENT &&
      node.treeParent.elementType == PklElementTypes.MODULE_DECLARATION
  }
}
