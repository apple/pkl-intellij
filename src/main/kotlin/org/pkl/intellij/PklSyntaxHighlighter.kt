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

import com.intellij.lexer.FlexAdapter
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import org.pkl.intellij.color.PklColor
import org.pkl.intellij.lexer._PklLexer
import org.pkl.intellij.psi.PklElementTypes.*

// https://www.jetbrains.org/intellij/sdk/docs/reference_guide/custom_language_support/syntax_highlighting_and_error_highlighting.html
class PklSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
  override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?) =
    PklSyntaxHighlighter()
}

class PklSyntaxHighlighter : SyntaxHighlighterBase() {
  companion object {
    val KEYWORDS: TokenSet =
      TokenSet.create(
        ABSTRACT,
        AMENDS,
        AS,
        CLASS_KEYWORD,
        CONST,
        ELSE,
        EXTENDS,
        EXTERNAL,
        FALSE,
        FOR,
        FUNCTION,
        HIDDEN,
        IF,
        IMPORT_KEYWORD,
        IMPORT_GLOB,
        IN,
        IS,
        LET,
        LOCAL,
        MODULE,
        NEW,
        NOTHING,
        NULL,
        OPEN,
        FIXED,
        OUT,
        OUTER,
        READ,
        READ_GLOB,
        READ_OR_NULL,
        SUPER,
        THIS,
        THROW,
        TRACE,
        TRUE,
        TYPEALIAS,
        UNKNOWN,
        WHEN,
        // reserved keywords
        PROTECTED,
        OVERRIDE,
        RECORD,
        DELETE,
        CASE,
        SWITCH,
        VARARG
      )

    val KEYWORD_NAMES: Set<String> = KEYWORDS.types.mapTo(mutableSetOf()) { it.toString() }

    private val PARENS = TokenSet.create(LPAREN, RPAREN)
    private val BRACKETS = TokenSet.create(LBRACK, RBRACK)
    private val BRACES = TokenSet.create(LBRACE, RBRACE)
    private val MEMBER_PREDICATES = TokenSet.create(LPRED, RPRED)

    private val STRING =
      TokenSet.create(STRING_START, STRING_END, ML_STRING_START, ML_STRING_END, STRING_CHARS)

    private val STRING_ESCAPES =
      TokenSet.create(CHAR_ESCAPE, UNICODE_ESCAPE, INTERPOLATION_START, INTERPOLATION_END)

    private val OPERATORS =
      TokenSet.create(
        AND,
        ASSIGN,
        AT,
        COLON,
        DIV,
        EQUAL,
        GT,
        GTE,
        INT_DIV,
        LT,
        LTE,
        MINUS,
        MOD,
        MUL,
        NOT,
        NOT_EQUAL,
        OR,
        PIPE,
        PLUS,
        POW,
        QDOT,
        QUESTION,
        UNION,
        SPREAD,
        QSPREAD
      )

    private val KEYS: Map<IElementType, TextAttributesKey> =
      hashMapOf<IElementType, TextAttributesKey>().apply {
        fillMap(this, KEYWORDS, PklColor.KEYWORD.textAttributesKey)
        fillMap(this, PARENS, PklColor.PARENTHESES.textAttributesKey)
        fillMap(this, BRACKETS, PklColor.BRACKETS.textAttributesKey)
        fillMap(this, BRACES, PklColor.BRACES.textAttributesKey)
        fillMap(this, OPERATORS, PklColor.OPERATOR.textAttributesKey)
        fillMap(this, STRING, PklColor.STRING.textAttributesKey)
        fillMap(this, STRING_ESCAPES, PklColor.STRING_ESCAPE.textAttributesKey)
        fillMap(this, MEMBER_PREDICATES, PklColor.MEMBER_PREDICATE.textAttributesKey)

        this[TokenType.BAD_CHARACTER] = HighlighterColors.BAD_CHARACTER
        this[NUMBER] = PklColor.NUMBER.textAttributesKey
        this[SHEBANG_COMMENT] = PklColor.LINE_COMMENT.textAttributesKey
        this[LINE_COMMENT] = PklColor.LINE_COMMENT.textAttributesKey
        this[BLOCK_COMMENT] = PklColor.BLOCK_COMMENT.textAttributesKey
        this[DOC_COMMENT_LINE] = PklColor.DOC_COMMENT.textAttributesKey
        this[IDENTIFIER] = PklColor.IDENTIFIER.textAttributesKey
        this[COMMA] = PklColor.COMMA.textAttributesKey
        this[DOT] = PklColor.DOT.textAttributesKey
        this[ARROW] = PklColor.ARROW.textAttributesKey
        this[COALESCE] = PklColor.COALESCE.textAttributesKey
        this[NON_NULL] = PklColor.NON_NULL.textAttributesKey
        this[PIPE] = PklColor.PIPE.textAttributesKey
      }
  }

  override fun getHighlightingLexer() = FlexAdapter(_PklLexer())

  override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> =
    pack(KEYS[tokenType])
}
