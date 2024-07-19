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
import com.intellij.navigation.ItemPresentation
import com.intellij.psi.PsiElement
import java.lang.AssertionError
import javax.swing.Icon
import org.pkl.intellij.packages.dto.PklProject
import org.pkl.intellij.type.Type
import org.pkl.intellij.type.TypeParameterBindings

abstract class PklModuleMemberBase(node: ASTNode) : PklAstWrapperPsiElement(node), PklModuleMember {
  override fun getName(): String? = nameIdentifier?.text

  override val docComment: PklDocComment?
    get() =
      throw AssertionError(
        "This method should have been overridden by ${this::class.qualifiedName}."
      )

  override val annotationList: PklAnnotationList
    get() =
      throw AssertionError(
        "This method should have been overridden by ${this::class.qualifiedName}."
      )

  override val modifierList: PklModifierList
    get() =
      throw AssertionError(
        "This method should have been overridden by ${this::class.qualifiedName}."
      )

  override val anchor: PsiElement
    get() =
      throw AssertionError(
        "This method should have been overridden by ${this::class.qualifiedName}."
      )

  override fun getNameIdentifier(): PsiElement? {
    throw AssertionError("This method should have been overridden by ${this::class.qualifiedName}.")
  }

  override fun getTextOffset(): Int = nameIdentifier?.textOffset ?: super.getTextOffset()

  override fun getLookupElementType(
    base: PklBaseModule,
    bindings: TypeParameterBindings,
    context: PklProject?
  ): Type {
    throw AssertionError("This method should have been overridden by ${this::class.qualifiedName}.")
  }

  override fun getPresentation(): ItemPresentation =
    object : ItemPresentation {
      override fun getLocationString(): String? = enclosingModule?.displayName

      override fun getIcon(unused: Boolean): Icon? = getIcon(0)

      override fun getPresentableText(): String? = name
    }

  override fun toString(): String = super.toString() + "($name)"
}
