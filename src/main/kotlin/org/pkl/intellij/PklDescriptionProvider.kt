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

import com.intellij.psi.ElementDescriptionLocation
import com.intellij.psi.ElementDescriptionProvider
import com.intellij.psi.PsiElement
import com.intellij.usageView.UsageViewTypeLocation
import org.pkl.intellij.psi.*
import org.pkl.intellij.util.unexpectedType

class PklDescriptionProvider : ElementDescriptionProvider {
  override fun getElementDescription(
    element: PsiElement,
    location: ElementDescriptionLocation
  ): String? {
    if (element !is PklElement) return null

    return when (location) {
      is UsageViewTypeLocation ->
        when (element) {
          is PklModule -> "module"
          is PklClass -> "class"
          is PklTypeAlias -> "type alias"
          is PklProperty -> "property"
          is PklMethod -> "method"
          is PklTypedIdentifier ->
            when (val parent = element.parent) {
              is PklLetExpr,
              is PklForGenerator -> "variable"
              is PklParameterList -> "parameter"
              else -> unexpectedType(parent)
            }
          is PklTypeParameter -> "type parameter"
          else -> null
        }
      else -> null
    }
  }
}
