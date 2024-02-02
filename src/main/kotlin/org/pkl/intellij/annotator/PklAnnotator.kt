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

import com.intellij.codeEditor.printing.HTMLTextPainter
import com.intellij.codeInspection.SuppressionUtil
import com.intellij.lang.annotation.AnnotationBuilder
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lang.annotation.ProblemGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.util.parentsOfType
import com.intellij.xml.util.XmlStringUtil
import kotlin.reflect.KClass
import org.jetbrains.annotations.TestOnly
import org.pkl.intellij.psi.*
import org.pkl.intellij.type.ConstraintValue
import org.pkl.intellij.type.Type
import org.pkl.intellij.type.computeExprType
import org.pkl.intellij.type.toConstraintExpr
import org.pkl.intellij.util.currentFile
import org.pkl.intellij.util.escapeXml

abstract class PklAnnotator : Annotator {
  companion object {
    val suppressRegex = Regex(SuppressionUtil.COMMON_SUPPRESS_REGEXP)

    @TestOnly var enabledTestAnnotator: KClass<out PklAnnotator>? = null
  }

  protected abstract fun doAnnotate(element: PsiElement, holder: AnnotationHolder)

  final override fun annotate(element: PsiElement, holder: AnnotationHolder) {
    if (!isUnitTestMode || enabledTestAnnotator == this::class) {
      doAnnotate(element, holder)
    }
  }

  protected val isUnitTestMode = ApplicationManager.getApplication().isUnitTestMode

  protected fun checkIsAmendable(
    element: PklObjectBodyListOwner,
    parentType: Type,
    base: PklBaseModule,
    holder: AnnotationHolder
  ) {
    for (objectBody in element.objectBodyList ?: return) {
      checkIsAmendableImpl(objectBody, parentType, base, holder)
    }
  }

  protected fun checkIsAmendable(
    element: PklObjectBodyOwner,
    parentType: Type,
    base: PklBaseModule,
    holder: AnnotationHolder
  ) {

    val objectBody = element.objectBody ?: return
    return checkIsAmendableImpl(objectBody, parentType, base, holder)
  }

  private fun checkIsAmendableImpl(
    objectBody: PklObjectBody,
    parentType: Type,
    base: PklBaseModule,
    holder: AnnotationHolder
  ) {

    if (parentType.isAmendable(base)) return

    val brace = objectBody.firstChildOfType(PklElementTypes.LBRACE) ?: return

    when {
      parentType is Type.Class && parentType.classEquals(base.classType) -> {
        val instantiatedClassText = parentType.typeArguments[0].render()
        createAnnotation(
          HighlightSeverity.ERROR,
          brace.textRange,
          "Cannot instantiate class $instantiatedClassText",
          "Cannot instantiate class <code>${instantiatedClassText.escapeXml()}</code>",
          holder
        )
      }
      else -> {
        val elementTypeText = parentType.render()
        createAnnotation(
          HighlightSeverity.ERROR,
          brace.textRange,
          "Cannot amend value of type $elementTypeText",
          "Cannot amend value of type <code>${elementTypeText.escapeXml()}</code>",
          holder
        )
      }
    }
  }

  protected fun checkIsInstantiable(
    element: PklNewExpr,
    instantiatedType: Type,
    base: PklBaseModule,
    holder: AnnotationHolder
  ) {

    if (instantiatedType.isInstantiable(base)) return

    val newKeyword = element.firstChild ?: return
    val elementTypeText = instantiatedType.render()
    createAnnotation(
      HighlightSeverity.ERROR,
      newKeyword.textRange,
      "Cannot instantiate type $elementTypeText",
      "Cannot instantiate type <code>${elementTypeText.escapeXml()}</code>",
      holder
    )
  }

  protected fun checkExprType(
    expr: PklExpr?,
    expectedType: Type,
    base: PklBaseModule,
    holder: AnnotationHolder
  ) {

    if (expr == null || expectedType == Type.Unknown) return

    val exprType = expr.computeExprType(base, mapOf())
    val exprValue = lazy { expr.toConstraintExpr(base).evaluate(ConstraintValue.Error) }
    val failedConstraints = mutableListOf<Pair<Type, Int>>()
    if (!isTypeMatch(exprType, exprValue, expectedType, failedConstraints, base)) {
      when {
        failedConstraints.isEmpty() ->
          reportTypeMismatch(expr, exprType, expectedType, base, holder)
        else -> reportConstraintMismatch(expr, exprValue, failedConstraints, holder)
      }
    }
  }

