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
package org.pkl.intellij.completion

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionType.BASIC
import com.intellij.patterns.PlatformPatterns.psiElement
import com.intellij.patterns.VirtualFilePattern
import org.pkl.intellij.psi.*

class PklCompletionContributor : CompletionContributor() {
  init {
    extend(
      BASIC,
      psiElement(PklElementTypes.IDENTIFIER).withParent(PklSimpleTypeName::class.java),
      TypeNameCompletionProvider()
    )

    extend(
      BASIC,
      psiElement(PklElementTypes.IDENTIFIER).withParent(PklPropertyName::class.java),
      UnqualifiedAccessCompletionProvider()
    )

    extend(
      BASIC,
      psiElement(PklElementTypes.IDENTIFIER).withParent(PklUnqualifiedAccessName::class.java),
      UnqualifiedAccessCompletionProvider()
    )

    extend(
      BASIC,
      psiElement(PklElementTypes.IDENTIFIER).withParent(PklQualifiedAccessName::class.java),
      QualifiedAccessCompletionProvider()
    )

    extend(
      BASIC,
      psiElement(PklElementTypes.STRING_CHARS)
        .withSuperParent(2, psiElement(PklStringLiteral::class.java)),
      StringLiteralTypeCompletionProvider()
    )

    extend(
      BASIC,
      psiElement(PklElementTypes.STRING_CHARS)
        .withSuperParent(3, psiElement(PklModuleUri::class.java)),
      ModuleUriCompletionProvider()
    )

    extend(
      BASIC,
      psiElement(PklElementTypes.STRING_CHARS)
        .withSuperParent(3, psiElement(PklObjectProperty::class.java).withName("uri"))
        .withSuperParent(7, psiElement(PklClassProperty::class.java).withName("dependencies"))
        .inVirtualFile(VirtualFilePattern().withName("PklProject")),
      ModuleUriCompletionProvider(packageUriOnly = true)
    )
  }
}
