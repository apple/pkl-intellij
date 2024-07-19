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
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.impl.CamelHumpMatcher
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.psi.PsiElement
import com.intellij.psi.util.parentOfType
import com.intellij.util.ProcessingContext
import org.pkl.intellij.packages.dto.PklProject
import org.pkl.intellij.psi.*
import org.pkl.intellij.type.Type
import org.pkl.intellij.type.inferExprTypeFromContext

class StringLiteralTypeCompletionProvider : PklCompletionProvider() {
  override fun addCompletions(
    parameters: CompletionParameters,
    context: ProcessingContext,
    result: CompletionResultSet
  ) {

    val position = parameters.position
    val stringLiteral = position.parentOfType<PklStringLiteral>() ?: return
    val module = parameters.originalFile as? PklModule ?: return
    val base = module.project.pklBaseModule
    val moduleContext = module.pklProject

    val resultType = stringLiteral.inferExprTypeFromContext(base, mapOf(), moduleContext)
    val result2 = result.withPrefixMatcher(CamelHumpMatcher(result.prefixMatcher.prefix, false))
    addCompletionResults(resultType, position, result2, null, base, moduleContext)
  }

  private fun addCompletionResults(
    resultType: Type,
    position: PsiElement,
    result: CompletionResultSet,
    originalAlias: Type.Alias?,
    base: PklBaseModule,
    context: PklProject?
  ) {

    when (resultType) {
      is Type.StringLiteral -> {
        val stringLiteral = position.parentOfType<PklStringLiteral>()
        if (stringLiteral != null) {
          val startDelimiter = stringLiteral.stringStart.text
          // TODO: how to replace entire PklStringContent rather than just the STRING_CHARS token?
          // (not a big deal in practice because string literal types can't contain interpolation
          // tokens
          // and are unlikely to contain escape tokens)
          val lookupElement =
            LookupElementBuilder.create(resultType.value)
              .withPresentableText(resultType.render(startDelimiter))
              .withTypeText((originalAlias ?: base.stringType).render(), true)
          result.addElement(lookupElement)
        }
      }
      is Type.Union -> {
        val stringLiteral = position.parentOfType<PklStringLiteral>()
        if (stringLiteral != null) {
          resultType.eachElementType { type ->
            addCompletionResults(type, position, result, originalAlias, base, context)
          }
        }
      }
      is Type.Alias -> {
        addCompletionResults(
          resultType.aliasedType(base, context),
          position,
          result,
          resultType,
          base,
          context
        )
      }
      else -> {}
    }
  }
}
