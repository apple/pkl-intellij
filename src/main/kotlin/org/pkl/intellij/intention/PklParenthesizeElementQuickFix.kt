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
package org.pkl.intellij.intention

import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement
import com.intellij.codeInspection.util.IntentionFamilyName
import com.intellij.codeInspection.util.IntentionName
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import org.pkl.intellij.psi.PklAccessExpr
import org.pkl.intellij.psi.memberAccessTextRange

class PklParenthesizeElementQuickFix(element: PklAccessExpr, private val memberNameText: String) :
  LocalQuickFixAndIntentionActionOnPsiElement(element) {
  override fun invoke(
    project: Project,
    file: PsiFile,
    editor: Editor?,
    startElement: PsiElement,
    endElement: PsiElement,
  ) {
    startElement as PklAccessExpr
    val textRange = startElement.memberAccessTextRange
    val document = file.viewProvider.document ?: return
    val psiDocumentManager = PsiDocumentManager.getInstance(project)
    psiDocumentManager.doPostponedOperationsAndUnblockDocument(document)
    // insert the closing paren first so the start offset stays valid
    document.insertString(textRange.endOffset, ")")
    document.insertString(textRange.startOffset, "(")
    psiDocumentManager.commitDocument(document)
  }

  override fun getText(): @IntentionName String = "Wrap in '${memberNameText}' in parentheses"

  override fun getFamilyName(): @IntentionFamilyName String = "Wrap in parentheses"
}
