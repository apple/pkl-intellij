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

import com.intellij.codeInsight.editorActions.EnterHandler
import com.intellij.codeInsight.editorActions.MultiCharQuoteHandler
import com.intellij.codeInsight.editorActions.QuoteHandler
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.highlighter.HighlighterIterator
import com.intellij.psi.tree.TokenSet
import org.pkl.intellij.psi.PklElementTypes.*

// For documentation of (MultiChar)QuoteHandler API see:
// https://github.com/klazuka/intellij-elm/blob/2e681f818e3ab35185a68470d928fe16b2e86abf/src/main/kotlin/org/elm/ide/typing/ElmQuoteHandler.kt
// To handle custom string delimiters correctly, we'll likely need our own TypedHandler instead of
// this.
class PklQuoteHandler : QuoteHandler, MultiCharQuoteHandler {
  companion object {
    private val STRING_TOKENS =
      TokenSet.create(
        STRING_START,
        STRING_END,
        ML_STRING_START,
        ML_STRING_END,
        STRING_CHARS,
        CHAR_ESCAPE,
        UNICODE_ESCAPE,
        INVALID_ESCAPE
      )
  }

  override fun isOpeningQuote(iterator: HighlighterIterator, offset: Int): Boolean {
    val tokenType = iterator.tokenType
    return tokenType === STRING_START || tokenType === ML_STRING_START
  }

  override fun isClosingQuote(iterator: HighlighterIterator, offset: Int): Boolean {
    val tokenType = iterator.tokenType
    return tokenType === STRING_END || tokenType === ML_STRING_END
  }

  override fun hasNonClosedLiteral(
    editor: Editor,
    iterator: HighlighterIterator,
    offset: Int
  ): Boolean {
    return iterator.tokenType !== STRING_END && iterator.tokenType !== ML_STRING_END
  }

  override fun isInsideLiteral(iterator: HighlighterIterator): Boolean {
    return STRING_TOKENS.contains(iterator.tokenType)
  }

  override fun getClosingQuote(iterator: HighlighterIterator, offset: Int): CharSequence? {
    val document = iterator.document.immutableCharSequence
    var position = offset - 1

    var quoteCount = 0
    while (position >= 0 && document[position] == '"') {
      quoteCount += 1
      position -= 1
    }

    if (quoteCount != 1 && quoteCount != 3) return null

    var poundCount = 0
    while (position >= 0 && document[position] == '#') {
      poundCount += 1
      position -= 1
    }

    val closingQuote = if (quoteCount == 1) "\"" else "\n\n\"\"\""
    return when {
      poundCount == 0 || offset < document.length && document[offset] == '#' -> closingQuote
      else ->
        closingQuote + document.subSequence(position + 1, position + 1 + poundCount).toString()
    }
  }

  override fun insertClosingQuote(editor: Editor, offset: Int, closingQuote: CharSequence) {
    super.insertClosingQuote(editor, offset, closingQuote)

    if (closingQuote[0] != '\n') return

    // adjust closing multiline string quote and caret
    val newOffset =
      EnterHandler.adjustLineIndentNoCommit(PklLanguage, editor.document, editor, offset + 1)
    if (newOffset != -1) {
      editor.caretModel.moveToOffset(newOffset)
      EnterHandler.adjustLineIndentNoCommit(PklLanguage, editor.document, editor, newOffset + 1)
    }
  }
}
