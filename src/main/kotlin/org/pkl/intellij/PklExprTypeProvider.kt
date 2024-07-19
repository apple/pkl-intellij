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

import com.intellij.lang.ExpressionTypeProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.util.parentOfType
import org.pkl.intellij.psi.PklExpr
import org.pkl.intellij.psi.enclosingModule
import org.pkl.intellij.psi.pklBaseModule
import org.pkl.intellij.type.computeExprType
import org.pkl.intellij.util.escapeXml

class PklExprTypeProvider : ExpressionTypeProvider<PklExpr>() {
  override fun getInformationHint(element: PklExpr): String {
    val type =
      element.computeExprType(
        element.project.pklBaseModule,
        mapOf(),
        element.enclosingModule?.pklProject
      )
    return type.render().escapeXml()
  }

  override fun getErrorHint(): String = "No expression found"

  override fun getExpressionsAt(elementAt: PsiElement): List<PklExpr> {
    return generateSequence(elementAt.parentOfType<PklExpr>()) { it.parent as? PklExpr }.toList()
  }
}
