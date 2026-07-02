/**
 * Copyright © 2026 Apple Inc. and the Pkl project authors. All rights reserved.
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
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.psi.util.parentOfType
import com.intellij.util.ProcessingContext
import org.pkl.intellij.documentation.PkldocLinkGeneratingProvider
import org.pkl.intellij.psi.PklDocComment
import org.pkl.intellij.psi.PklDocCommentReference
import org.pkl.intellij.psi.enclosingModule
import org.pkl.intellij.psi.pklBaseModule
import org.pkl.intellij.resolve.ResolveVisitors
import org.pkl.intellij.resolve.Resolvers
import org.pkl.intellij.resolve.withoutShadowedElements

class PklDocCommentMemberLinkCompletionProvider : PklCompletionProvider() {
  private val memberLinkKeywords =
    PkldocLinkGeneratingProvider.keywords.map { LookupElementBuilder.create(it).bold() }

  override fun addCompletions(
    parameters: CompletionParameters,
    context: ProcessingContext,
    result: CompletionResultSet
  ) {
    val docComment = parameters.position.parentOfType<PklDocComment>() ?: return
    val reference =
      docComment.references.find { it.absoluteRange.contains(parameters.offset) } ?: return
    if (reference.fullText.contains(".")) {
      completeQualifiedMemberLink(reference, docComment, result)
    } else {
      result.addAllElements(memberLinkKeywords)
      completeUnqualifiedMemberLink(docComment, result)
    }
  }

  private fun completeUnqualifiedMemberLink(
    docComment: PklDocComment,
    result: CompletionResultSet
  ) {
    val base = docComment.project.pklBaseModule
    val context = docComment.enclosingModule?.pklProject
    val visitor = ResolveVisitors.lookupElements(base).withoutShadowedElements()
    Resolvers.resolveUnqualifiedAccess(
      docComment,
      null,
      isProperty = true,
      base,
      mapOf(),
      visitor,
      context
    )
    Resolvers.resolveUnqualifiedAccess(
      docComment,
      null,
      isProperty = false,
      base,
      mapOf(),
      visitor,
      context
    )
    Resolvers.resolveUnqualifiedTypeName(docComment, base, mapOf(), visitor, context)
    result.addAllElements(visitor.result)
  }

  private fun completeQualifiedMemberLink(
    reference: PklDocCommentReference,
    docComment: PklDocComment,
    result: CompletionResultSet
  ) {
    val project = docComment.project
    val context = docComment.enclosingModule?.pklProject
    val visitor = ResolveVisitors.lookupElements(project.pklBaseModule).withoutShadowedElements()
    Resolvers.resolveQualifiedDocCommentMemberLink(
      reference.fullText,
      docComment,
      true,
      project.pklBaseModule,
      visitor,
      context
    )
    Resolvers.resolveQualifiedDocCommentMemberLink(
      reference.fullText,
      docComment,
      false,
      project.pklBaseModule,
      visitor,
      context
    )
    result.addAllElements(visitor.result)
  }
}
