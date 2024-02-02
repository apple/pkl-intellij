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

import com.intellij.util.containers.nullize

interface PklObjectBodyEx : PklParameterListOwner {
  val members: Sequence<PklObjectMember>

  val properties: Sequence<PklObjectProperty>

  val methods: Sequence<PklObjectMethod>

  /** Returns `"one"` given an object body containing `x = "one"`. */
  fun getConstantStringProperty(propertyName: String): String? {
    val property = properties.find { it.propertyName.textMatches(propertyName) } ?: return null
    return property.expr.stringContent
  }

  /**
   * Returns `List("one", "two")` given an object body containing either of:
   * - `x { "one"; "two" }`
   * - `x = new { "one"; "two" }`
   * - `x = y { "one"; "two" }`. Ignores non-element members and elements that aren't string
   *   literals.
   */
  fun getConstantStringElementsProperty(propertyName: String): List<String> {
    val property = properties.find { it.propertyName.textMatches(propertyName) } ?: return listOf()
    property.objectBodyList
      .nullize()
      ?.flatMap { it.stringElements }
      ?.let {
        return it
      }
    property.expr?.let { expr ->
      return (expr as? PklObjectBodyOwner)?.objectBody?.stringElements ?: listOf()
    }
    return listOf()
  }

  private val PklObjectBody.stringElements: List<String>
    get() = members.map { (it as? PklObjectElement)?.expr.stringContent }.filterNotNull().toList()

  private val PklExpr?.stringContent: String?
    get() = (this as? PklStringLiteral)?.content?.escapedText()
}
