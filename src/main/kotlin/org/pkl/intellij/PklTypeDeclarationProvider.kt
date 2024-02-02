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

import com.intellij.codeInsight.navigation.actions.TypeDeclarationProvider
import com.intellij.psi.PsiElement
import org.pkl.intellij.psi.PklAccessExpr
import org.pkl.intellij.psi.pklBaseModule
import org.pkl.intellij.resolve.ResolveVisitors
import org.pkl.intellij.type.computeResolvedImportType

class PklTypeDeclarationProvider : TypeDeclarationProvider {
  override fun getSymbolTypeDeclarations(element: PsiElement): Array<PsiElement> {
    val base = element.project.pklBaseModule

    return when (element) {
      is PklAccessExpr -> {
        val visitor = ResolveVisitors.elementsNamed(element.memberNameText, base)
        // assume that filtering out identical PSIs is good enough
        val result = mutableSetOf<PsiElement>()
        for (target in element.resolve(base, null, mapOf(), visitor)) {
          getSymbolTypeDeclarations(target).let { result.addAll(it) }
        }
        result.toTypedArray()
      }
      else ->
        element.computeResolvedImportType(base, mapOf()).resolveToDefinitions(base).toTypedArray()
    }
  }
}
