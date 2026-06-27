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

import com.intellij.codeInsight.template.TemplateBuilderFactory
import com.intellij.codeInsight.template.TemplateBuilderImpl
import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement
import com.intellij.lang.ASTNode
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.source.tree.PsiWhiteSpaceImpl
import com.intellij.psi.util.PsiTreeUtil
import org.pkl.intellij.packages.dto.PklProject
import org.pkl.intellij.psi.*

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
      def.effectiveParentProperties(context)?.values?.filterNot { def.hasDeclaredProperty(it.name) }
    val parentMethods = def.methods(context)?.values?.filterNot { def.hasDeclaredMethod(it.name) }
    val parentMembers = (parentProperties ?: listOf()) + (parentMethods ?: listOf())
    val importList = myModule.importList
    val insertedMembers = mutableListOf<PsiElement>()
    if (def is PklClassBase) {
      if (def.body != null) {
        def.addMembers(def.body!!.node, parentMembers, importList, myModule, insertedMembers)
      } else {
        val newClass = PklPsiFactory.createClassWithEmptyBody(def, project)
        newClass.addMembers(
          newClass.body!!.node,
          parentMembers,
          importList,
          myModule,
          insertedMembers
        )
        val inserted = def.replace(newClass)
        // replace() may copy the element; re-collect TODOs from the live tree element
        insertedMembers.clear()
        insertedMembers.add(inserted)
      }
    } else {
      def as PklModule
      def.addMembers(def.memberList.node, parentMembers, importList, myModule, insertedMembers)
    }
    if (editor != null) {
      PsiDocumentManager.getInstance(project)
        .doPostponedOperationsAndUnblockDocument(editor.document)
      val todoExprs =
        insertedMembers
          .flatMap { PsiTreeUtil.collectElementsOfType(it, PklUnqualifiedAccessExpr::class.java) }
          .filter { it.text == "TODO()" }
      if (todoExprs.isNotEmpty()) {
        val templateBuilderFactory =
          ApplicationManager.getApplication().getService(TemplateBuilderFactory::class.java)
        val builder = templateBuilderFactory.createTemplateBuilder(file) as TemplateBuilderImpl
        for (expr in todoExprs) {
          builder.replaceElement(expr, "TODO()")
        }
        editor.caretModel.moveToOffset(todoExprs.first().textOffset)
        builder.run(editor, true)
      }
    }
  }

  private fun PklTypeDefOrModule.addMembers(
    node: ASTNode,
    parentMembers: List<PklClassMember>,
    importList: PklImportList,
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
      val psi = createMember(myModule, parentMember, importList, project) ?: continue
      if (isFirst) {
        isFirst = false
      } else {
        node.addChild(PsiWhiteSpaceImpl("\n"), insertBefore)
      }
      if (!isModule) {
        node.addChild(PsiWhiteSpaceImpl(indent), insertBefore)
      }
      node.addChild(psi.node, insertBefore)
      insertedMembers.add(psi)
      node.addChild(PsiWhiteSpaceImpl("\n"), insertBefore)
    }
  }

  private fun createMember(
    myModule: PklModule,
    parentMember: PklClassMember,
    importList: PklImportList,
    project: Project
  ): PsiElement? {
    return when {
      parentMember.isAbstract && parentMember is PklClassProperty -> {
        val propertyText = renderProperty(myModule, parentMember, importList)
        PklPsiFactory.createClassProperty(propertyText, project)
      }
      parentMember.isAbstract && parentMember is PklClassMethod -> {
        val text = renderMethod(myModule, parentMember, importList)
        PklPsiFactory.createClassMethod(text, project)
      }
      parentMember is PklClassProperty && parentMember.isFixedOrConst -> {
        val text = renderFixedConstProperty(parentMember)
        PklPsiFactory.createClassProperty(text, project)
      }
      else -> null
    }
  }

  private fun StringBuilder.appendModifiersWithoutAbstract(modifierList: PklModifierList): Boolean {
    var isFirst = true
    for (modifier in modifierList.elements) {
      if (modifier.elementType != PklElementTypes.ABSTRACT) {
        if (isFirst) {
          isFirst = false
        } else {
          append(" ")
        }
        append(modifier.text)
      }
    }
    return !isFirst
  }

  private fun renderFixedConstProperty(property: PklClassProperty): String {
    return buildString {
      if (appendModifiersWithoutAbstract(property.modifierList)) {
        append(" ")
      }
      append(property.nameIdentifier.text)
      append(" = TODO()")
    }
  }

  private fun renderProperty(
    myModule: PklModule,
    originalProperty: PklClassProperty,
    importList: PklImportList,
  ): String {
    return buildString {
      if (appendModifiersWithoutAbstract(originalProperty.modifierList)) {
        append(" ")
      }
      append(originalProperty.nameIdentifier.text)
      val type = originalProperty.type
      if (type != null) {
        append(": ")
        appendType(type, myModule, importList)
      }
      append(" = TODO()")
    }
  }

  private fun renderMethod(
    myModule: PklModule,
    originalMethod: PklClassMethod,
    importList: PklImportList,
  ): String {
    return buildString {
      if (appendModifiersWithoutAbstract(originalMethod.modifierList)) {
        append(' ')
      }
      append("function ")
      append(originalMethod.nameIdentifier!!.text)
      append('(')
      originalMethod.parameterList?.let { paramList ->
        var isFirst = true
        for (param in paramList.elements) {
          if (isFirst) {
            isFirst = false
          } else {
            append(", ")
          }
          append(param.nameIdentifier!!.text)
          val type = param.type
          if (type != null) {
            append(": ")
            appendType(type, myModule, importList)
          }
        }
      }
      append(')')
      val returnType = originalMethod.returnType
      if (returnType != null) {
        append(": ")
        appendType(returnType, myModule, importList)
      }
      append(" = TODO()")
    }
  }

  private fun StringBuilder.appendType(
    type: PklType,
    myModule: PklModule,
    imports: PklImportList,
  ) {
    type.accept(TypeNameRenderer(this, type, myModule, imports, myModule.pklProject))
  }

  class TypeNameRenderer(
    private val sb: StringBuilder,
    private val type: PklType,
    private val myModule: PklModule,
    private val imports: PklImportList,
    private val context: PklProject?
  ) : PklVisitor<Unit>() {

    private fun renderSimpleTypeName(o: PklDeclaredType) {
      val module = o.enclosingModule ?: return
      if (module == myModule) {
        sb.append(o.typeName.simpleName.identifier.text)
        return
      }
      val resolvedType = o.typeName.resolve(context) as? PklTypeDefOrModule
      when {
        resolvedType == null -> sb.append(o.typeName.simpleName.identifier.text)
        resolvedType.isInPklBaseModule || resolvedType.enclosingModule == myModule ->
          sb.append(resolvedType.nameIdentifier?.text ?: "<>")
        else -> {
          val importName = imports.findOrInsertImport(resolvedType.enclosingModule!!)
          sb.append(importName)
          if (resolvedType !is PklModule) {
            sb.append(".")
            sb.append(resolvedType.nameIdentifier!!.text)
          }
        }
      }
    }

    override fun visitDeclaredType(o: PklDeclaredType) {
      renderSimpleTypeName(o)
      val argumentList = o.typeArgumentList
      if (argumentList != null && argumentList.elements.isNotEmpty()) {
        sb.append("<")
        var isFirst = true
        for (elem in argumentList.elements) {
          if (isFirst) {
            isFirst = false
          } else {
            sb.append(", ")
          }
          elem.accept(this)
        }
        sb.append(">")
      }
    }

    override fun visitUnknownType(o: PklUnknownType) {
      sb.append("unknown")
    }

    override fun visitDefaultType(o: PklDefaultType) {
      sb.append("*")
      o.type?.accept(this)
    }

    override fun visitParenthesizedType(o: PklParenthesizedType) {
      sb.append("(")
      o.type?.accept(this)
      sb.append(")")
    }

    override fun visitUnionType(o: PklUnionType) {
      o.leftType.accept(this)
      sb.append(" | ")
      o.rightType?.accept(this)
    }

    override fun visitFunctionType(o: PklFunctionType) {
      @Suppress("DuplicatedCode") sb.append("(")
      var isFirst = true
      for (param in o.functionTypeParameterList.elements) {
        if (isFirst) {
          isFirst = false
        } else {
          sb.append(", ")
        }
        param.accept(this)
      }
      sb.append(") -> ")
      o.type?.accept(this)
    }

    override fun visitModuleType(o: PklModuleType) {
      val module = type.enclosingModule ?: return
      if (module == myModule) {
        sb.append("module")
      } else {
        sb.append(imports.findOrInsertImport(module))
      }
    }

    override fun visitNothingType(o: PklNothingType) {
      sb.append("nothing")
    }

    override fun visitNullableType(o: PklNullableType) {
      o.type.accept(this)
      sb.append("?")
    }

    override fun visitStringLiteralType(o: PklStringLiteralType) {
      sb.append(o.text)
    }

    override fun visitConstrainedType(o: PklConstrainedType) {
      o.type.accept(this)
      sb.append("(")
      var isFirst = true
      for (expr in o.typeConstraintList.elements) {
        if (isFirst) {
          isFirst = false
        } else {
          sb.append(", ")
        }
        expr.accept(this)
      }
      sb.append(")")
    }

    override fun visitExpr(o: PklExpr) {
      // TODO this doesn't handle member access.
      sb.append(o.text)
    }

    override fun visitTypeTestExpr(o: PklTypeTestExpr) {
      o.expr.accept(this)
      sb.append(' ')
      sb.append(o.operator.text)
      sb.append(' ')
      o.type.accept(this)
    }

    override fun visitTypeCastExpr(o: PklTypeCastExpr) {
      o.expr.accept(this)
      sb.append(' ')
      sb.append(o.operator.text)
      sb.append(' ')
      o.type.accept(this)
    }
  }
}
