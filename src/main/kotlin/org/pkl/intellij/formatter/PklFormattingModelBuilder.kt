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
package org.pkl.intellij.formatter

import com.intellij.formatting.*
import com.intellij.psi.tree.TokenSet
import org.pkl.intellij.PklLanguage
import org.pkl.intellij.PklSyntaxHighlighter
import org.pkl.intellij.psi.PklElementTypes.*

class PklFormattingModelBuilder : FormattingModelBuilder {
  companion object {
    // unary minus is handled separately
    val PREFIX_OPERATORS = TokenSet.create(NOT)

    val POSTFIX_OPERATORS = TokenSet.create(NON_NULL)

    val BINARY_OPERATORS =
      TokenSet.create(
        AND,
        ARROW,
        ASSIGN,
        COALESCE,
        DIV,
        EQUAL,
        GT,
        GTE,
        INT_DIV,
        LT,
        LTE,
        MOD,
        MUL,
        NOT_EQUAL,
        OR,
        PIPE,
        PLUS,
        POW
      )

    val PROPERTY =
      TokenSet.create(
        MODULE_MEMBER,
        CLASS_PROPERTY,
        OBJECT_PROPERTY,
      )

    val TIGHT_OPERATORS = TokenSet.create(DOT, QDOT, UNION)

    val METHOD_STYLE_KEYWORDS = TokenSet.create(IMPORT_KEYWORD, READ, READ_OR_NULL, THROW, TRACE)
  }

  override fun createModel(context: FormattingContext): FormattingModel {
    val spacingBuilder =
      SpacingBuilder(context.codeStyleSettings, PklLanguage)
        // handle "ambiguous" tokens first
        .afterInside(MINUS, UNARY_MINUS_EXPR)
        .spacing(0, 0, 0, true, 0)
        .before(TYPE_ARGUMENT_LIST)
        .spacing(0, 0, 0, true, 0)
        .before(TYPE_PARAMETER_LIST)
        .spacing(0, 0, 0, true, 0)
        .withinPairInside(LT, GT, TYPE_ARGUMENT_LIST)
        .spacing(0, 0, 0, true, 0)
        .withinPairInside(LT, GT, TYPE_PARAMETER_LIST)
        .spacing(0, 0, 0, true, 0)
        .after(PREFIX_OPERATORS)
        .spacing(0, 0, 0, false, 0)
        .before(POSTFIX_OPERATORS)
        .spacing(0, 0, 0, false, 0)
        .around(BINARY_OPERATORS)
        .spacing(1, 1, 0, true, 0)
        .around(TIGHT_OPERATORS)
        .spacing(0, 0, 0, true, 0)
        .between(METHOD_STYLE_KEYWORDS, LPAREN)
        .spacing(0, 0, 0, true, 1)
        .around(PklSyntaxHighlighter.KEYWORDS)
        .spacing(1, 1, 0, true, 1)
        .before(COMMA)
        .spacing(0, 0, 0, true, 1)
        .after(COMMA)
        .spacing(1, 1, 0, true, 1)
        .before(COLON)
        .spacing(0, 0, 0, false, 0)
        .after(COLON)
        .spacing(1, 1, 0, false, 0)
        .withinPair(LPAREN, RPAREN)
        .spacing(0, 0, 0, true, 1) // (...)
        .withinPair(LBRACK, RBRACK)
        .spacing(0, 0, 0, true, 1) // [...]
        .withinPair(LPRED, RPRED)
        .spacing(0, 0, 0, true, 1) // [[...]]
        .between(LBRACE, RBRACE)
        .spacing(0, 0, 0, true, 1) // {}
        .withinPair(LBRACE, RBRACE)
        .spacing(1, 1, 0, true, 1) // { ... }
        .before(CLASS_BODY)
        .spacing(1, 1, 0, false, 0) // class Foo {
        .before(OBJECT_BODY)
        .spacing(1, 1, 0, false, 0) // foo {
        .before(PROPERTY)
        .spacing(1, 1, 0, true, 1) // foo =

    return FormattingModelProvider.createFormattingModelForPsiFile(
      context.psiElement.containingFile,
      PklBlock(context.psiElement.node, Indent.getNoneIndent(), spacingBuilder),
      context.codeStyleSettings
    )
  }
}
