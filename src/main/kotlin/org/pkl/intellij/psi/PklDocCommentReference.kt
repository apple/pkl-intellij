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
package org.pkl.intellij.psi

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.util.startOffset
import org.pkl.intellij.documentation.PkldocLinkGeneratingProvider
import org.pkl.intellij.resolve.DocCommentResolvers

class PklDocCommentReference(
  element: PsiElement,
  private val rangeInElement: TextRange,
  private val fullLabelTextRange: TextRange
) : PsiReferenceBase<PsiElement>(element, rangeInElement) {
  override fun resolve(): PsiElement? {
    val link =
      if (
        rangeInElement != fullLabelTextRange &&
          fullLabelTextRange.endOffset == rangeInElement.endOffset
      )
        fullText
      else element.text.substring(fullLabelTextRange.startOffset, rangeInElement.endOffset)
    if (link in PkldocLinkGeneratingProvider.keywords) {
      return null
    }
    val project = element.project
    val psiManager = PsiManager.getInstance(project)
    return DocCommentResolvers.resolveLink(psiManager, link, element)
  }

  val fullText: String =
    element.text.substring(fullLabelTextRange.startOffset, fullLabelTextRange.endOffset)

  val text: String = element.text.substring(rangeInElement.startOffset, rangeInElement.endOffset)

  val fullAbsoluteRange: TextRange = fullLabelTextRange.shiftRight(element.startOffset)
}