  /**
   * Type and constraint checking of union types needs to be intertwined. Otherwise, if the left
   * alternative of a union type had (only) a type mismatch, and the right alternative (only) a
   * constraint mismatch, the overall check would still succeed.
   */
  private fun isTypeMatch(
    exprType: Type,
    exprValue: Lazy<ConstraintValue>,
    memberType: Type,
    failedConstraints: MutableList<Pair<Type, Int>>,
    base: PklBaseModule
  ): Boolean {

    return when {
      exprType is Type.Alias -> {
        isTypeMatch(exprType.aliasedType(base), exprValue, memberType, failedConstraints, base)
      }
      exprType is Type.Union -> {
        // This can cause multiple checks of the same top-level or nested constraint.
        // To avoid this, constraint check results could be cached while this method runs.
        isTypeMatch(exprType.leftType, exprValue, memberType, failedConstraints, base) &&
          isTypeMatch(exprType.rightType, exprValue, memberType, failedConstraints, base)
      }
      memberType is Type.Alias -> {
        isTypeMatch(exprType, exprValue, memberType.aliasedType(base), failedConstraints, base) &&
          isConstraintMatch(exprValue, memberType, failedConstraints, true)
      }
      memberType is Type.Union && !memberType.isUnionOfStringLiterals -> {
        (isTypeMatch(exprType, exprValue, memberType.leftType, failedConstraints, base) ||
          isTypeMatch(exprType, exprValue, memberType.rightType, failedConstraints, base)) &&
          isConstraintMatch(exprValue, memberType, failedConstraints, true)
      }
      else -> {
        exprType.isSubtypeOf(memberType, base) &&
          isConstraintMatch(exprValue, memberType, failedConstraints, false)
      }
    }
  }

  private fun isConstraintMatch(
    exprValue: Lazy<ConstraintValue>,
    memberType: Type,
    failedConstraints: MutableList<Pair<Type, Int>>,
    isOverride: Boolean
  ): Boolean {

    var index = 0
    var failedIndex: Int = -1
    for (constraint in memberType.constraints) {
      if (constraint.evaluate(exprValue.value) == ConstraintValue.False) {
        failedIndex = index
        break
      }
      index += 1
    }

    if (failedIndex == -1) return true

    if (isOverride) failedConstraints.clear()
    failedConstraints.add(memberType to failedIndex)
    return false
  }

  private fun reportTypeMismatch(
    expr: PklExpr,
    actualType: Type,
    requiredType: Type,
    base: PklBaseModule,
    holder: AnnotationHolder
  ) {
    when {
      !actualType.hasCommonSubtypeWith(requiredType, base) -> {
        // no subtype of actual type is a subtype of required type ->
        // cannot be caused by the type system being too weak ->
        // runtime type check cannot succeed ->
        // report error
        createMismatchAnnotation(
          HighlightSeverity.ERROR,
          expr.textRange,
          "Type",
          requiredType.render(),
          actualType.render(),
          holder,
          PklProblemGroups.typeMismatch
        )
      }
      actualType.isNullable(base) && actualType.nonNull(base).isSubtypeOf(requiredType, base) -> {
        // actual type is only too weak in that it admits `null` ->
        // could be caused by the type system being too weak ->
        // runtime type check could succeed ->
        // report warning with custom message
        createMismatchAnnotation(
          HighlightSeverity.WARNING,
          expr.textRange,
          "Nullability",
          requiredType.render(),
          actualType.render(),
          holder,
          PklProblemGroups.typeMismatch,
          expr
        )
      }
      else -> {
        // actual type is too weak ->
        // could be caused by the type system being too weak ->
        // runtime type check could succeed ->
        // report warning
        createMismatchAnnotation(
          HighlightSeverity.WARNING,
          expr.textRange,
          "Type",
          requiredType.render(),
          actualType.render(),
          holder,
          PklProblemGroups.typeMismatch,
          expr
        )
      }
    }
  }

