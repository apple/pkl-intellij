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
package org.pkl.intellij

import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lexer.FlexAdapter
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.TokenSet
import org.pkl.intellij.lexer._PklLexer
import org.pkl.intellij.parser.PklParser
import org.pkl.intellij.psi.PklElementTypes
import org.pkl.intellij.psi.PklModuleImpl
import org.pkl.intellij.stubs.PklModuleStub

class PklParserDefinition : ParserDefinition {
  companion object {
    val FILE = PklModuleStub.Type

    private val COMMENTS =
      TokenSet.create(PklElementTypes.LINE_COMMENT, PklElementTypes.BLOCK_COMMENT)
    private val STRING_LITERALS =
      TokenSet.create(PklElementTypes.STRING_LITERAL, PklElementTypes.ML_STRING_LITERAL)
  }

  override fun createLexer(project: Project) = FlexAdapter(_PklLexer())

  override fun createParser(project: Project) = PklParser()

  override fun getFileNodeType() = FILE

  override fun getCommentTokens() = COMMENTS

  override fun getStringLiteralElements() = STRING_LITERALS

  override fun createElement(node: ASTNode): PsiElement =
    PklElementTypes.Factory.createElement(node)

  override fun createFile(viewProvider: FileViewProvider) = PklModuleImpl(viewProvider)

  override fun spaceExistenceTypeBetweenTokens(left: ASTNode, right: ASTNode) =
    ParserDefinition.SpaceRequirements.MAY
}
