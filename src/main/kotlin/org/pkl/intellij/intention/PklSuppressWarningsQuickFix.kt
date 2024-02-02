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
package org.pkl.intellij.intention

import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement
import com.intellij.codeInspection.SuppressQuickFix
import com.intellij.lang.annotation.ProblemGroup
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import org.pkl.intellij.annotator.PklAnnotator
import org.pkl.intellij.psi.PklPsiFactory
import org.pkl.intellij.psi.PklSuppressWarningsTarget
import org.pkl.intellij.psi.skipWhitespaceBack

class PklSuppressWarningsQuickFix(
  element: PsiElement,
  private val group: ProblemGroup,
  // IDE warning if not using a pointer
  private val targetPointer: SmartPsiElementPointer<PklSuppressWarningsTarget>
) : LocalQuickFixAndIntentionActionOnPsiElement(element), SuppressQuickFix {

  override fun getText(): String =
    when (val target = targetPointer.element) {
      is PsiNamedElement ->
        "Suppress '${group.problemName}' for ${target.getKind()} '${target.name}'"
      null ->
        // need to return *something*
        "Suppress '${group.problemName}'"
      else -> "Suppress '${group.problemName}' for ${target.getKind()}"
    }

  override fun getFamilyName(): String = "QuickFix"

  override fun invoke(
    project: Project,
    file: PsiFile,
    editor: Editor?,
    startElement: PsiElement,
    endElement: PsiElement
  ) {
    val target = targetPointer.element ?: return
    val parent = target.parent ?: return
    val prevSibling = target.prevSibling?.skipWhitespaceBack()
    // if there already is a suppression on this element, add to it
    if (prevSibling is PsiComment && PklAnnotator.suppressRegex.find(prevSibling.text) != null) {
      val comment =
        PklPsiFactory.createLineComment(
          "${prevSibling.text.replaceFirst(Regex("//\\s*"), "")},${group.problemName}",
          project
        )
      prevSibling.replace(comment)
      // otherwise, create a new comment
    } else {
      parent.addBefore(
        PklPsiFactory.createLineComment("noinspection ${group.problemName}", project),
        target
      )
      parent.addBefore(PklPsiFactory.createToken("\n", project), target)
    }
  }

  override fun isSuppressAll(): Boolean = false

  override fun isAvailable(project: Project, context: PsiElement): Boolean =
    context.isValid && targetPointer.element != null
}
