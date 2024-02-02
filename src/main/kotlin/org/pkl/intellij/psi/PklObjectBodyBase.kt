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
import com.intellij.openapi.util.Key
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.parentOfTypes

abstract class PklObjectBodyBase(node: ASTNode) : PklAstWrapperPsiElement(node), PklObjectBody {
  override val members: Sequence<PklObjectMember>
    get() = childrenOfClass()

  override val properties: Sequence<PklObjectProperty>
    get() = childrenOfClass()

  override val methods: Sequence<PklObjectMethod>
    get() = childrenOfClass()

  companion object {
    private val constScopeKey: Key<CachedValue<Boolean>> = Key.create("isConstScope")
  }

  fun isConstScope(): Boolean {
    return CachedValuesManager.getManager(project)
      .getCachedValue(
        this,
        constScopeKey,
        {
          val parentProperty = parentOfTypes(PklProperty::class, PklMethod::class)
          val isConstScope =
            if (parentProperty?.isConst == true) {
              true
            } else {
              val parentObj = parentOfTypes(PklObjectBodyBase::class)
              parentObj?.isConstScope() ?: false
            }
          CachedValueProvider.Result.createSingleDependency(isConstScope, this)
        },
        false
      )
  }
}
