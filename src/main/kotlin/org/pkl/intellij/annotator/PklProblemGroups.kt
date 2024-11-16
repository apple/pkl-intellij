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
package org.pkl.intellij.annotator

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.lang.annotation.ProblemGroup
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.util.parentsOfType
import org.pkl.intellij.intention.PklSuppressWarningsQuickFix
import org.pkl.intellij.psi.PklSuppressWarningsTarget
import org.pkl.intellij.util.canModify

object PklProblemGroups {
  val deprecated: PklProblemGroup = PklProblemGroup("Deprecated")

  val pklVersionMismatch: PklProblemGroup = PklProblemGroup("PklVersionMismatch")

  val unresolvedElement: PklProblemGroup = PklProblemGroup("UnresolvedElement")

  val typeMismatch: PklProblemGroup = PklProblemGroup("TypeMismatch")

  val replaceForGeneratorWithSpread: PklProblemGroup =
    PklProblemGroup("ReplaceForGeneratorWithSpread")

  val unsupportedFeature: PklProblemGroup = PklProblemGroup("UnsupportedFeature")

  val missingDefaultValue: PklProblemGroup = PklProblemGroup("MissingDefaultValue")

  val missingRequiredValues: PklProblemGroup = PklProblemGroup("MissingRequiredValues")
}

// implementing `SuppressableProblemGroup` doesn't seem to do anything
// for annotators that add warning annotations with a problem group
class PklProblemGroup(private val problemName: String) : ProblemGroup {
  fun getSuppressQuickFixes(element: PsiElement, file: PsiFile): List<IntentionAction> {
    if (!file.canModify()) return listOf()

    val fixes = mutableListOf<IntentionAction>()
    for (target in element.parentsOfType<PklSuppressWarningsTarget>()) {
      val pointer =
        SmartPointerManager.getInstance(file.project).createSmartPsiElementPointer(target, file)
      fixes.add(PklSuppressWarningsQuickFix(element, this, pointer))
    }
    return fixes
  }

  override fun getProblemName(): String = problemName
}
