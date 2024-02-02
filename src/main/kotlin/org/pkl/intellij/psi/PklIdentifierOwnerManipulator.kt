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

import com.intellij.openapi.util.TextRange
import com.intellij.psi.AbstractElementManipulator

class PklIdentifierOwnerManipulator : AbstractElementManipulator<PklIdentifierOwner>() {
  override fun handleContentChange(
    element: PklIdentifierOwner,
    range: TextRange,
    newContent: String
  ): PklIdentifierOwner {

    val oldIdentifier = element.identifier
    val oldText = oldIdentifier.text

    val newText = oldText.replaceRange(range.startOffset, range.endOffset, newContent)
    val newIdentifier = PklPsiFactory.createIdentifier(newText, element.project)

    oldIdentifier.node.treeParent.replaceChild(oldIdentifier.node, newIdentifier.node)

    return element
  }
}
