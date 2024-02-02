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
import com.intellij.psi.LiteralTextEscaper
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.TokenType

abstract class PklStringContentBase(node: ASTNode) :
  PklAstWrapperPsiElement(node), PklStringContent, PsiLanguageInjectionHost {

  override fun updateText(text: String): PsiLanguageInjectionHost {
    val newContent = PklPsiFactory.createStringContent(text, prevSibling.text, project)
    node.replaceAllChildrenToChildrenOf(newContent.node)
    return this
  }

  override fun createLiteralTextEscaper(): LiteralTextEscaper<out PsiLanguageInjectionHost> =
    PklStringContentEscaper(this)

  override fun isValidHost(): Boolean {
    // only inject into valid constant string literals
    node.eachChild { child ->
      when (child.elementType) {
        PklElementTypes.STRING_CHARS,
        TokenType.WHITE_SPACE,
        PklElementTypes.CHAR_ESCAPE,
        PklElementTypes.UNICODE_ESCAPE -> {}
        else -> return false
      }
    }

    // remaining decision is made in [PklStringLiteralInjector.getLanguagesToInject()]
    return true
  }
}
