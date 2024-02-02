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
package org.pkl.intellij.completion

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate.Result.CONTINUE
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate.Result.STOP
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import org.pkl.intellij.psi.PklElementTypes
import org.pkl.intellij.psi.PklModule
import org.pkl.intellij.psi.PklModuleUri
import org.pkl.intellij.psi.elementType

class PklAutoPopupHandler : TypedHandlerDelegate() {
  override fun checkAutoPopup(
    charTyped: Char,
    project: Project,
    editor: Editor,
    file: PsiFile
  ): Result {

    if (file !is PklModule) return CONTINUE

    if (charTyped == '"') {
      // trigger auto popup for string literals (module URIs, string literal types)
      // only takes effect if completion candidates are found
      AutoPopupController.getInstance(project).scheduleAutoPopup(editor, CompletionType.BASIC) {
        pklModule ->
        val offset = editor.caretModel.offset
        val token = pklModule.findElementAt(offset - 1)
        token?.elementType == PklElementTypes.STRING_START
      }
      return STOP
    } else if (charTyped == '/') {
      AutoPopupController.getInstance(project).scheduleAutoPopup(editor, CompletionType.BASIC) {
        pklModule ->
        val offset = editor.caretModel.offset
        val token = pklModule.findElementAt(offset - 1) ?: return@scheduleAutoPopup false
        token.elementType == PklElementTypes.STRING_CHARS &&
          token.parent.parent.parent is PklModuleUri
      }
      return STOP
    }

    return CONTINUE
  }
}
