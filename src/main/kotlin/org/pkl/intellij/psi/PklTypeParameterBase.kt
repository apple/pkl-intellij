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
package org.pkl.intellij.psi

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import org.pkl.intellij.type.Type
import org.pkl.intellij.type.TypeParameterBindings

abstract class PklTypeParameterBase(node: ASTNode) :
  PklAstWrapperPsiElement(node), PklTypeParameter {
  override fun getName(): String = identifier.text

  override fun getNameIdentifier(): PsiElement = identifier

  override fun getTextOffset(): Int = nameIdentifier.textOffset

  // TODO: consider making return type nullable instead
  override fun getLookupElementType(base: PklBaseModule, bindings: TypeParameterBindings): Type =
    Type.Unknown

  override fun toString(): String = super.toString() + "($name)"
}
