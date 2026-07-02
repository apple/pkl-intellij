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
package org.pkl.intellij.annotator

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiElement
import org.pkl.intellij.documentation.PkldocLinkGeneratingProvider
import org.pkl.intellij.psi.PklDocComment
import org.pkl.intellij.util.escapeXml

class PklDocCommentAnnotator : PklAnnotator() {
  override fun doAnnotate(element: PsiElement, holder: AnnotationHolder) {
    if (element !is PklDocComment) return
    for (reference in element.references) {
      if (reference.fullText in PkldocLinkGeneratingProvider.keywords) continue
      val resolved = reference.resolve()
      if (resolved != null) return
      val text = reference.text
      createAnnotation(
        HighlightSeverity.WARNING,
        reference.absoluteRange,
        "Unresolved reference: $text",
        "Unresolved reference: <code>${text.escapeXml()}</code>",
        holder
      )
    }
  }
}
