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
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.psi.util.parentOfType
import com.intellij.psi.util.parentOfTypes
import com.intellij.util.ProcessingContext
import org.pkl.intellij.psi.PklClass
import org.pkl.intellij.psi.PklClassMember
import org.pkl.intellij.psi.PklClassMethod
import org.pkl.intellij.psi.PklModule
import org.pkl.intellij.psi.PklTypeDefOrModule
import org.pkl.intellij.psi.createMember
import org.pkl.intellij.psi.enclosingModule
import org.pkl.intellij.psi.hasDeclaredMethod
import org.pkl.intellij.psi.methods
import org.pkl.intellij.psi.pklBaseModule
import org.pkl.intellij.util.appendIdentifier
import org.pkl.intellij.util.insertTemplateAtTodoExprs

private class MemberInsertHandler(private val parentMember: PklClassMember) :
  InsertHandler<LookupElement> {
  override fun handleInsert(context: InsertionContext, element: LookupElement) {
    val classMember =
      context.file.findElementAt(context.startOffset)?.parentOfType<PklClassMember>() ?: return
    val enclosingModule = context.file as? PklModule ?: return
    val enclosingDef = classMember.parentOfTypes(PklTypeDefOrModule::class) ?: return
    val psi = enclosingDef.createMember(parentMember, enclosingModule)
    val insertedPsi = classMember.replace(psi)
    insertTemplateAtTodoExprs(listOf(insertedPsi), context.editor, context.file)
  }
}

class ImplementMemberCompletionProvider : PklCompletionProvider() {
  override fun addCompletions(
    parameters: CompletionParameters,
    context: ProcessingContext,
    result: CompletionResultSet
  ) {
    val position = parameters.position
    val originalFile = parameters.originalFile
    val base = originalFile.project.pklBaseModule
    val def = position.parentOfTypes(PklClass::class, PklModule::class)!!
    val context = def.enclosingModule?.pklProject
    // only offer completions for abstract methods for now.
    // completions for other methods might encourage overriding functions that are meant to be
    // closed
    // (Pkl does not have open/closed functions)
    val parentMethods =
      def.methods(context)?.values?.filterNot { def.hasDeclaredMethod(it.name) || !it.isAbstract }
        ?: return
    for (prop in parentMethods) {
      val lookupElement =
        LookupElementBuilder.createWithIcon(prop)
          .withPresentableText(renderCompletionText(prop))
          .bold()
          .withTypeText(prop.getLookupElementType(base, mapOf(), context).render(), true)
          .withInsertHandler(MemberInsertHandler(prop))
      result.addElement(lookupElement)
    }
  }

  fun renderCompletionText(prop: PklClassMethod): String {
    return buildString {
      append("function ")
      // `name` guaranteed to exist (we only provide completions for members that have names)
      appendIdentifier(prop.name!!)
      append("(")
      prop.parameterList?.elements?.let { params ->
        var isFirst = true
        for (param in params) {
          if (isFirst) {
            isFirst = false
          } else {
            append(", ")
          }
          append(param.identifier.text)
          param.type?.let { type ->
            append(": ")
            append(type.text)
          }
        }
      }
      append(")")
      prop.returnType?.let { type ->
        append(": ")
        append(type.text)
      }
      append(" = …")
    }
  }
}
