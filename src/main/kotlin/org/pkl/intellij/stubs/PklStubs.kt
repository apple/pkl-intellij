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
package org.pkl.intellij.stubs

import com.intellij.psi.PsiElement
import com.intellij.psi.stubs.PsiFileStubImpl
import com.intellij.psi.stubs.StubBase
import com.intellij.psi.stubs.StubElement
import com.intellij.psi.tree.IStubFileElementType
import org.pkl.intellij.PklLanguage
import org.pkl.intellij.psi.PklModule
import org.pkl.intellij.psi.PklModuleUri

class PklModuleStub(module: PklModule) : PsiFileStubImpl<PklModule>(module) {
  object Type : IStubFileElementType<PklModuleStub>(PklLanguage) {
    override fun getExternalId(): String = "Pkl.file"
  }
}

interface PklModuleUriStub : StubElement<PklModuleUri> {
  val moduleUri: String?
}

class PklModuleUriStubImpl(parent: StubElement<out PsiElement>, override val moduleUri: String?) :
  StubBase<PklModuleUri>(parent, PklStubElementTypes.MODULE_URI), PklModuleUriStub {
  override fun toString(): String = "PklModuleUriStubImpl"
}

fun factory(name: String): PklStubElementType<*, *> =
  when (name) {
    "MODULE_URI" -> PklStubElementTypes.MODULE_URI
    else -> error("Unknown type: $name")
  }
