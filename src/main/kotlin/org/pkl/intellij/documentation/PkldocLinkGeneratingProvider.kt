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

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.html.GeneratingProvider
import org.intellij.markdown.html.HtmlGenerator
import org.pkl.intellij.util.escapeXml

object PkldocLinkGeneratingProvider : GeneratingProvider {
  private val keywords = setOf("null", "true", "false", "this", "unknown", "nothing")

  override fun processNode(
    visitor: HtmlGenerator.HtmlGeneratingVisitor,
    text: String,
    node: ASTNode
  ) {
    val labelNode =
      node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_LABEL } ?: return
    val labelNodeText = labelNode.getTextInNode(text).drop(1).dropLast(1)
    // only full reference links have a text node
    val textNode = node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT }
    val textNodeText = textNode?.getTextInNode(text)?.drop(1)?.dropLast(1)

    fun renderLinkText() {
      visitor.consumeTagOpen(node, "code")
      visitor.consumeHtml((textNodeText ?: labelNodeText).escapeXml())
      visitor.consumeTagClose("code")
    }

    if (labelNodeText in keywords) {
      renderLinkText()
      return
    }

    visitor.consumeTagOpen(node, "a", "href='psi_element://$labelNodeText'")
    renderLinkText()
    visitor.consumeTagClose("a")
  }
}
