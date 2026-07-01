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
package org.pkl.intellij.intention

import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement
import com.intellij.lang.ASTNode
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.source.tree.PsiWhiteSpaceImpl
import org.pkl.intellij.psi.*
import org.pkl.intellij.util.insertTemplateAtTodoExprs

class PklImplementMembersQuickFix(element: PklTypeDefOrModule) :
  LocalQuickFixAndIntentionActionOnPsiElement(element) {

  override fun getFamilyName(): String = "QuickFix"

  override fun getText(): String = "Implement members"

  override fun invoke(
    project: Project,
    file: PsiFile,
    editor: Editor?,
    startElement: PsiElement,
    endElement: PsiElement
  ) {
    val def = startElement as PklTypeDefOrModule
    val myModule = startElement.enclosingModule ?: return
    val context = myModule.pklProject
    val parentProperties =
      def.effectiveParentProperties(context)?.values?.filterNot {
        def.hasDeclaredProperty(it.name) || !it.isFixedOrConstOrAbstract
      }
    val parentMethods =
      def.methods(context)?.values?.filterNot { def.hasDeclaredMethod(it.name) || !it.isAbstract }
    val parentMembers = (parentProperties ?: listOf()) + (parentMethods ?: listOf())
    val insertedMembers = mutableListOf<PsiElement>()
    if (def is PklClassBase) {
      if (def.body != null) {
        def.addMembers(def.body!!.node, parentMembers, myModule, insertedMembers)
      } else {
        val newClass = PklPsiFactory.createClassWithEmptyBody(def, project)
        newClass.addMembers(newClass.body!!.node, parentMembers, myModule, insertedMembers)
        val inserted = def.replace(newClass)
        // replace() may copy the element; re-collect TODOs from the live tree element
        insertedMembers.clear()
        insertedMembers.add(inserted)
      }
    } else {
      def as PklModule
      def.addMembers(def.memberList.node, parentMembers, myModule, insertedMembers)
    }
    if (editor != null) {
      insertTemplateAtTodoExprs(insertedMembers, editor, file)
    }
  }

  private fun PklTypeDefOrModule.addMembers(
    node: ASTNode,
    parentMembers: List<PklClassMember>,
    myModule: PklModule,
    insertedMembers: MutableList<PsiElement>
  ) {
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
    for (parentMember in parentMembers) {
      if (isFirst) {
        isFirst = false
      } else {
        node.addChild(PsiWhiteSpaceImpl("\n"), insertBefore)
      }
      if (!isModule) {
        node.addChild(PsiWhiteSpaceImpl(indent), insertBefore)
      }
      val psi = createMember(parentMember, myModule)
      node.addChild(psi.node, insertBefore)
      insertedMembers.add(psi)
      node.addChild(PsiWhiteSpaceImpl("\n"), insertBefore)
    }
  }
}
