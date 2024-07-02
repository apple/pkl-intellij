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
import org.pkl.intellij.intention.PklChangeOrAddAmendsClauseQuickFix
import org.pkl.intellij.packages.dto.PklProject
import org.pkl.intellij.psi.*
import org.pkl.intellij.type.computeExprType
import org.pkl.intellij.type.computeThisType

class PklProjectAnnotator : PklAnnotator() {

  private fun List<PklObjectBody>.findOutputValue(): List<PklExpr>? {
    for (body in this) {
      val outputValue = body.findOutputValue()
      if (outputValue.isNotEmpty()) return outputValue
    }
    return null
  }

  private fun PklObjectBody.findOutputValue(): List<PklExpr> {
    return listOfNotNull(properties.find { it.name == "value" }?.expr)
  }

  private infix fun List<PklExpr>?.plus(that: List<PklExpr>?): List<PklExpr>? {
    return if (this == null) that else if (that == null) this else that + this
  }

  private fun PklExpr.findOutputValue(): List<PklExpr>? {
    return when (this) {
      is PklNewExpr -> objectBody?.findOutputValue()
      is PklAmendExpr -> objectBody.findOutputValue()
      is PklLetExpr -> bodyExpr?.findOutputValue()
      is PklBinExpr -> leftExpr.findOutputValue() plus rightExpr?.findOutputValue()
      is PklParenthesizedExpr -> expr?.findOutputValue()
      else -> null
    }
  }

  private fun findOutputValue(element: PklModule?, context: PklProject?): List<PklExpr>? {
    if (element == null) return null
    val output = findOutput(element)
    return output?.expr?.findOutputValue()
      ?: output?.objectBodyList?.findOutputValue()
        ?: findOutputValue(element.supermodule(context), context)
  }

  private fun findOutput(element: PklModule?): PklClassProperty? {
    return if (element == null) return null else element.properties.find { it.name == "output" }
  }

  override fun doAnnotate(element: PsiElement, holder: AnnotationHolder) {
    val project = element.project
    val baseModule = project.pklBaseModule
    val projectModule = project.pklProjectModule

    if (element.containingFile.name != "PklProject" || element !is PklModule) return
    val context = element.enclosingModule?.pklProject
    val outputValue = findOutputValue(element, context)
    if (outputValue == null) {
      val elementType = element.computeThisType(baseModule, mapOf(), context)
      if (!elementType.isEquivalentTo(projectModule.projectType, baseModule, context)) {
        val textRange =
          element.extendsAmendsClause?.textRange
            ?: element.declaration?.textRange ?: element.textRange
        buildAnnotation(
            HighlightSeverity.ERROR,
            textRange,
            "Invalid module type",
            "Invalid PklProject module type",
            holder
          )
          ?.apply { withFix(PklChangeOrAddAmendsClauseQuickFix(element, "pkl:Project")) }
          ?.create()
      }
      return
    }
    for (value in outputValue) {
      val type = value.computeExprType(baseModule, mapOf(), context)
      if (value.enclosingModule == element) {
        if (!type.isEquivalentTo(projectModule.projectType, baseModule, context)) {
          createMismatchAnnotation(
            HighlightSeverity.ERROR,
            value.textRange,
            "Type",
            projectModule.projectType.render(),
            type.render(),
            holder,
            PklProblemGroups.typeMismatch
          )
        }
      }
    }
  }
}
