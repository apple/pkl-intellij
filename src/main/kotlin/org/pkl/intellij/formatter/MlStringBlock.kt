/**
 * Copyright © 2024-2025 Apple Inc. and the Pkl project authors. All rights reserved.
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
package org.pkl.intellij.formatter

import com.intellij.formatting.*
import com.intellij.lang.ASTNode
import com.intellij.openapi.util.TextRange
import com.intellij.psi.TokenType
import org.pkl.intellij.psi.*
import org.pkl.intellij.psi.PklElementTypes.*

class MlStringBlock(node: ASTNode, spacingBuilder: SpacingBuilder) :
  PklBlock(node, Indent.getIndent(Indent.Type.NORMAL, false, true), spacingBuilder) {

  override fun buildChildren(): List<Block> {
    val baseIndent = computeBaseIndent() ?: return listOf()

    val result = mutableListOf<Block>()

    myNode.eachChild { childNode ->
      when (childNode.elementType) {
        STRING_CONTENT -> // flatten
        childNode.eachChild { grandChild -> processChildNode(grandChild, baseIndent, result) }
        else -> processChildNode(childNode, baseIndent, result)
      }
    }

    return result
  }

  private fun computeBaseIndent(): Int? {
    val endQuote = myNode.findChildByType(ML_STRING_END) ?: return null
    // use `findPrevByType()` instead of `treePrev`
    // because blank string has empty string content node between whitespace node and end quote
    val wsBeforeEndQuote = endQuote.findPrevByType(TokenType.WHITE_SPACE) ?: return null
    val newlineIndex = wsBeforeEndQuote.chars.lastIndexOf('\n')
    return if (newlineIndex == -1) 0 else wsBeforeEndQuote.textLength - newlineIndex - 1
  }

  private fun processChildNode(node: ASTNode, baseIndent: Int, result: MutableList<Block>) {
    if (node.elementType == TokenType.WHITE_SPACE) {
      val index = node.chars.lastIndexOf('\n')
      if (index != -1) {
        val lineStartIndex = index + 1 + baseIndent
        if (node.textLength > lineStartIndex) {
          val childRange = node.textRange
          val blockRange = TextRange(childRange.startOffset + lineStartIndex, childRange.endOffset)
          result.add(MlStringWsBlock(blockRange))
          return
        }
      }
    }

    if (needsBlock(node)) {
      result.add(PklBlock(node, Indent.getNoneIndent(), spacingBuilder))
    }
  }
}
