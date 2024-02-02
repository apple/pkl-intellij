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
package org.pkl.intellij

import com.intellij.lang.refactoring.NamesValidator
import com.intellij.openapi.project.Project

class PklNamesValidator : NamesValidator {
  override fun isKeyword(name: String, project: Project?): Boolean =
    PklSyntaxHighlighter.KEYWORD_NAMES.contains(name)

  override fun isIdentifier(name: String, project: Project?): Boolean {
    if (name.isEmpty()) return false

    if (name.startsWith('`') && name.endsWith('`')) return true

    if (PklSyntaxHighlighter.KEYWORD_NAMES.contains(name)) return false

    val firstCp: Int = name.codePointAt(0)
    return (firstCp == '$'.code ||
      firstCp == '_'.code ||
      Character.isUnicodeIdentifierStart(firstCp)) &&
      name.codePoints().skip(1).allMatch { it == '$'.code || Character.isUnicodeIdentifierPart(it) }
  }
}
