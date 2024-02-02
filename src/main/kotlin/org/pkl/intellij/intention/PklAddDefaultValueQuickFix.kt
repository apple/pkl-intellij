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
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.refactoring.suggested.endOffset
import com.intellij.refactoring.suggested.startOffset
import org.pkl.intellij.psi.PklClassProperty
import org.pkl.intellij.psi.PklClassPropertyBase
import org.pkl.intellij.psi.PklPsiFactory

class PklAddDefaultValueQuickFix(property: PklClassProperty) :
  LocalQuickFixAndIntentionActionOnPsiElement(property) {
  override fun getFamilyName(): String = "QuickFix"

  override fun getText(): String = "Add default value"

  override fun invoke(
    project: Project,
    file: PsiFile,
    editor: Editor?,
    startElement: PsiElement,
    endElement: PsiElement
  ) {
    val property = startElement as PklClassPropertyBase
    val expr = PklPsiFactory.createTodo(project)
    property.setExpr(expr)
    if (editor != null) {
      PsiDocumentManager.getInstance(project).commitDocument(editor.document)
      editor.selectionModel.setSelection(expr.startOffset, expr.endOffset)
      editor.caretModel.moveToOffset(expr.endOffset)
    }
  }
}
