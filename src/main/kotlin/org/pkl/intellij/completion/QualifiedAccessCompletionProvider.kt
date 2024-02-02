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
import org.pkl.intellij.psi.PklModule
import org.pkl.intellij.psi.PklQualifiedAccessExpr
import org.pkl.intellij.psi.pklBaseModule
import org.pkl.intellij.resolve.ResolveVisitors
import org.pkl.intellij.resolve.Resolvers
import org.pkl.intellij.type.computeExprType

class QualifiedAccessCompletionProvider : PklCompletionProvider() {
  override fun addCompletions(
    parameters: CompletionParameters,
    context: ProcessingContext,
    result: CompletionResultSet
  ) {

    val position = parameters.position
    val module = parameters.originalFile as? PklModule ?: return
    val base = module.project.pklBaseModule
    val accessExpr = position.parentOfType<PklQualifiedAccessExpr>() ?: return
    val receiverType = accessExpr.receiverExpr.computeExprType(base, mapOf())

    val visitor = ResolveVisitors.lookupElements(base)
    Resolvers.resolveQualifiedAccess(receiverType, true, base, visitor)
    Resolvers.resolveQualifiedAccess(receiverType, false, base, visitor)
    result.addAllElements(visitor.result)
  }
}
