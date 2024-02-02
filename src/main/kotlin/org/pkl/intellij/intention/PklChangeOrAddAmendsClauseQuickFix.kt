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
import org.pkl.intellij.psi.PklModule
import org.pkl.intellij.psi.PklPsiFactory

class PklChangeOrAddAmendsClauseQuickFix(elem: PklModule, private val uri: String) :
  LocalQuickFixAndIntentionActionOnPsiElement(elem) {
  override fun getFamilyName(): String = "QuickFix"

  override fun getText(): String =
    if ((startElement as? PklModule)?.extendsAmendsClause != null) "Replace with `amends \"$uri\"`"
    else "Add `amends \"$uri\"`"

  override fun invoke(
    project: Project,
    file: PsiFile,
    editor: Editor?,
    startElement: PsiElement,
    endElement: PsiElement
  ) {
    startElement as PklModule
    val clause = PklPsiFactory.createAmendsClause(uri, project)
    if (startElement.extendsAmendsClause != null) {
      startElement.extendsAmendsClause!!.replace(clause)
      return
    }
    if (startElement.declaration != null) {
      val decl = startElement.declaration!!
      decl.addAfter(PklPsiFactory.createToken("\n", project), decl.lastChild)
      decl.addAfter(clause, decl.lastChild)
      if (editor != null) {
        PsiDocumentManager.getInstance(project).commitDocument(editor.document)
      }
      return
    }
    startElement.addBefore(PklPsiFactory.createToken("\n", project), startElement.firstChild)
    startElement.addBefore(clause, startElement.firstChild)
    if (editor != null) {
      PsiDocumentManager.getInstance(project).commitDocument(editor.document)
    }
  }
}
