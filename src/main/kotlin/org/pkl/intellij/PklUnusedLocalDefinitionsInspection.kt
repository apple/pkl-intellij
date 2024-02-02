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

import com.intellij.codeInspection.*
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.parentOfType
import java.util.*
import org.pkl.intellij.intention.PklMakeBlankIdentifierQuickFix
import org.pkl.intellij.psi.*
import org.pkl.intellij.resolve.visitLocalDefinitions
import org.pkl.intellij.resolve.visitUsedLocalDefinitions

class PklUnusedLocalDefinitionsInspection : LocalInspectionTool() {
  override fun checkFile(
    file: PsiFile,
    manager: InspectionManager,
    isOnTheFly: Boolean
  ): Array<ProblemDescriptor?> {
    val module = file as? PklModule ?: return arrayOf()

    val project = module.project
    val base = project.pklBaseModule
    val problems = mutableListOf<ProblemDescriptor>()

    val usedDefinitions: IdentityHashMap<PklElement, PklElement> = IdentityHashMap()
    visitUsedLocalDefinitions(module, base) { usedDefinitions[it] = it }

    fun report(element: PsiElement?, message: String) {
      if (element == null || element.textMatches("_")) return
      val parent = element.parentOfType<PklTypedIdentifier>()
      val fixes =
        if (parent != null) {
          arrayOf(PklMakeBlankIdentifierQuickFix(parent, isIgnore = true))
        } else arrayOf()
      problems.add(
        manager.createProblemDescriptor(
          element,
          message,
          isOnTheFly,
          fixes,
          ProblemHighlightType.LIKE_UNUSED_SYMBOL
        )
      )
    }

    // TODO: only report unused imports that resolve successfully?
    visitLocalDefinitions(module) { definition ->
      if (!usedDefinitions.contains(definition)) {
        // TODO: quick fixes
        when (definition) {
          is PklImport -> report(definition, "Unused import")
          is PklClass -> report(definition.identifier, "Unused class")
          is PklTypeAlias -> report(definition.identifier, "Unused type alias")
          is PklMethod -> report(definition.identifier, "Unused method")
          is PklProperty -> report(definition.nameIdentifier, "Unused property")
          is PklTypedIdentifier ->
            when (val parent = definition.parent) {
              // TODO: value of for-generator's key+value can't be removed even if unused
              is PklLetExpr,
              is PklForGenerator -> report(definition.identifier, "Unused variable")
              // TODO: switch to PklParameterList and think what to do about unused lambda params
              is PklFunctionParameterList -> {
                val grandparent = parent.parent
                if (
                  grandparent !is PklModifierListOwner ||
                    !grandparent.isAbstract && !grandparent.isExternal
                ) {
                  report(definition.identifier, "Unused parameter")
                }
              }
            }
          is PklTypeParameter -> report(definition.identifier, "Unused variable")
        }
      }
    }

    return problems.toTypedArray()
  }

  override fun runForWholeFile(): Boolean = true
}
