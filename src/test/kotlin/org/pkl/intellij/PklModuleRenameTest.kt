/**
 * Copyright © 2026 Apple Inc. and the Pkl project authors. All rights reserved.
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

import com.intellij.lang.findUsages.LanguageFindUsages
import org.assertj.core.api.Assertions.assertThat
import org.pkl.intellij.psi.PklModule

class PklModuleRenameTest : PklTestCase() {
  fun `test module declaration descriptive name`() {
    val module =
      myFixture.configureByText(PklFileType, "open module NamesSpacedApplication") as PklModule
    assertThat(LanguageFindUsages.getDescriptiveName(module.declaration!!))
      .isEqualTo("NamesSpacedApplication")
  }

  fun `test rename module declaration`() {
    myFixture.configureByText(PklFileType, "open module NamesSpaced<caret>Application")
    myFixture.renameElementAtCaret("NamespacedApplication")
    myFixture.checkResult("open module NamespacedApplication")
  }

  fun `test rename module preserves extends clause`() {
    myFixture.addFileToProject("Base.pkl", "open module Base")
    myFixture.configureByText(
      PklFileType,
      "open module NamesSpaced<caret>Application extends \"Base.pkl\"",
    )
    myFixture.renameElementAtCaret("NamespacedApplication")
    myFixture.checkResult("open module NamespacedApplication extends \"Base.pkl\"")
  }

  fun `test rename qualified module declaration`() {
    myFixture.configureByText(PklFileType, "open module example.NamesSpaced<caret>Application")
    myFixture.renameElementAtCaret("NamespacedApplication")
    myFixture.checkResult("open module example.NamespacedApplication")
  }
}
