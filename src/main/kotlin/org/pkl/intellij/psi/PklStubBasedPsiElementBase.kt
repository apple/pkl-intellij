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

import com.intellij.extapi.psi.StubBasedPsiElementBase
import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.stubs.IStubElementType
import com.intellij.psi.stubs.StubElement

abstract class PklStubBasedPsiElementBase<StubT : StubElement<*>> :
  StubBasedPsiElementBase<StubT>, PklElement {
  constructor(node: ASTNode) : super(node)

  constructor(stub: StubT, nodeType: IStubElementType<*, *>) : super(stub, nodeType)

  // improve readability in PSI viewer
  override fun toString(): String {
    return javaClass.name.removePrefix("org.pkl.intellij.psi.impl.Pkl").removeSuffix("Impl")
  }

  override fun acceptChildren(visitor: PsiElementVisitor) {
    var child = firstChild
    while (child != null) {
      // get next sibling before calling `accept()` so that visitor can `replace()` child without
      // affecting traversal
      val nextSibling = child.nextSibling
      child.accept(visitor)
      child = nextSibling
    }
  }
}
