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

abstract class PklAnnotationListBase(node: ASTNode) :
  PklAstWrapperPsiElement(node), PklAnnotationList {
  override val elements: Sequence<PklAnnotation>
    get() = childrenOfClass()

  override val deprecated: Deprecated?
    get() {
      val base = project.pklBaseModule
      val annotation =
        elements.find { it.typeName?.resolve() === base.deprecatedType.psi } ?: return null
      val body = annotation.objectBody
      val since = body?.getConstantStringProperty("since")
      val message = body?.getConstantStringProperty("message")
      val replaceWith = body?.getConstantStringProperty("replaceWith")
      return Deprecated(parent, since, message, replaceWith)
    }

  override val sourceCode: SourceCode?
    get() {
      val annotation =
        elements.find {
          val resolved = it.typeName?.resolve()
          resolved != null && resolved === project.pklBaseModule.sourceCodeType?.psi
        }
          ?: return null
      val body = annotation.objectBody ?: return null
      return SourceCode(
        body.getConstantStringProperty("language") ?: return null,
        body.getConstantStringProperty("prefix"),
        body.getConstantStringProperty("suffix")
      )
    }
}
