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

abstract class PklTypeDefBase(node: ASTNode) : PklModuleMemberBase(node), PklTypeDef {
  override val anchor: PsiElement
    get() = keyword

  override val displayName: String
    get() {
      val className = name ?: "<class>"
      val module = enclosingModule
      return if (module != null && !(module.isPklBaseModule)) "${module.displayName}#$className"
      else className
    }

  override fun getNameIdentifier(): PsiElement? = identifier

  override fun isEquivalentTo(other: PsiElement): Boolean {
    if (this === other) return true
    if (this::class != other::class) return false
    other as PklTypeDefBase
    val myName = name ?: return false
    val otherName = other.name ?: return false
    if (myName != otherName) return false
    val myMod = enclosingModule ?: return false
    val otherMod = other.enclosingModule ?: return false
    return areElementsEquivalent(myMod, otherMod)
  }
}
