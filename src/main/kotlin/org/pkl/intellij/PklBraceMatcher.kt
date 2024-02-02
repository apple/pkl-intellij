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

import com.intellij.lang.BracePair
import com.intellij.lang.PairedBraceMatcher
import com.intellij.psi.*
import com.intellij.psi.tree.IElementType
import org.pkl.intellij.psi.PklClassBody
import org.pkl.intellij.psi.PklElementTypes
import org.pkl.intellij.psi.PklObjectBody

// https://www.jetbrains.org/intellij/sdk/docs/reference_guide/custom_language_support/additional_minor_features.html
class PklBraceMatcher : PairedBraceMatcher {
  companion object {
    // for some reason doesn't work for `<>`
    private val PAIRS =
      arrayOf(
        BracePair(PklElementTypes.LPAREN, PklElementTypes.RPAREN, false),
        BracePair(PklElementTypes.LBRACK, PklElementTypes.RBRACK, false),
        BracePair(PklElementTypes.LBRACE, PklElementTypes.RBRACE, true),
        BracePair(PklElementTypes.LPRED, PklElementTypes.RPRED, false),
        BracePair(PklElementTypes.INTERPOLATION_START, PklElementTypes.INTERPOLATION_END, false)
      )
  }

  override fun getPairs(): Array<BracePair> = PAIRS

  override fun isPairedBracesAllowedBeforeType(
    lbraceType: IElementType,
    contextType: IElementType?
  ): Boolean = true

  override fun getCodeConstructStart(file: PsiFile, openingBraceOffset: Int): Int {
    val openingBrace = file.findElementAt(openingBraceOffset)
    if (openingBrace == null || openingBrace is PsiFile) return openingBraceOffset

    return when (val openingBraceParent = openingBrace.parent) {
      is PklObjectBody,
      is PklClassBody -> openingBraceParent.parent.textRange.startOffset
      else -> openingBraceOffset
    }
  }
}
