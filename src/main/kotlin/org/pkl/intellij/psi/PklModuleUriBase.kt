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
package org.pkl.intellij.psi

import com.intellij.lang.ASTNode
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReference
import com.intellij.psi.stubs.IStubElementType
import com.intellij.psi.stubs.StubElement
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import org.pkl.intellij.PklLanguage
import org.pkl.intellij.stubs.PklModuleUriStub
import org.pkl.intellij.stubs.PklStubElementTypes

abstract class PklModuleUriBase : PklStubBasedPsiElementBase<PklModuleUriStub>, PklModuleUri {
  constructor(node: ASTNode) : super(node)

  constructor(stub: PklModuleUriStub, stubType: IStubElementType<*, *>) : super(stub, stubType)

  override fun getElementType(): IStubElementType<out StubElement<*>, *> =
    PklStubElementTypes.MODULE_URI

  override fun getReference(): PsiReference? = references.firstOrNull()

  override fun getModuleUri(): String? {
    return stub?.moduleUri ?: stringConstant.content.escapedText()
  }

  override fun getReferences(): Array<PsiReference> {
    val psiManager = PsiManager.getInstance(project)
    return CachedValuesManager.getManager(project).getCachedValue(this) {
      val content = stringConstant.content
      val contentText = content.text
      val contentOffset = stringConstant.stringStart.textLength
      val result: Array<PsiReference> =
        when {
          isModulePathOrFileUri(contentText) -> createReferences(contentText, contentOffset)
          isPackageUri(contentText) -> createPackageReferences(contentText, contentOffset)
          else -> arrayOf(PklModuleUriReference(this, content.textRangeInParent))
        }
      CachedValueProvider.Result.create(
        result,
        psiManager.modificationTracker.forLanguage(PklLanguage)
      )
    }
  }

  private fun isModulePathOrFileUri(uri: String): Boolean {
    return when {
      uri.startsWith("modulepath:", ignoreCase = true) -> true
      uri.startsWith("file:", ignoreCase = true) -> true
      uri.contains(":") -> false
      else ->
        // `.originalFile` because IntelliJ's code completion mechanism
        // creates PsiFile copy which returns `null` for `.virtualFile`
        !containingFile.originalFile.virtualFile.url.startsWith("https:", ignoreCase = true)
    }
  }

  private fun isPackageUri(uri: String): Boolean {
    return when {
      uri.startsWith("package:", ignoreCase = true) -> true
      else -> enclosingModule?.isInPackage == true && uri.startsWith('@')
    }
  }

  private fun createReferences(contentText: String, contentOffset: Int): Array<PsiReference> {
    val references = mutableListOf<PklModuleUriReference>()
    val colonIndex = contentText.indexOf(':')
    var firstPos = colonIndex + 1
    while (firstPos < contentText.length && contentText[firstPos] == '/') {
      firstPos += 1
    }
    var prevPos = firstPos
    var currPos = firstPos
    while (currPos < contentText.length) {
      if (contentText[currPos] == '/') {
        val range = TextRange(prevPos, currPos).shiftRight(contentOffset)
        references.add(PklModuleUriReference(this, range))
        prevPos = currPos + 1
      }
      currPos += 1
    }
    if (currPos > prevPos) {
      val range = TextRange(prevPos, currPos).shiftRight(contentOffset)
      references.add(PklModuleUriReference(this, range))
    }
    return references.toTypedArray()
  }

  private fun createPackageReferences(
    contentText: String,
    contentOffset: Int
  ): Array<PsiReference> {
    val references = mutableListOf<PklModuleUriReference>()
    val offset = contentText.indexOf("#") + 1
    // either no fragment, or fragment doesn't start with `/`. Just create a reference to the whole
    // thing.
    if (offset == 0 || offset == contentText.length) {
      return arrayOf(
        PklModuleUriReference(this, TextRange(contentOffset, contentText.length + contentOffset))
      )
    }
    val firstRange = TextRange(0, offset + 1).shiftRight(contentOffset)
    // create a reference for the package root itself.
    references.add(PklModuleUriReference(this, firstRange))
    for (reference in createReferences(contentText.drop(offset), offset + contentOffset)) {
      references.add(reference as PklModuleUriReference)
    }
    return references.toTypedArray()
  }
}
