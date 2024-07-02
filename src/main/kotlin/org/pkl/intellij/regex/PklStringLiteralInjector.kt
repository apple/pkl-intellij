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
package org.pkl.intellij.regex

import com.intellij.lang.Language
import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import java.util.*
import org.intellij.lang.regexp.RegExpLanguage
import org.pkl.intellij.psi.*
import org.pkl.intellij.psi.impl.PklStringContentImpl
import org.pkl.intellij.resolve.ResolveVisitors

class PklStringLiteralInjector : MultiHostInjector {
  override fun elementsToInjectIn(): List<Class<out PsiElement>> =
    listOf(PklStringContentImpl::class.java)

  override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
    if (context !is PklStringContentImpl) return

    // assertion: `context` is a valid constant string literal
    // (see [PklStringContentBase.isValidHost()])
    if (isRegex(context)) {
      registrar
        .startInjecting(RegExpLanguage.INSTANCE)
        .addPlace(null, null, context, TextRange(0, context.textLength))
        .doneInjecting()
      return
    }

    val sourceCode = getSourceCode(context)
    if (sourceCode != null) {
      val isPklExpr = sourceCode.language == "PklExpr"
      val languageId = if (isPklExpr) "Pkl" else sourceCode.language
      val language =
        Language.findLanguageByID(languageId)
          ?: Language.findLanguageByID(languageId.uppercase(Locale.getDefault()))
      if (language != null) {
        // For language `PklExpr`, embed expr in an object w/ unresolvable parent.
        // This results in this-type `unknown`, which avoids "cannot resolve property/method"
        // errors.
        val effectivePrefix = if (isPklExpr) "` ` = new ` ` { ` ` = " else sourceCode.prefix
        val effectiveSuffix = if (isPklExpr) " }" else sourceCode.suffix
        registrar
          .startInjecting(language)
          .addPlace(effectivePrefix, effectiveSuffix, context, TextRange(0, context.textLength))
          .doneInjecting()
      }
    }
  }

  private fun isRegex(context: PklStringContent): Boolean {
    if (context.parent is PklMlStringLiteral) return false

    val accessExpr = context.parent?.parent?.parent as? PklUnqualifiedAccessExpr? ?: return false
    val looksLikeRegex =
      accessExpr.memberName.identifier.text == "Regex" &&
        accessExpr.argumentList?.elements?.size == 1
    if (!looksLikeRegex) return false

    val project = context.project
    val base = project.pklBaseModule
    val resolved =
      accessExpr.resolve(
        base,
        null,
        mapOf(),
        ResolveVisitors.firstElementNamed(
          "Regex",
          base,
        ),
        accessExpr.enclosingModule?.pklProject
      )
    return resolved == base.regexConstructor
  }

  private fun getSourceCode(context: PklStringContent): SourceCode? {
    // only support `prop = "string-literal"` for now
    // ideally, `@SourceCode` would be an annotation targeting *types*
    val property = context.parent?.parent
    if (property !is PklProperty) return null

    val projectContext = context.enclosingModule?.pklProject
    val propertyDef =
      when {
        property is PklClassProperty && property.isDefinition(projectContext) -> property
        else -> property.propertyName.resolve(projectContext) as? PklClassProperty ?: return null
      }
    return propertyDef.annotationList.sourceCode
  }
}
