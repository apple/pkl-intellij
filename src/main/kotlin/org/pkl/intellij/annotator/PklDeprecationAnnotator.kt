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

import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.parentOfTypes
import org.pkl.intellij.intention.PklReplaceDeprecatedQuickFix
import org.pkl.intellij.psi.*
import org.pkl.intellij.util.canModify
import org.pkl.intellij.util.capitalized
import org.pkl.intellij.util.currentFile
import org.pkl.intellij.util.unexpectedType

class PklDeprecationAnnotator : PklAnnotator() {
  override fun doAnnotate(element: PsiElement, holder: AnnotationHolder) {
    val target = element.reference?.resolve() as? PklAnnotationListOwner ?: return
    val deprecated = target.annotationList?.deprecated ?: return
    if (isSuppressed(element, PklProblemGroups.deprecated)) return

    val targetName =
      when (target) {
        is PklModule -> target.displayName
        is PsiNamedElement -> target.name
        else -> unexpectedType(target)
      }
    val warningMessage = buildString {
      append('\'')
      append(targetName)
      append("' is deprecated")
      val message = deprecated.message
      if (!message.isNullOrEmpty()) {
        append(". ")
        append(message.capitalized())
        if (!(message.endsWith('.'))) append('.')
      }
    }
    val warningElement =
      when (element) {
        is PklModuleUri -> element.stringConstant.content
        else -> element
      }
    val annotation =
      holder
        .newAnnotation(HighlightSeverity.WARNING, warningMessage)
        .range(warningElement)
        .highlightType(ProblemHighlightType.LIKE_DEPRECATED)
        .problemGroup(PklProblemGroups.deprecated)
    if (deprecated.replaceWith != null) {
      // quick fix operates on enclosing access expression or type name
      val parent = element.parentOfTypes(PklAccessExpr::class, PklTypeName::class)
      if (parent != null && holder.currentFile.canModify()) {
        annotation.withFix(PklReplaceDeprecatedQuickFix(parent, deprecated))
      }
    }
    for (fix in PklProblemGroups.deprecated.getSuppressQuickFixes(element, holder.currentFile)) {
      annotation.withFix(fix)
    }
    annotation.create()
  }
}
