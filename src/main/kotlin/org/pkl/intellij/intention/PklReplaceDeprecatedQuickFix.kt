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
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import org.pkl.intellij.psi.*
import org.pkl.intellij.psi.Deprecated
import org.pkl.intellij.util.unexpectedType

/** Quick fix for replacing usages of deprecated elements that specify `Deprecated.replaceWith`. */
class PklReplaceDeprecatedQuickFix(
  element: PsiElement /* PklAccessExpr|PklTypeName */,
  private val deprecated: Deprecated
) : LocalQuickFixAndIntentionActionOnPsiElement(element) {

  override fun getText(): String = "Replace with '${deprecated.replaceWith}'"

  override fun getFamilyName(): String = "QuickFix"

  override fun invoke(
    project: Project,
    file: PsiFile,
    editor: Editor?,
    startElement: PsiElement,
    endElement: PsiElement
  ) {
    when (startElement) {
      is PklAccessExpr -> startElement.replaceDeprecated(file as PklModule)
      is PklTypeName -> startElement.replaceDeprecated(file as PklModule)
      else -> unexpectedType(startElement)
    }
  }
}
