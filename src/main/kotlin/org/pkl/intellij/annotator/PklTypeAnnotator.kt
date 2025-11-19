/**
 * Copyright © 2024-2025 Apple Inc. and the Pkl project authors. All rights reserved.
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
import org.pkl.intellij.intention.PklRemoveDefaultTypeQuickFix
import org.pkl.intellij.psi.PklDeclaredType
import org.pkl.intellij.psi.PklDefaultType
import org.pkl.intellij.psi.PklType
import org.pkl.intellij.psi.PklUnionType
import org.pkl.intellij.psi.pklBaseModule
import org.pkl.intellij.type.Type
import org.pkl.intellij.type.toType
import org.pkl.intellij.util.currentModule

/**
 * Validate types:
 * - Default types can only be put inside unions
 * - Union types cannot have multiple defaults
 */
class PklTypeAnnotator : PklAnnotator() {
  override fun doAnnotate(element: PsiElement, holder: AnnotationHolder) {
    when (element) {
      is PklDefaultType -> validateDefaultType(element, holder)
      is PklUnionType -> validateUnionType(element, holder)
      is PklDeclaredType -> validateDeclaredType(element, holder)
    }
  }

  private fun validateDeclaredType(type: PklDeclaredType, holder: AnnotationHolder) {
    if (type.typeArgumentList?.elements.isNullOrEmpty()) return
    val module = holder.currentModule ?: return
    val referent = type.toType(module.project.pklBaseModule, emptyMap(), module.pklProject)

    val argCount = type.typeArgumentList!!.elements.size
    val paramCount =
      when (referent) {
        is Type.Class -> referent.typeParameters.size
        is Type.Alias -> referent.typeParameters.size
        else -> return
      }
    if (paramCount == 0 || paramCount == argCount) return

    createAnnotation(
      HighlightSeverity.ERROR,
      type.textRange,
      "Type argument count mismatch. Required: 0 or $paramCount Found: $argCount",
      "Type argument count mismatch.<table><tr><td>Required:</td><td>0 or $paramCount</td></tr>" +
        "<tr><td align=\"right\">Found:</td><td>$argCount</td></tr></table>",
      holder
    )
  }

  private fun validateDefaultType(type: PklDefaultType, holder: AnnotationHolder) {
    if (type.parent !is PklUnionType) {
      holder
        .newAnnotation(HighlightSeverity.ERROR, "Only union types can have default values")
        .range(type)
        .withFix(PklRemoveDefaultTypeQuickFix(type))
        .create()
    }
  }

  private fun validateUnionType(union: PklUnionType, holder: AnnotationHolder) {
    if (union.parent is PklUnionType) return

    var totalDefaults = if (union.rightType is PklDefaultType) 1 else 0
    var type: PklType? = union.leftType

    while (type != null && totalDefaults < 2) {
      if (type is PklUnionType) {
        if (type.rightType is PklDefaultType) totalDefaults++
        type = type.leftType
      } else {
        if (type is PklDefaultType) totalDefaults++
        break
      }
    }
    if (totalDefaults > 1) {
      holder
        .newAnnotation(HighlightSeverity.ERROR, "Union types cannot have more than one default")
        .range(union)
        .create()
    }
  }
}
