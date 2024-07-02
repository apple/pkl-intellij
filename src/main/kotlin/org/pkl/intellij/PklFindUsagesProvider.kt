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

import com.intellij.lang.cacheBuilder.DefaultWordsScanner
import com.intellij.lang.cacheBuilder.WordsScanner
import com.intellij.lang.findUsages.FindUsagesProvider
import com.intellij.lexer.FlexAdapter
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.parentOfTypes
import org.pkl.intellij.lexer._PklLexer
import org.pkl.intellij.psi.*
import org.pkl.intellij.util.unexpectedType

// https://www.jetbrains.org/intellij/sdk/docs/reference_guide/custom_language_support/find_usages.html
class PklFindUsagesProvider : FindUsagesProvider {
  override fun getWordsScanner(): WordsScanner =
    DefaultWordsScanner(
      FlexAdapter(_PklLexer()),
      TokenSet.create(PklElementTypes.IDENTIFIER),
      TokenSet.create(
        PklElementTypes.LINE_COMMENT,
        PklElementTypes.BLOCK_COMMENT,
        PklElementTypes.DOC_COMMENT_LINE
      ),
      TokenSet.create(
        PklElementTypes.STRING_CHARS,
        PklElementTypes.NUMBER,
        PklElementTypes.TRUE,
        PklElementTypes.FALSE,
        PklElementTypes.NULL
      )
    )

  override fun canFindUsagesFor(element: PsiElement): Boolean =
    when (element) {
      is PklModule -> true
      is PklClass -> true
      is PklTypeAlias -> true
      is PklMethod -> true
      is PklProperty -> element.isDefinition(element.enclosingModule?.pklProject)
      is PklTypedIdentifier -> true
      is PklTypeParameter -> true
      else -> false
    }

  override fun getHelpId(psiElement: PsiElement): String? = null

  override fun getType(element: PsiElement): String =
    when (element) {
      is PklModule -> "module"
      is PklClass -> "class"
      is PklTypeAlias -> "type alias"
      is PklMethod -> "method"
      is PklProperty -> "property"
      is PklTypedIdentifier ->
        when (val parent = element.parent) {
          is PklLetExpr,
          is PklForGenerator -> "variable"
          is PklParameterList -> "parameter"
          else -> unexpectedType(parent)
        }
      is PklTypeParameter -> "type parameter"
      // surprisingly called for PsiElement's excluded by `canFindUsagesFor`
      // (and must not return null)
      else -> ""
    }

  override fun getDescriptiveName(element: PsiElement): String =
    when (element) {
      is PklModule -> element.name
      is PklClass -> {
        val moduleName = element.enclosingModule?.displayName ?: "<module>"
        val className = element.name ?: "<class>"
        "$moduleName#$className"
      }
      is PklTypeAlias -> {
        val moduleName = element.enclosingModule?.displayName ?: "<module>"
        val aliasName = element.name ?: "<alias>"
        "$moduleName#$aliasName"
      }
      is PklMethod -> {
        val parent = element.parentOfTypes(PklModule::class, PklClass::class)
        val parentName = parent?.let { getDescriptiveName(it) } ?: "<parent>"
        val methodName = element.name ?: "<method>"
        val parameters = element.parameterList
        val returnType = element.returnType

        buildString {
          append(parentName)
          append('.')
          append(methodName)
          renderParameterTypeList(parameters, mapOf())
          renderTypeAnnotation(returnType, mapOf())
        }
      }
      is PklProperty -> {
        val parent = element.parentOfTypes(PklModule::class, PklClass::class, PklTypeAlias::class)
        val parentName = parent?.let { getDescriptiveName(it) } ?: "<parent>"
        val propertyName = element.name
        "$parentName.$propertyName"
      }
      is PklTypedIdentifier -> element.name!!
      is PklTypeParameter -> element.name!!
      else -> unexpectedType(element)
    }

  override fun getNodeText(element: PsiElement, useFullName: Boolean): String =
    if (useFullName) {
      getDescriptiveName(element)
    } else {
      when (element) {
        is PklModule -> element.name
        is PklClass -> element.name ?: "<class>"
        is PklTypeAlias -> element.name ?: "<class>"
        is PklMethod -> (element.name ?: "<method>") + "()"
        is PklProperty -> element.name
        is PklTypedIdentifier -> element.name!!
        is PklTypeParameter -> element.name!!
        else -> unexpectedType(element)
      }
    }
}
