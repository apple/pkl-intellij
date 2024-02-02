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
import com.intellij.psi.stubs.*
import org.pkl.intellij.PklLanguage
import org.pkl.intellij.psi.PklModuleUri
import org.pkl.intellij.psi.escapedText
import org.pkl.intellij.psi.impl.PklModuleUriImpl

object PklStubElementTypes {
  val MODULE_URI = PklModuleUriStubElementType()
}

class PklModuleUriStubElementType :
  PklStubElementType<PklModuleUriStub, PklModuleUri>("MODULE_URI") {
  override fun serialize(stub: PklModuleUriStub, dataStream: StubOutputStream) {
    dataStream.writeName(stub.moduleUri)
  }

  override fun deserialize(
    dataStream: StubInputStream,
    parentStub: StubElement<*>
  ): PklModuleUriStub {
    return PklModuleUriStubImpl(parentStub, dataStream.readNameString()!!)
  }

  override fun createStub(
    psi: PklModuleUri,
    parentStub: StubElement<out PsiElement>
  ): PklModuleUriStub {
    return PklModuleUriStubImpl(parentStub, psi.stringConstant.content.escapedText())
  }

  override fun createPsi(stub: PklModuleUriStub): PklModuleUri {
    return PklModuleUriImpl(stub, this)
  }

  override fun indexStub(stub: PklModuleUriStub, sink: IndexSink) {
    stub.moduleUri?.let { uri ->
      // we only care to index `package:` URIs so that we can build the external libraries list.
      if (uri.startsWith("package:")) {
        sink.occurrence(PklModuleUriIndex.Util.KEY, uri)
      }
    }
  }
}

abstract class PklStubElementType<StubT : StubElement<*>, PsiT : PsiElement>(debugName: String) :
  IStubElementType<StubT, PsiT>(debugName, PklLanguage) {
  override fun getExternalId(): String = "pkl.${super.toString()}"
}
