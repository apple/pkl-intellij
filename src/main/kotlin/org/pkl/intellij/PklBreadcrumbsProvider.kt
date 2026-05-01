/**
 * Copyright © 2024-2026 Apple Inc. and the Pkl project authors. All rights reserved.
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

import com.intellij.lang.Language
import com.intellij.psi.PsiElement
import com.intellij.ui.breadcrumbs.BreadcrumbsProvider
import org.pkl.intellij.psi.*
import org.pkl.intellij.util.toDisplayText

class PklBreadcrumbsProvider : BreadcrumbsProvider {
  override fun getLanguages(): Array<Language> = arrayOf(PklLanguage)

  override fun acceptElement(elem: PsiElement): Boolean =
    handlers.firstOrNull { it.accepts(elem) } != null

  override fun getElementInfo(elem: PsiElement): String =
    handlers.firstOrNull { it.accepts(elem) }!!.getElementInfo(elem)

  private interface PklBreadcrumbHandler {
    fun accepts(elem: PsiElement): Boolean

    fun getElementInfo(element: PsiElement): String
  }

  private inline fun <reified T : PklElement> handler(
    crossinline handle: (T) -> String
  ): PklBreadcrumbHandler {
    return object : PklBreadcrumbHandler {
      override fun accepts(elem: PsiElement): Boolean = elem is T

      override fun getElementInfo(element: PsiElement): String = handle(element as T)
    }
  }

  private val classHandler = handler<PklClass> { it.identifier?.text ?: "<class>" }

  private val typealiasHandler = handler<PklTypeAlias> { it.identifier?.text ?: "<typealias>" }

  private val propertyHandler = handler<PklClassProperty> { it.propertyName.identifier.text }

  private val objectMemberHandler = handler<PklObjectMember> { it.presentation!!.presentableText!! }

  private val annotationHandler =
    handler<PklAnnotation> { elem -> elem.typeName?.text?.let { "@$it" } ?: "<annotation>" }

  private val methodHandler = handler<PklMethod> { it.presentation.presentableText!! }

  private val newExprHandler = handler<PklNewExpr> { it.toDisplayText()!! }

  private val amendExprHandler = handler<PklAmendExpr> { it.toDisplayText()!! }

  private fun PsiElement.isThenBody(): Boolean {
    val p = parent
    if (p !is PklIfExpr) return false
    return p.thenExpr == this
  }

  private fun PsiElement.isElseBody(): Boolean {
    val p = parent
    if (p !is PklIfExpr) return false
    return p.elseExpr == this
  }

  private val ifThenHandler =
    object : PklBreadcrumbHandler {
      override fun accepts(elem: PsiElement): Boolean = elem.isThenBody()

      override fun getElementInfo(element: PsiElement): String {
        val ifExpr = element.parent as PklIfExpr
        return buildString {
          if (element.parent.isElseBody()) append("if … else ")
          append("if (")
          append(ifExpr.conditionExpr?.toDisplayText() ?: "…")
          append(")")
        }
      }
    }

  private val elseHandler =
    object : PklBreadcrumbHandler {
      override fun accepts(elem: PsiElement): Boolean =
        elem.parent is PklIfExpr &&
          elem.isElseBody() &&
          // filter out `else if`
          elem !is PklIfExpr

      override fun getElementInfo(element: PsiElement): String = buildString {
        val ifExpr = element.parent as PklIfExpr
        append("if (")
        append(ifExpr.conditionExpr?.toDisplayText() ?: "…")
        append(") else")
      }
    }

  private val letExprHandler =
    handler<PklLetExpr> { elem ->
      val captured = elem.typedIdentifier?.identifier?.text ?: return@handler "<let>"
      "let ($captured = …)"
    }

  private val handlers =
    listOf(
      classHandler,
      typealiasHandler,
      propertyHandler,
      objectMemberHandler,
      annotationHandler,
      methodHandler,
      newExprHandler,
      amendExprHandler,
      ifThenHandler,
      elseHandler,
      letExprHandler
    )
}
