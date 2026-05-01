/**
 * Copyright © 2024-2026 Apple Inc. and the Pkl project authors. All rights reserved.
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

import com.intellij.icons.AllIcons
import com.intellij.lang.ASTNode
import com.intellij.navigation.ItemPresentation
import javax.swing.Icon
import org.pkl.intellij.util.toDisplayText

abstract class PklObjectEntryBase(node: ASTNode) : PklObjectMemberBase(node), PklObjectEntry {
  override fun getIcon(flags: Int): Icon = AllIcons.Json.Array

  override fun getPresentation(): ItemPresentation =
    object : ItemPresentation {
      override fun getLocationString(): String? = null

      override fun getIcon(unused: Boolean): Icon = AllIcons.Json.Array

      override fun getPresentableText(): String = buildString {
        append(keyExpr?.toDisplayText() ?: "<entry>")
      }
    }
}
