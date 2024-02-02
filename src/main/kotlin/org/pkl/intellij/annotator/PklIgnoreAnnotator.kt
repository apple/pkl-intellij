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
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.parentOfTypes
import org.pkl.intellij.intention.PklMakeBlankIdentifierQuickFix
import org.pkl.intellij.intention.PklRenameIgnoreQuickFix
import org.pkl.intellij.psi.*
import org.pkl.intellij.psi.PklElementTypes.IDENTIFIER
import org.pkl.intellij.util.canModify
import org.pkl.intellij.util.currentFile

class PklIgnoreAnnotator : PklAnnotator() {
  override fun doAnnotate(element: PsiElement, holder: AnnotationHolder) {
    if (element.elementType != IDENTIFIER || !element.textMatches("_")) return
    val parent =
      element.parentOfTypes(
        PklTypedIdentifier::class,
        /* stop classes */ PklObjectBody::class,
        PklExpr::class
      )
    if (parent is PklTypedIdentifier && parent.type == null) return
    if (parent == null || parent !is PklParameterList) {
      report(element, element.textRange, holder)
    }
  }

  private fun report(expr: PsiElement, range: TextRange, holder: AnnotationHolder) {
    val annotation =
      holder
        .newAnnotation(HighlightSeverity.ERROR, "The blank identifier (_) is not valid here")
        .tooltip("The blank identifier (<code>_</code>) is not valid here")
        .range(range)
    if (holder.currentFile.canModify()) {
      annotation.withFix(PklRenameIgnoreQuickFix(expr))
      if (expr.parent is PklTypedIdentifier) {
        annotation.withFix(PklMakeBlankIdentifierQuickFix(expr.parent as PklTypedIdentifier))
      }
    }
    annotation.create()
  }
}
