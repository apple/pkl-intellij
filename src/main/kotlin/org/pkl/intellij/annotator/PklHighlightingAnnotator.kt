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
package org.pkl.intellij.annotator

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.tree.LeafPsiElement
import org.pkl.intellij.color.PklColor
import org.pkl.intellij.psi.*

/** Performs semantic highlighting on Pkl files. */
class PklHighlightingAnnotator : PklAnnotator() {
  override fun doAnnotate(element: PsiElement, holder: AnnotationHolder) {
    if (element !is LeafPsiElement) return
    val color = element.color ?: return
    val severity = if (isUnitTestMode) color.testSeverity else HighlightSeverity.INFORMATION
    holder.newSilentAnnotation(severity).textAttributes(color.textAttributesKey).create()
  }

  private val LeafPsiElement.color: PklColor?
    get() =
      when (elementType) {
        // Technically, we can color this in PklSyntaxHighlighter.
        // However, we do this here so that `@` and the identifier get colored in at the same time.
        PklElementTypes.AT -> PklColor.ANNOTATION
        PklElementTypes.IDENTIFIER -> parent.color
        else -> null
      }

  private val PsiElement.color: PklColor?
    get() =
      when (this) {
        is PklTypedIdentifier -> parent.color
        is PklPropertyName -> PklColor.PROPERTY
        is PklUnqualifiedAccessName,
        is PklQualifiedAccessName,
        is PklSuperAccessName ->
          when (val referent = reference?.resolve()) {
            null ->
              when (val parent = parent) {
                is PklAccessExpr ->
                  // note: unresolved unqualified non-method access is colored as PROPERTY
                  if (parent.isPropertyAccess) PklColor.PROPERTY else PklColor.METHOD
                else -> null
              }
            else -> referent.color
          }
        is PklSimpleTypeName ->
          when (parent?.parent) {
            is PklAnnotation -> PklColor.ANNOTATION
            else ->
              when (val referent = reference?.resolve()) {
                null -> PklColor.CLASS // color unresolved type as CLASS
                else ->
                  when (val referentColor = referent.color) {
                    PklColor.MODULE -> PklColor.CLASS // color module class as CLASS
                    else -> referentColor
                  }
              }
          }
        is PklModuleName ->
          when (parent?.parent) {
            is PklAnnotation -> PklColor.ANNOTATION
            else -> PklColor.MODULE
          }
        is PklProperty -> PklColor.PROPERTY
        is PklMethod -> PklColor.METHOD
        is PklClass -> PklColor.CLASS
        is PklTypeAlias -> PklColor.TYPE_ALIAS
        is PklModule -> PklColor.MODULE
        is PklImport -> PklColor.MODULE
        is PklTypeParameter -> PklColor.PARAMETER
        is PklParameterList -> PklColor.PARAMETER
        is PklLetExpr -> PklColor.LOCAL_VARIABLE
        is PklForGenerator -> PklColor.LOCAL_VARIABLE
        else -> null
      }
}
