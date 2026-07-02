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

import com.intellij.lang.ASTNode
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.parser.MarkdownParser
import org.pkl.intellij.documentation.PkldocFlavorDescriptor

abstract class PklDocCommentBase(node: ASTNode) :
  PklAstWrapperPsiElement(node), PklDocComment, PklDocCommentEx {
  override fun getTokenType(): IElementType = PklElementTypes.DOC_COMMENT_LINE

  override fun getOwner(): PsiElement? = parent

  override val contents: String
    get() =
      docCommentLines.joinToString("\n") {
        it.text.substring(if (it.text.length > 3 && it.text[3].isWhitespace()) 4 else 3)
      }

  private val docCommentLines: Sequence<PsiElement>
    get() = childSeq.filter { it.elementType == PklElementTypes.DOC_COMMENT_LINE }

  override fun getReferences(): Array<PklDocCommentReference> {
    return CachedValuesManager.getManager(project).getCachedValue(this) {
      val markdownText = contents
      val tree = MarkdownParser(PkldocFlavorDescriptor).buildMarkdownTreeFromString(markdownText)
      val memberLinkReferences = gatherMemberLinks(contents, tree).toTypedArray()
      CachedValueProvider.Result.create(memberLinkReferences, this)
    }
  }

  private fun gatherMemberLinks(
    contents: String,
    node: org.intellij.markdown.ast.ASTNode
  ): List<PklDocCommentReference> {
    return if (
      node.type == MarkdownElementTypes.SHORT_REFERENCE_LINK ||
        node.type == MarkdownElementTypes.FULL_REFERENCE_LINK
    ) {
      val labelNode =
        node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_LABEL }
          ?: return emptyList()
      val fullTextRange = getTextRange(labelNode)
      val labelText = labelNode.getTextInNode(contents).drop(1).dropLast(1)
      val self = this
      buildList {
        val segments = labelText.split('.')
        var offset = fullTextRange.startOffset
        for (segment in segments) {
          val myTextRange = TextRange(offset, offset + segment.length)
          add(PklDocCommentReference(self, myTextRange, fullTextRange))
          offset += segment.length + 1
        }
      }
    } else node.children.flatMap { gatherMemberLinks(contents, it) }
  }

  // `node`'s offsets are relative to `element.contents`, a reconstructed string with the "///"
  // prefix stripped from every line. Walk the same doc comment lines to translate those offsets
  // into offsets within `element.text` itself.
  private fun getTextRange(node: org.intellij.markdown.ast.ASTNode): TextRange {
    var offset = 0
    var startOffset = -1
    var endOffset = -1
    for (line in docCommentLines) {
      val text = line.text
      val prefixLength = if (text.length > 3 && text[3].isWhitespace()) 4 else 3
      val contentLength = text.length - prefixLength
      val lineContentStart = line.startOffsetInParent + prefixLength
      if (startOffset == -1 && node.startOffset <= offset + contentLength) {
        startOffset = lineContentStart + (node.startOffset - offset)
      }
      if (endOffset == -1 && node.endOffset <= offset + contentLength) {
        endOffset = lineContentStart + (node.endOffset - offset)
      }
      if (startOffset != -1 && endOffset != -1) break
      offset += contentLength + 1 // `+1` for the "\n" joining consecutive lines
    }
    return TextRange(startOffset + 1, endOffset - 1)
  }
}
