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

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.lang.parameterInfo.*
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.parentOfType
import org.pkl.intellij.psi.*
import org.pkl.intellij.resolve.ResolveVisitors

class PklParameterInfoHandler :
  ParameterInfoHandlerWithTabActionSupport<PklArgumentList, PklParameterList, PklExpr> {
  companion object {
    val stopSearchClasses =
      setOf(PklMethod::class.java, PklProperty::class.java, PklObjectBody::class.java)

    val stopSearchClassesArray = stopSearchClasses.toTypedArray()

    val allowedParentClasses = setOf(PklAccessExpr::class.java)
  }

  init {
    // TODO HACK
    CodeInsightSettings.getInstance().SHOW_PARAMETER_NAME_HINTS_ON_COMPLETION = false
  }

  override fun getActualParameterDelimiterType(): IElementType = PklElementTypes.COMMA

  override fun getActualParametersRBraceType(): IElementType = PklElementTypes.RPAREN

  override fun getArgListStopSearchClasses(): Set<Class<*>> = stopSearchClasses

  override fun getActualParameters(argumentList: PklArgumentList): Array<PklExpr> =
    argumentList.elements.toTypedArray()

  override fun getArgumentListClass(): Class<PklArgumentList> = PklArgumentList::class.java

  override fun getArgumentListAllowedParentClasses(): Set<Class<*>> = allowedParentClasses

  override fun showParameterInfo(
    argumentList: PklArgumentList,
    context: CreateParameterInfoContext
  ) {
    context.showHint(argumentList, argumentList.textRange.startOffset, this)
  }

  override fun updateParameterInfo(
    argumentList: PklArgumentList,
    context: UpdateParameterInfoContext
  ) {
    if (context.parameterOwner == null || argumentList == context.parameterOwner) {
      context.parameterOwner = argumentList
    } else {
      context.removeHint()
    }
  }

  override fun updateUI(parameterList: PklParameterList, context: ParameterInfoUIContext) {
    val builder = StringBuilder()
    val currentParameterIndex = context.currentParameterIndex
    var startOffset = -1
    var endOffset = -1
    var first = true

    val parameters = parameterList.elements
    for ((index, parameter) in parameters.withIndex()) {
      if (first) first = false else builder.append(", ")
      if (index == currentParameterIndex) startOffset = builder.length
      builder.append(parameter.identifier.text)
      val type = parameter.type?.unknownToNull()
      if (type != null) {
        // TODO: get bindings from context
        builder.append(": ").renderType(type, mapOf())
      }
      if (index == currentParameterIndex) endOffset = builder.length
    }

    val isGrey = !context.isUIComponentEnabled
    val isDeprecated = false // TODO

    context.setupUIComponentPresentation(
      builder.toString(),
      startOffset,
      endOffset,
      isGrey,
      isDeprecated,
      false,
      context.defaultParameterColor
    )
  }

  override fun findElementForUpdatingParameterInfo(
    context: UpdateParameterInfoContext
  ): PklArgumentList? {
    val argumentList = findArgumentListAtCaret(context) ?: return null
    val argumentIndex =
      ParameterInfoUtils.getCurrentParameterIndex(
        argumentList.node,
        context.offset,
        PklElementTypes.COMMA
      )
    context.setCurrentParameter(argumentIndex)
    return argumentList
  }

  override fun findElementForParameterInfo(context: CreateParameterInfoContext): PklArgumentList? {
    val argumentList = findArgumentListAtCaret(context) ?: return null
    val accessExpr = argumentList.parentOfType<PklAccessExpr>() ?: return null
    val base = context.project.pklBaseModule

    val visitor = ResolveVisitors.elementsNamed(accessExpr.memberNameText, base)
    val methods = accessExpr.resolve(base, null, mapOf(), visitor)
    val parameterLists =
      methods.mapNotNull { method ->
        when (method) {
          is PklMethod -> method.parameterList
          else -> null
        }
      }
    context.itemsToShow = parameterLists.toTypedArray()
    return argumentList
  }

  private fun findArgumentListAtCaret(context: ParameterInfoContext): PklArgumentList? {
    val element = context.file.findElementAt(context.offset) ?: return null
    return PsiTreeUtil.getParentOfType(
      element,
      PklArgumentList::class.java,
      true,
      *stopSearchClassesArray
    )
  }
}