  private fun reportConstraintMismatch(
    expr: PklExpr,
    exprValue: Lazy<ConstraintValue>,
    constraints: List<Pair<Type, Int>>,
    holder: AnnotationHolder
  ) {

    val textBuilder = StringBuilder()
    val htmlBuilder = StringBuilder()
    val valueText = exprValue.value.render()

    textBuilder.append("Constraint violation.\nRequired: ")
    htmlBuilder.append("Constraint violation.").append("<table><tr><td>Required:</td><td>")

    var isFirst = true
    for ((type, index) in constraints) {
      if (isFirst) {
        isFirst = false
      } else {
        textBuilder.append(" || ")
        htmlBuilder.append(" || ")
      }
      val constraint = type.constraints[index]
      val constraintText = constraint.render()
      textBuilder.append(constraintText)
      val htmlConstraintText = convertToHtml(expr, constraintText)
      htmlBuilder.append(htmlConstraintText)
    }

    textBuilder.append("\nFound: ").appendLine(valueText)
    val htmlValueText = convertToHtml(expr, valueText)
    htmlBuilder
      .append("</td></tr><tr><td align=\"right\">Found:</td><td>")
      .append(htmlValueText)
      .append("</td></tr></table>")

    createAnnotation(
      HighlightSeverity.ERROR,
      expr.textRange,
      textBuilder.toString(),
      htmlBuilder.toString(),
      holder,
      PklProblemGroups.typeMismatch
    )
  }

  private fun convertToHtml(context: PsiElement, codeFragment: String): String {
    // try to prevent "Control-flow exceptions (like ProcessCanceledException) should never be
    // logged"
    // caused by `HtmlTextPainter.convertCodeFragmentToHTMLFragmentWithInlineStyles` logging all
    // exceptions
    ProgressManager.checkCanceled()

    return HTMLTextPainter.convertCodeFragmentToHTMLFragmentWithInlineStyles(context, codeFragment)
  }

  protected fun buildAnnotation(
    severity: HighlightSeverity,
    range: TextRange,
    message: String,
    tooltip: String,
    holder: AnnotationHolder,
    group: PklProblemGroup? = null,
    suppressedElement: PsiElement? = null
  ): AnnotationBuilder? {
    if (
      severity != HighlightSeverity.ERROR &&
        group != null &&
        suppressedElement != null &&
        isSuppressed(suppressedElement, group)
    )
      return null

    val result =
      holder
        .newAnnotation(severity, message)
        .range(range)
        .tooltip(XmlStringUtil.wrapInHtml(tooltip))
    if (group != null) {
      result.problemGroup(group)
      if (severity != HighlightSeverity.ERROR && suppressedElement != null) {
        for (fix in group.getSuppressQuickFixes(suppressedElement, holder.currentFile)) {
          result.withFix(fix)
        }
      }
    }
    return result
  }

  @Suppress("SameParameterValue")
  protected fun createAnnotation(
    severity: HighlightSeverity,
    range: TextRange,
    message: String,
    tooltip: String,
    holder: AnnotationHolder,
    group: PklProblemGroup? = null,
    suppressedElement: PsiElement? = null
  ) {
    buildAnnotation(severity, range, message, tooltip, holder, group, suppressedElement)?.create()
  }

  @Suppress("SameParameterValue")
  protected fun createMismatchAnnotation(
    severity: HighlightSeverity,
    range: TextRange,
    entity: String,
    required: String,
    found: String,
    holder: AnnotationHolder,
    group: PklProblemGroup? = null,
    suppressedElement: PsiElement? = null
  ) {
    createAnnotation(
      severity,
      range,
      "$entity mismatch. Required: $required Found: $found",
      "$entity mismatch.<table><tr><td>Required:</td><td><code>${required.escapeXml()}</code></td></tr>" +
        "<tr><td align=\"right\">Found:</td><td><code>${found.escapeXml()}</code></td></tr></table>",
      holder,
      group,
      suppressedElement
    )
  }

  protected fun isSuppressed(element: PsiElement, group: ProblemGroup): Boolean {
    val problemName = group.problemName ?: return false

    for (member in element.parentsOfType<PklSuppressWarningsTarget>()) {
      val comment = member.prevSibling?.skipWhitespaceBack()
      if (comment !is PsiComment || comment.tokenType != PklElementTypes.LINE_COMMENT) continue
      val result = suppressRegex.find(comment.text, 2)
      if (
        result != null &&
          SuppressionUtil.isInspectionToolIdMentioned(result.groupValues[1], problemName)
      ) {
        return true
      }
    }
    return false
  }
}
