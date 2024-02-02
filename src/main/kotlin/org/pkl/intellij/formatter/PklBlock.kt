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
package org.pkl.intellij.formatter

import com.intellij.formatting.*
import com.intellij.lang.ASTNode
import com.intellij.psi.TokenType
import com.intellij.psi.formatter.common.AbstractBlock
import com.intellij.psi.impl.source.tree.LeafElement
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import org.pkl.intellij.PklParserDefinition
import org.pkl.intellij.formatter.PklFormattingModelBuilder.Companion.BINARY_OPERATORS
import org.pkl.intellij.psi.*
import org.pkl.intellij.psi.PklElementTypes.*

open class PklBlock(
  node: ASTNode,
  private val indent: Indent,
  protected val spacingBuilder: SpacingBuilder
) : AbstractBlock(node, null, null) {

  companion object {
    private val COMMENTS = TokenSet.create(LINE_COMMENT, BLOCK_COMMENT, DOC_COMMENT)
    private val BRACES = TokenSet.create(LBRACE, RBRACE)
    private val PARENS = TokenSet.create(LPAREN, RPAREN)
    private val BODIES = TokenSet.create(CLASS_BODY, OBJECT_BODY)
    private val PARENTHESIZED_LISTS =
      TokenSet.create(
        ARGUMENT_LIST,
        FUNCTION_PARAMETER_LIST,
        TYPE_CONSTRAINT_LIST,
        FUNCTION_TYPE_PARAMETER_LIST
      )
    private val INDENT_CHILDREN_OF =
      TokenSet.orSet(
        PARENTHESIZED_LISTS,
        BODIES,
        TokenSet.create(IF_EXPR, LET_EXPR, FOR_GENERATOR, WHEN_GENERATOR)
      )
    private val EXPR_BODY_CONTAINERS =
      TokenSet.create(
        TYPE_ALIAS,
        CLASS_METHOD,
        CLASS_PROPERTY,
        OBJECT_METHOD,
        OBJECT_PROPERTY,
        FUNCTION_LITERAL
      )
    private val LET_FOR_WHEN = TokenSet.create(LET_EXPR, FOR_GENERATOR, WHEN_GENERATOR)
  }

  override fun getSpacing(child1: Block?, child2: Block): Spacing? =
    spacingBuilder.getSpacing(this, child1, child2)

  override fun getIndent(): Indent = indent

  override fun isLeaf(): Boolean = myNode.elementType is TokenType

  override fun buildChildren(): List<Block> {
    if (myNode is LeafElement) return listOf()

    val parentType = myNode.elementType
    var prevChildNode: ASTNode? = null
    val result = mutableListOf<Block>()

    myNode.eachChild { childNode ->
      if (!needsBlock(childNode)) return@eachChild

      val childBlock =
        when (childNode.elementType) {
          ML_STRING_LITERAL -> MlStringBlock(childNode, spacingBuilder)
          else ->
            PklBlock(
              childNode,
              calculateBlockIndent(parentType, childNode, prevChildNode),
              spacingBuilder
            )
        }
      result.add(childBlock)

      // make prevChildNode point to previous non-comment child node
      if (childNode.elementType !in COMMENTS) {
        prevChildNode = childNode
      }
    }

    return result
  }

  // determines indentation when enter is pressed
  // (but not when code is reformatted)
  override fun getChildIndent(): Indent {
    return when (myNode.elementType) {
      PklParserDefinition.FILE -> Indent.getNoneIndent()
      in EXPR_BODY_CONTAINERS ->
        // Type aliases, methods, and properties don't have a dedicated body PSI like classes and
        // objects do.
        // This is inspired by other language PSIs such as Kotlin's but complicates matters here.
        // If, say, a class method always return "normal indent", pressing return at the end of a
        // doc comment line will indent.
        // And if it always returns "no indent", pressing return after "function foo() =" won't
        // indent.
        // While deciding based on [isComplete] seems to work well enough in practice, it isn't
        // fully correct:
        // Pressing return at the end of a doc comment line with *incomplete* method will still
        // indent.
        if (isIncomplete) Indent.getNormalIndent() else Indent.getNoneIndent()
      in INDENT_CHILDREN_OF -> Indent.getNormalIndent()
      else -> Indent.getNoneIndent()
    }
  }

  protected fun needsBlock(node: ASTNode): Boolean {
    return when (node.elementType) {
      TokenType.ERROR_ELEMENT -> true
      TokenType.WHITE_SPACE -> node.textLength == 1 && node.textContains(';')
      else -> node.textLength > 0
    }
  }

  private fun calculateBlockIndent(
    parentType: IElementType,
    blockNode: ASTNode,
    prevNode: ASTNode?
  ): Indent {
    val blockType = blockNode.elementType

    if (blockType == DOT || blockType == QDOT) {
      return Indent.getIndent(Indent.Type.NORMAL, false, true)
    }

    if (parentType in PARENTHESIZED_LISTS) {
      return when (blockType) {
        in PARENS -> Indent.getNoneIndent()
        else -> Indent.getNormalIndent()
      }
    }

    if (parentType in BODIES) {
      return when (blockType) {
        in BRACES -> Indent.getNoneIndent()
        else -> Indent.getNormalIndent()
      }
    }

    if (prevNode == null) return Indent.getNoneIndent()

    val prevType = prevNode.elementType

    if (prevType == ASSIGN || prevType == COLON || prevType == ARROW) {
      return if (blockNode.isWrappedAmendOrAccessExpr()) {
        // Don't enforce indent of this block (the node after `=`, `:` or `->`) on children.
        // Examples:
        // x = y {
        //   foo = 1
        // }
        // x = y(
        //   1
        // )
        // x = y {
        //   foo = 1
        // } |> lambda
        // x = (y) -> z {
        //   foo = 1
        // }
        // x = y {
        //   foo = 1
        // }.z()
        // x = bar(y {
        //   foo = 1
        // })
        // x = bar(y(
        //   1
        // ))
        // x = bar(y {
        //   foo = 1
        // }).z()
        // x = bar1(bar2(y {
        //   foo = 1
        // }))
        Indent.getNormalIndent()
      } else {
        // do enforce indent on children
        Indent.getIndent(Indent.Type.NORMAL, false, true)
      }
    }

    if (prevType in BINARY_OPERATORS) {
      return Indent.getIndent(Indent.Type.NORMAL, false, true)
    }

    if (parentType == IF_EXPR) {
      return when (prevType) {
        in PARENS -> Indent.getNormalIndent()
        ELSE -> if (blockType == IF_EXPR) Indent.getNoneIndent() else Indent.getNormalIndent()
        else -> Indent.getNoneIndent()
      }
    }

    if (parentType == FUNCTION_LITERAL) {
      return when (prevType) {
        ARROW -> Indent.getNormalIndent()
        else -> Indent.getNoneIndent()
      }
    }

    if (parentType in LET_FOR_WHEN) {
      return when (prevType) {
        in PARENS -> Indent.getNormalIndent()
        IN -> Indent.getNormalIndent() // old let syntax
        else -> Indent.getNoneIndent()
      }
    }

    return Indent.getNoneIndent()
  }

  private fun ASTNode?.isWrappedAmendOrAccessExpr(): Boolean {
    if (this == null) return false

    return when (elementType) {
      NEW_EXPR,
      AMEND_EXPR,
      QUALIFIED_ACCESS_EXPR,
      UNQUALIFIED_ACCESS_EXPR -> true // base case
      UNQUALIFIED_ACCESS_EXPR,
      SUPER_ACCESS_EXPR ->
        (psi as PklAccessExpr)
          .argumentList
          ?.elements
          ?.getOrNull(0)
          ?.node
          .isWrappedAmendOrAccessExpr()
      QUALIFIED_ACCESS_EXPR ->
        (psi as PklQualifiedAccessExpr).receiverExpr.node.isWrappedAmendOrAccessExpr() ||
          (psi as PklQualifiedAccessExpr)
            .argumentList
            ?.elements
            ?.getOrNull(0)
            ?.node
            .isWrappedAmendOrAccessExpr()
      FUNCTION_LITERAL -> (psi as PklFunctionLiteral).expr?.node.isWrappedAmendOrAccessExpr()
      PIPE_BIN_EXPR -> (psi as PklPipeBinExpr).leftExpr.node.isWrappedAmendOrAccessExpr()
      IF_EXPR ->
        (psi as PklIfExpr).thenExpr?.node.isWrappedAmendOrAccessExpr() ||
          (psi as PklIfExpr).elseExpr?.node.isWrappedAmendOrAccessExpr()
      FOR_GENERATOR -> (psi as PklForGenerator).iterableExpr?.node.isWrappedAmendOrAccessExpr()
      WHEN_GENERATOR -> (psi as PklWhenGenerator).conditionExpr?.node.isWrappedAmendOrAccessExpr()
      else -> false
    }
  }
}
