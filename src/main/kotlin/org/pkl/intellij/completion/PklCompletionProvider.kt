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

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.lookup.LookupElementBuilder

abstract class PklCompletionProvider : CompletionProvider<CompletionParameters>() {
  companion object {
    @JvmStatic
    protected val TYPE_KEYWORD_LOOKUP_ELEMENTS =
      // omit `in` and `out` because user-defined generic types aren't supported
      listOf("module", "nothing", "unknown").map { LookupElementBuilder.create(it).bold() }
    @JvmStatic
    protected val EXPRESSION_LEVEL_KEYWORD_LOOKUP_ELEMENTS =
      listOf(
          "as ",
          "else ",
          "false",
          "if ()",
          "import()",
          "import*()",
          "is ",
          "let ()",
          "module",
          "new ",
          "null",
          "outer",
          "read()",
          "read?()",
          "read*()",
          "super",
          "this",
          "throw()",
          "trace()",
          "true"
        )
        .map { keyword -> LookupElementBuilder.create(keyword).bold().postProcess(keyword) }
    @JvmStatic
    protected val DEFINITION_LEVEL_KEYWORD_LOOKUP_ELEMENTS =
      listOf("for ()", "function ", "hidden ", "local ", "fixed ", "const", "when ()").map { keyword
        ->
        LookupElementBuilder.create(keyword).bold().postProcess(keyword)
      }
    /**
     * In addition to [DEFINITION_LEVEL_KEYWORD_LOOKUP_ELEMENTS]. `"module "` replaces `"module"`.
     */
    @JvmStatic
    protected val MODULE_LEVEL_KEYWORD_LOOKUP_ELEMENTS =
      listOf(
          "abstract ",
          "amends ",
          "class ",
          "extends ",
          "import* ",
          "import ",
          "module ",
          "open ",
          "typealias "
        )
        .map { LookupElementBuilder.create(it).bold() }
    /** In addition to [DEFINITION_LEVEL_KEYWORD_LOOKUP_ELEMENTS]. */
    @JvmStatic
    protected val CLASS_LEVEL_KEYWORD_LOOKUP_ELEMENTS =
      listOf("abstract").map { LookupElementBuilder.create(it).bold() }

    private fun LookupElementBuilder.postProcess(keyword: String): LookupElementBuilder {
      if (!keyword.endsWith("()")) return this

      return this
        // let's be consistent with [UnqualifiedAccessCompletionProvider] in terms of look and feel
        .withPresentableText(keyword.dropLast(2) + "(…)")
        .withInsertHandler { context, _ ->
          context.editor.caretModel.moveCaretRelatively(-1, 0, false, false, false)
        }
    }
  }
}
