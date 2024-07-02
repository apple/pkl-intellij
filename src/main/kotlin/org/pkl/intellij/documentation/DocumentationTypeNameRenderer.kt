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
package org.pkl.intellij.documentation

import org.pkl.intellij.psi.*
import org.pkl.intellij.type.Type
import org.pkl.intellij.util.escapeXml
import org.pkl.intellij.util.notEscapeXml

object DocumentationTypeNameRenderer : TypeNameRenderer {
  override fun render(name: PklTypeName, appendable: Appendable) {
    appendable.renderTypeName(name.moduleName?.resolve(null), name.simpleName.text)
  }

  override fun render(type: Type.Class, appendable: Appendable) {
    appendable.renderTypeName(type.psi.enclosingModule, type.psi.name)
  }

  override fun render(type: Type.Alias, appendable: Appendable) {
    appendable.renderTypeName(type.psi.enclosingModule, type.psi.name)
  }

  override fun render(type: Type.Module, appendable: Appendable) {
    appendable.renderModuleName(type.psi, type.psi.shortDisplayName)
  }

  fun Appendable.renderTypeName(
    module: PklModule?,
    simpleName: String?,
    displayName: String? = simpleName
  ) {
    notEscapeXml {
      append("<a href='psi_element://")

      if (module != null) {
        // preferably use VFS URL because it is much easier to resolve than declared module name
        append(module.virtualFile?.url ?: module.declaredName?.text ?: "")
        append("#")
      }

      append(simpleName ?: "<type>")
      append("'>")

      escapeXml { append(displayName ?: "<type>") }

      append("</a>")
    }
  }

  fun Appendable.renderModuleName(module: PklModule, displayName: String) {
    notEscapeXml {
      append("<a href='psi_element://")

      // preferably use VFS URL because it is much easier to resolve than declared module name
      append(module.virtualFile?.url ?: module.declaredName?.text ?: "")
      append("#")

      append("'>")

      escapeXml { append(displayName) }

      append("</a>")
    }
  }
}
