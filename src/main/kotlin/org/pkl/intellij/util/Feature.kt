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
package org.pkl.intellij.util

import com.intellij.psi.PsiElement
import org.pkl.intellij.PklVersion
import org.pkl.intellij.psi.*

sealed interface Feature {
  val featureName: String
  val requiredVersion: PklVersion
  val predicate: (PsiElement) -> Boolean
  val message: String? get() = null

  fun isSupported(module: PklModule): Boolean = module.effectivePklVersion >= requiredVersion

  companion object {
    val features = listOf<Feature>(ConstObjectMember)

    object ConstObjectMember : Feature {
      override val featureName: String = "const object member"
      override val requiredVersion: PklVersion = PklVersion.VERSION_0_27
      override val predicate: (PsiElement) -> Boolean = { elem ->
        elem.elementType == PklElementTypes.CONST && elem.parent.parent is PklObjectMember
      }
      override val message: String = "Modifier 'const' cannot be applied to object members in this Pkl version."
    }
  }
}
