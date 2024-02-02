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
package org.pkl.intellij.intention

import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement
import com.intellij.lang.ASTNode
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.impl.source.tree.PsiWhiteSpaceImpl
import org.pkl.intellij.psi.*

class PklDefinePropertiesValuesQuickFix(
  element: PklTypeDefOrModule,
  val properties: List<SmartPsiElementPointer<PklClassProperty>>
) : LocalQuickFixAndIntentionActionOnPsiElement(element) {
  override fun getFamilyName(): String = "QuickFix"

  override fun getText(): String = "Define fixed/const property values"

  override fun invoke(
    project: Project,
    file: PsiFile,
    editor: Editor?,
    startElement: PsiElement,
    endElement: PsiElement
  ) {
    val props = properties.mapNotNull { it.element }
    if (startElement is PklClassBase) {
      if (startElement.body != null) {
        startElement.addProperties(startElement.body!!.node, props)
      } else {
        val newClass =
          PklPsiFactory.createClassWithProperties(
            startElement.identifier!!.text,
            startElement.extendsClause?.type?.text,
            props,
            project
          )
        startElement.replace(newClass)
      }
    } else {
      startElement as PklModule
      startElement.addProperties(startElement.node, props)
    }
    if (editor != null) {
      PsiDocumentManager.getInstance(project).commitDocument(editor.document)
    }
  }

  private fun PklTypeDefOrModule.addProperties(node: ASTNode, properties: List<PklClassProperty>) {
    val isModule = this is PklModule
    val propertiesContainer =
      when (this) {
        is PklModule -> memberList
        is PklClass -> body ?: return
        else -> return
      }
    val lastProperty = propertiesContainer.lastChildOfClass<PklClassProperty>()
    val insertAfter: PsiElement? = lastProperty ?: propertiesContainer.firstChild
    val insertBefore = insertAfter?.nextSibling?.skipWhitespace()?.node
    val newlines = insertAfter?.nextSibling?.text?.takeLastWhile { it == '\n' }?.length ?: 0
    val newlinesWanted = if (insertAfter?.elementType == PklElementTypes.LBRACE) 1 else 2
    val initialSpacing = (newlinesWanted - newlines).coerceAtLeast(0)
    if (initialSpacing > 0) {
      node.addChild(PsiWhiteSpaceImpl("\n".repeat(initialSpacing)), insertBefore)
    }
    val indent = if (isModule) "" else "  "
    var isFirst = true
    for (property in properties) {
      val psi = PklPsiFactory.createClassPropertyWithoutTypeAnnotation(property, project)
      if (isFirst) {
        isFirst = false
      } else {
        node.addChild(PsiWhiteSpaceImpl("\n"), insertBefore)
      }
      if (!isModule) {
        node.addChild(PsiWhiteSpaceImpl(indent), insertBefore)
      }
      node.addChild(psi.node, insertBefore)
      node.addChild(PsiWhiteSpaceImpl("\n"), insertBefore)
    }
  }
}
