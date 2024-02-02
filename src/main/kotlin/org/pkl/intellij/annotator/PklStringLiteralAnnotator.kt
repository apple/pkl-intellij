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
import org.pkl.intellij.intention.PklRedundantStringInterpolationQuickFix
import org.pkl.intellij.psi.*
import org.pkl.intellij.resolve.ResolveVisitors
import org.pkl.intellij.util.canModify
import org.pkl.intellij.util.currentFile
import org.pkl.intellij.util.currentProject

class PklStringLiteralAnnotator : PklAnnotator() {
  override fun doAnnotate(element: PsiElement, holder: AnnotationHolder) {
    val project = holder.currentProject
    val base = project.pklBaseModule

    when (element) {
      is PklStringLiteral -> {
        val content = element.content
        val firstChild = content.firstChild ?: return
        val lastChild = content.lastChild ?: return

        if (
          firstChild.elementType != PklElementTypes.INTERPOLATION_START ||
            lastChild.elementType != PklElementTypes.INTERPOLATION_END
        )
          return

        val expr = firstChild.nextSibling as? PklExpr ?: return
        if (lastChild.prevSibling !== expr) return

        // assertion: string literal contains nothing but a single interpolation expression

        when (expr) {
          is PklUnqualifiedAccessExpr -> {
            val visitor =
              ResolveVisitors.typeOfFirstElementNamed(
                expr.memberName.identifier.text,
                null,
                base,
                expr.isNullSafeAccess,
                false
              )
            val type = expr.resolve(base, null, mapOf(), visitor)
            if (type.isSubtypeOf(base.stringType, base)) {
              warn(element, holder)
            }
          }
        }
      }
    }
  }

  private fun warn(element: PklStringLiteral, holder: AnnotationHolder) {
    val annotation =
      holder
        .newAnnotation(HighlightSeverity.WARNING, "Redundant string interpolation")
        .range(element)
    if (holder.currentFile.canModify()) {
      annotation.withFix(PklRedundantStringInterpolationQuickFix(element))
    }
    annotation.create()
  }
}
