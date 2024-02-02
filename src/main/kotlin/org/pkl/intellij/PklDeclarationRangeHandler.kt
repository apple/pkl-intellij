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

import com.intellij.codeInsight.hint.DeclarationRangeHandler
import com.intellij.openapi.util.TextRange
import org.pkl.intellij.psi.*

// https://www.jetbrains.org/intellij/sdk/docs/reference_guide/custom_language_support/additional_minor_features.html
class PklDeclarationRangeHandler : DeclarationRangeHandler<PklElement> {
  override fun getDeclarationRange(container: PklElement): TextRange {
    return when (container) {
      is PklModule -> {
        val declaration = container.declaration ?: return TextRange.EMPTY_RANGE
        val start =
          declaration.modifierList
            ?: declaration.firstChildOfType(PklElementTypes.MODULE)
              ?: declaration.qualifiedIdentifier ?: declaration.extendsAmendsClause
              ?: return TextRange.EMPTY_RANGE
        val end =
          declaration.extendsAmendsClause
            ?: declaration.qualifiedIdentifier
              ?: declaration.firstChildOfType(PklElementTypes.MODULE) ?: declaration.modifierList
              ?: return TextRange.EMPTY_RANGE
        TextRange(start.textOffset, end.textRange.endOffset)
      }
      is PklClass -> {
        val start = container.modifierList
        val end =
          container.extendsClause
            ?: container.typeParameterList ?: container.identifier
              ?: container.firstChildOfType(PklElementTypes.CLASS_KEYWORD)!!
        TextRange(start.textOffset, end.textRange.endOffset)
      }
      is PklTypeAlias -> {
        val start = container.modifierList
        val end =
          container.typeParameterList
            ?: container.identifier ?: container.firstChildOfType(PklElementTypes.CLASS_KEYWORD)!!
        TextRange(start.textOffset, end.textRange.endOffset)
      }
      is PklMethod -> {
        val start = container.modifierList
        val end =
          container.returnType
            ?: container.parameterList ?: container.typeParameterList ?: container.identifier
              ?: container.firstChildOfType(PklElementTypes.FUNCTION)!!
        TextRange(start.textOffset, end.textRange.endOffset)
      }
      is PklProperty -> {
        val start = container.modifierList
        val end = container.type ?: container.propertyName
        TextRange(start.textOffset, end.textRange.endOffset)
      }
      else -> TextRange.EMPTY_RANGE
    }
  }
}
