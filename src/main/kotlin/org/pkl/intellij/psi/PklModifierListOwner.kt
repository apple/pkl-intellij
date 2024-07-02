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

import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet

interface PklModifierListOwner : PklElement {
  companion object {
    private val constOrFixed = TokenSet.create(PklElementTypes.CONST, PklElementTypes.FIXED)

    private val localOrConstOrFixed =
      TokenSet.create(PklElementTypes.LOCAL, PklElementTypes.CONST, PklElementTypes.FIXED)

    private val abstractOrOpen = TokenSet.create(PklElementTypes.ABSTRACT, PklElementTypes.OPEN)
  }

  val modifierList: PklModifierList?

  val isAbstract: Boolean
    get() = hasModifier(PklElementTypes.ABSTRACT)

  val isExternal: Boolean
    get() = hasModifier(PklElementTypes.EXTERNAL)

  val isHidden: Boolean
    get() = hasModifier(PklElementTypes.HIDDEN)

  val isLocal: Boolean
    get() = hasModifier(PklElementTypes.LOCAL)

  val isOpen: Boolean
    get() = hasModifier(PklElementTypes.OPEN)

  val isFixed: Boolean
    get() = hasModifier(PklElementTypes.FIXED)

  val isConst: Boolean
    get() = hasModifier(PklElementTypes.CONST)

  val isFixedOrConst: Boolean
    get() = hasEitherModifier(constOrFixed)

  val isAbstractOrOpen: Boolean
    get() = hasEitherModifier(abstractOrOpen)

  val isLocalOrConstOrFixed: Boolean
    get() = hasEitherModifier(localOrConstOrFixed)

  private fun hasModifier(modifier: IElementType): Boolean {
    val modifiers = modifierList?.elements ?: return false
    return modifiers.any { it.elementType == modifier }
  }

  private fun hasEitherModifier(tokenSet: TokenSet): Boolean {
    val modifiers = modifierList?.elements ?: return false
    return modifiers.any { tokenSet.contains(it.elementType) }
  }
}
