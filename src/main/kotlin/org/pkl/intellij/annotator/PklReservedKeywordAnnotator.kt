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
package org.pkl.intellij.annotator

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.TokenSet
import org.pkl.intellij.psi.PklElementTypes
import org.pkl.intellij.psi.elementType

class PklReservedKeywordAnnotator : PklAnnotator() {
  companion object {
    private val RESERVED_KEYWORDS =
      TokenSet.create(
        PklElementTypes.PROTECTED,
        PklElementTypes.OVERRIDE,
        PklElementTypes.RECORD,
        PklElementTypes.DELETE,
        PklElementTypes.CASE,
        PklElementTypes.SWITCH,
        PklElementTypes.VARARG
      )
  }

  override fun doAnnotate(element: PsiElement, holder: AnnotationHolder) {
    if (element.elementType in RESERVED_KEYWORDS) {
      createAnnotation(
        HighlightSeverity.ERROR,
        element.textRange,
        "Keyword '${element.text}' is reserved for future use",
        "Keyword <code>${element.text}</code> is reserved for future use",
        holder
      )
    }
  }
}
