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
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.util.PsiTreeUtil
import org.pkl.intellij.externalreader.ReaderKind
import org.pkl.intellij.externalreader.pklExternalReaderService
import org.pkl.intellij.packages.dto.PklProject
import org.pkl.intellij.psi.PklModule
import org.pkl.intellij.psi.PklObjectEntry
import org.pkl.intellij.psi.PklProperty

/**
 * Warns when a `PklProject` declares an external module or resource reader that doesn't work: the
 * executable can't be started, it isn't an external reader (it closes early or answers with
 * something unintelligible), or it doesn't support the scheme it's declared for.
 *
 * Finding that out means starting the reader and asking it (an `InitializeModuleReaderRequest` or
 * `InitializeResourceReaderRequest`; the reader answers without a spec if it doesn't handle the
 * scheme). That blocks on a child process, so this is an [ExternalAnnotator]: it runs outside of
 * the read action.
 */
class PklExternalReaderSchemeAnnotator :
  ExternalAnnotator<
    PklExternalReaderSchemeAnnotator.Info,
    List<PklExternalReaderSchemeAnnotator.Problem>,
  >() {
  class Info(
    val project: Project,
    val pklProject: PklProject,
    val declarations: List<Declaration>,
  )

  class Declaration(
    val scheme: String,
    val kind: ReaderKind,
    val range: TextRange,
    val key: SmartPsiElementPointer<PsiElement>,
  )

  class Problem(
    val range: TextRange,
    val message: String,
    val key: SmartPsiElementPointer<PsiElement>,
  )

  override fun collectInformation(file: PsiFile, editor: Editor, hasErrors: Boolean): Info? =
    collectInformation(file)

  override fun collectInformation(file: PsiFile): Info? {
    if (file !is PklModule || file.name != "PklProject") return null
    // only projects that were synced know about the readers they declare
    val pklProject = file.pklProject ?: return null
    val declarations = findDeclaredReaders(file)
    if (declarations.isEmpty()) return null
    return Info(file.project, pklProject, declarations)
  }

  override fun doAnnotate(info: Info): List<Problem> {
    val service = info.project.pklExternalReaderService
    return info.declarations.mapNotNull { declaration ->
      service
        .checkProjectReader(declaration.scheme, declaration.kind, info.pklProject)
        ?.problem(declaration.scheme)
        ?.let { Problem(declaration.range, it, declaration.key) }
    }
  }

  override fun apply(file: PsiFile, result: List<Problem>, holder: AnnotationHolder) {
    for (problem in result) {
      holder
        .newAnnotation(HighlightSeverity.WARNING, problem.message)
        .range(problem.range)
        .problemGroup(PklProblemGroups.externalReaderError)
        .apply {
          val key = problem.key.element ?: return@apply
          for (fix in PklProblemGroups.externalReaderError.getSuppressQuickFixes(key, file)) {
            withFix(fix)
          }
        }
        .create()
    }
  }

  /**
   * The entries of `evaluatorSettings.externalModuleReaders` and `.externalResourceReaders`: the
   * scheme, and where its key is.
   */
  private fun findDeclaredReaders(module: PklModule): List<Declaration> {
    val result = mutableListOf<Declaration>()
    for (settings in module.properties.filter { it.name == "evaluatorSettings" }) {
      for (readers in PsiTreeUtil.findChildrenOfType(settings, PklProperty::class.java)) {
        val kind =
          when (readers.name) {
            "externalModuleReaders" -> ReaderKind.MODULE
            "externalResourceReaders" -> ReaderKind.RESOURCE
            else -> continue
          }
        for (entry in PsiTreeUtil.findChildrenOfType(readers, PklObjectEntry::class.java)) {
          // only the readers' own entries, not entries of something nested inside of them
          if (PsiTreeUtil.getParentOfType(entry, PklProperty::class.java) != readers) continue
          val key = entry.keyExpr ?: continue
          val scheme = key.text.removeSurrounding("\"")
          if (scheme.isEmpty() || scheme == key.text) continue // not a plain string literal
          // Checking whether the reader works is expensive, so skip it if the warning is
          // suppressed anyway.
          if (PklAnnotator.isSuppressedByComment(key, PklProblemGroups.externalReaderError))
            continue
          val pointer =
            SmartPointerManager.getInstance(module.project)
              .createSmartPsiElementPointer(key as PsiElement)
          result.add(Declaration(scheme, kind, key.textRange, pointer))
        }
      }
    }
    return result
  }
}
