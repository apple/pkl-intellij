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
package org.pkl.intellij

import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegateAdapter
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType.WHITE_SPACE
import com.intellij.psi.util.parentOfType
import com.intellij.refactoring.suggested.endOffset
import com.intellij.refactoring.suggested.startOffset
import com.intellij.util.text.CharArrayUtil
import org.pkl.intellij.psi.PklAmendExpr
import org.pkl.intellij.psi.PklElementTypes.AMEND_EXPR
import org.pkl.intellij.psi.elementType

/**
 * Adapted from
 * [RsEnterInLineCommentHandler](https://github.com/intellij-rust/intellij-rust/blob/master/src/main/kotlin/org/rust/ide/typing/RsEnterInLineCommentHandler.kt)
 */
class PklAmendExprEnterHandler : EnterHandlerDelegateAdapter() {
  override fun preprocessEnter(
    file: PsiFile,
    editor: Editor,
    caretOffsetRef: Ref<Int>,
    caretAdvanceRef: Ref<Int>,
    dataContext: DataContext,
    originalHandler: EditorActionHandler?
  ): EnterHandlerDelegate.Result {
    if (file.fileType != PklFileType) {
      return EnterHandlerDelegate.Result.Continue
    }

    // get current document and commit any changes, so we'll get the latest PSI
    val document = editor.document
    PsiDocumentManager.getInstance(file.project).commitDocument(document)

    var caretOffset = caretOffsetRef.get()
    val text = document.charsSequence

    // skip following spaces and tabs
    val offset = CharArrayUtil.shiftForward(text, caretOffset, " \t")

    // figure out if the caret is at the end of the line
    val isEOL = text.startsWith("\n", offset)

    // find the PsiElement at the caret
    var elementAtCaret = file.findElementAt(offset) ?: return EnterHandlerDelegate.Result.Continue
    if (isEOL && elementAtCaret.isEolWhitespace(offset)) {
      // ... or the previous one if this is end-of-line whitespace
      elementAtCaret = elementAtCaret.prevSibling ?: return EnterHandlerDelegate.Result.Continue
    }

    elementAtCaret =
      elementAtCaret.parentOfType<PklAmendExpr>(true) ?: return EnterHandlerDelegate.Result.Continue

    while (elementAtCaret.firstChild.elementType == AMEND_EXPR) {
      elementAtCaret = elementAtCaret.firstChild
    }

    // check if the element at the caret is a line comment
    val unparenthesizedExpr =
      elementAtCaret
        .takeIf { it.elementType == AMEND_EXPR && it.children[0].text[0] != '(' }
        ?.children
        ?.get(0)
        ?: return EnterHandlerDelegate.Result.Continue

    document.replaceString(
      unparenthesizedExpr.startOffset,
      unparenthesizedExpr.endOffset,
      "(" + unparenthesizedExpr.text + ")"
    )

    caretOffset += 2
    caretOffsetRef.set(caretOffset)

    if (text.startsWith("{}", caretOffset - 1)) {
      document.insertString(caretOffset, "\n")
    }

    return EnterHandlerDelegate.Result.Default
  }

  // Returns true for
  //   ```
  //   fooo  <caret>
  //
  //
  //   ```
  //
  // Returns false for
  //   ```
  //   fooo
  //
  //   <caret>
  //   ```
  private fun PsiElement.isEolWhitespace(caretOffset: Int): Boolean {
    if (node?.elementType != WHITE_SPACE) return false
    val pos = node.text.indexOf('\n')
    return pos == -1 || caretOffset <= pos + startOffset
  }
}
