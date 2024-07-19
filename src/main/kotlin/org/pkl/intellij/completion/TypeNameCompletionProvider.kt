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
package org.pkl.intellij.completion

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.psi.util.parentOfType
import com.intellij.util.ProcessingContext
import org.pkl.intellij.psi.PklTypeName
import org.pkl.intellij.psi.enclosingModule
import org.pkl.intellij.psi.pklBaseModule
import org.pkl.intellij.resolve.ResolveVisitors
import org.pkl.intellij.resolve.Resolvers
import org.pkl.intellij.resolve.withoutShadowedElements

class TypeNameCompletionProvider : PklCompletionProvider() {
  override fun addCompletions(
    parameters: CompletionParameters,
    context: ProcessingContext,
    result: CompletionResultSet
  ) {

    val position = parameters.position
    val base = parameters.originalFile.project.pklBaseModule
    val typeName = position.parentOfType<PklTypeName>() ?: return
    val visitor = ResolveVisitors.lookupElements(base).withoutShadowedElements()

    val moduleName = typeName.moduleName?.identifier?.text
    if (moduleName != null) {
      Resolvers.resolveQualifiedTypeName(
        position,
        moduleName,
        visitor,
        position.enclosingModule?.pklProject
      )
    } else {
      Resolvers.resolveUnqualifiedTypeName(
        position,
        base,
        mapOf(),
        visitor,
        position.enclosingModule?.pklProject
      )
    }

    result.addAllElements(visitor.result)

    result.addAllElements(TYPE_KEYWORD_LOOKUP_ELEMENTS)
  }
}
