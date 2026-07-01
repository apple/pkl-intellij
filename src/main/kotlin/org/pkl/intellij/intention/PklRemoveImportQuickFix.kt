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

import com.intellij.codeInsight.intention.FileModifier.SafeFieldForPreview
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.richcopy.HtmlSyntaxInfoUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import java.util.*
import org.pkl.intellij.PklLanguage
import org.pkl.intellij.psi.PklImport
import org.pkl.intellij.psi.PklModule
import org.pkl.intellij.psi.pklBaseModule
import org.pkl.intellij.resolve.visitUsedLocalDefinitions

class PklRemoveImportQuickFix(import: PklImport) :
  LocalQuickFixAndIntentionActionOnPsiElement(import) {
  @SafeFieldForPreview private val removedImportTexts: List<String>

  init {
    val module = import.containingFile as? PklModule
    removedImportTexts = if (module == null) emptyList() else unusedImports(module).map { it.text }
  }

  override fun getFamilyName(): String = "QuickFix"

  override fun getText(): String = "Remove unused imports"

  override fun invoke(
    project: Project,
    file: PsiFile,
    editor: Editor?,
    startElement: PsiElement,
    endElement: PsiElement
  ) {
    val module = file as? PklModule ?: return
    for (import in unusedImports(module)) {
      (import.nextSibling as? PsiWhiteSpace)?.delete()
      import.delete()
    }
  }

  override fun generatePreview(
    project: Project,
    editor: Editor,
    file: PsiFile
  ): IntentionPreviewInfo = buildPreview(project)

  override fun generatePreview(
    project: Project,
    previewDescriptor: ProblemDescriptor
  ): IntentionPreviewInfo = buildPreview(project)

  private fun buildPreview(project: Project): IntentionPreviewInfo {
    if (removedImportTexts.isEmpty()) {
      return IntentionPreviewInfo.EMPTY
    }
    val html = buildString {
      for ((index, text) in removedImportTexts.withIndex()) {
        if (index > 0) append("<br/>")
        append("<s>")
        HtmlSyntaxInfoUtil.appendHighlightedByLexerAndEncodedAsHtmlCodeSnippet(
          this,
          project,
          PklLanguage,
          text,
          1.0f
        )
        append("</s>")
      }
    }
    return IntentionPreviewInfo.Html(HtmlChunk.raw(html))
  }

  private fun unusedImports(module: PklModule): List<PklImport> {
    val base = module.project.pklBaseModule
    val usedImports: IdentityHashMap<PklImport, PklImport> = IdentityHashMap()
    visitUsedLocalDefinitions(module, base) { if (it is PklImport) usedImports[it] = it }
    return module.imports.filter { !usedImports.contains(it) }.toList()
  }
}
