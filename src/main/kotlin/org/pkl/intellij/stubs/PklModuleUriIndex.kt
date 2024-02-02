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

import com.intellij.psi.stubs.StringStubIndexExtension
import com.intellij.psi.stubs.StubIndexKey
import org.pkl.intellij.psi.PklModuleUri

class PklModuleUriIndex : StringStubIndexExtension<PklModuleUri>() {
  override fun getKey(): StubIndexKey<String, PklModuleUri> = Util.KEY

  object Util {
    val KEY: StubIndexKey<String, PklModuleUri> =
      StubIndexKey.createIndexKey("org.pkl.intellij.stubs.index.PklModuleUri")
  }
}
