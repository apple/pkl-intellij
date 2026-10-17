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
package org.pkl.intellij.resolve

import com.intellij.openapi.command.WriteCommandAction
import org.assertj.core.api.Assertions.assertThat
import org.pkl.intellij.PklFileType
import org.pkl.intellij.PklTestCase
import org.pkl.intellij.psi.PklClass
import org.pkl.intellij.psi.PklClassProperty
import org.pkl.intellij.psi.PklModule
import org.pkl.intellij.psi.findOrInsertImport

class ReferenceResolveTest : PklTestCase() {
  fun `test resolve reference`() {
    myFixture.configureByText(
      PklFileType,
      """
      import "pkl:ref"
      
      class Domain extends ref.Domain
      
      class Bird {
        /// The name of the bird
        name: String
      }
      
      r: ref.Reference<Domain, Bird>
      
      res = r.na<caret>me
    """
        .trimIndent(),
    )
    val element = myFixture.getReferenceAtCaretPosition()!!
    val resolved = element.resolve()
    assertThat(resolved).isInstanceOf(PklClassProperty::class.java)
    resolved as PklClassProperty
    assertThat(resolved.name).isEqualTo("name")
  }

  fun `test resolve inferred keyword import name`() {
    myFixture.addFileToProject("module.pkl", "foo = 42")
    myFixture.configureByText(
      PklFileType,
      """
      import "module.pkl"
      foo = `mod<caret>ule`.foo
      """
        .trimIndent(),
    )
    val resolved = myFixture.getReferenceAtCaretPosition()!!.resolve()
    assertThat(resolved).isInstanceOf(PklModule::class.java)
    assertThat(resolved!!.containingFile.name).isEqualTo("module.pkl")
  }

  fun `test resolve member of keyword import`() {
    myFixture.addFileToProject("module.pkl", "foo = 42")
    myFixture.configureByText(
      PklFileType,
      """
      import "module.pkl"
      foo = `module`.fo<caret>o
      """
        .trimIndent(),
    )
    val resolved = myFixture.getReferenceAtCaretPosition()!!.resolve()
    assertThat(resolved).isInstanceOf(PklClassProperty::class.java)
    assertThat(resolved!!.containingFile.name).isEqualTo("module.pkl")
  }

  fun `test resolve type through keyword import name`() {
    myFixture.addFileToProject("module.pkl", "class Bar {}")
    myFixture.configureByText(
      PklFileType,
      """
      import "module.pkl"
      foo: `module`.Ba<caret>r
      """
        .trimIndent(),
    )
    val resolved = myFixture.getReferenceAtCaretPosition()!!.resolve()
    assertThat(resolved).isInstanceOf(PklClass::class.java)
    assertThat(resolved!!.containingFile.name).isEqualTo("module.pkl")
  }

  fun `test resolve inferred hyphenated import name`() {
    myFixture.addFileToProject("my-module.pkl", "foo = 42")
    myFixture.configureByText(
      PklFileType,
      """
      import "my-module.pkl"
      foo = `my-mod<caret>ule`.foo
      """
        .trimIndent(),
    )
    val resolved = myFixture.getReferenceAtCaretPosition()!!.resolve()
    assertThat(resolved).isInstanceOf(PklModule::class.java)
    assertThat(resolved!!.containingFile.name).isEqualTo("my-module.pkl")
  }

  fun `test resolve ordinary inferred import name`() {
    myFixture.addFileToProject("other.pkl", "foo = 42")
    myFixture.configureByText(
      PklFileType,
      """
      import "other.pkl"
      foo = oth<caret>er.foo
      """
        .trimIndent(),
    )
    val resolved = myFixture.getReferenceAtCaretPosition()!!.resolve()
    assertThat(resolved).isInstanceOf(PklModule::class.java)
    assertThat(resolved!!.containingFile.name).isEqualTo("other.pkl")
  }

  fun `test resolve explicit escaped import alias`() {
    myFixture.addFileToProject("other.pkl", "foo = 42")
    myFixture.configureByText(
      PklFileType,
      """
      import "other.pkl" as `module`
      foo = `mod<caret>ule`.foo
      """
        .trimIndent(),
    )
    val resolved = myFixture.getReferenceAtCaretPosition()!!.resolve()
    assertThat(resolved).isInstanceOf(PklModule::class.java)
    assertThat(resolved!!.containingFile.name).isEqualTo("other.pkl")
  }

  fun `test insert colliding keyword import name`() {
    myFixture.addFileToProject("first/module.pkl", "foo = 1")
    val imported = myFixture.addFileToProject("second/module.pkl", "foo = 2") as PklModule
    val module =
      myFixture.configureByText(PklFileType, "import \"first/module.pkl\"\n") as PklModule
    WriteCommandAction.runWriteCommandAction(project) {
      assertThat(module.importList.findOrInsertImport(imported)).isEqualTo("module2")
    }
    assertThat(module.imports.last().identifier!!.text).isEqualTo("module2")
  }

  fun `test insert colliding hyphenated import name`() {
    myFixture.addFileToProject("first/my-module9.pkl", "foo = 1")
    val imported = myFixture.addFileToProject("second/my-module9.pkl", "foo = 2") as PklModule
    val module =
      myFixture.configureByText(PklFileType, "import \"first/my-module9.pkl\"\n") as PklModule
    WriteCommandAction.runWriteCommandAction(project) {
      assertThat(module.importList.findOrInsertImport(imported)).isEqualTo("`my-module10`")
    }
    assertThat(module.imports.last().identifier!!.text).isEqualTo("`my-module10`")
  }

  fun `test quoted reference resolves unquoted property`() {
    myFixture.configureByText(PklFileType, "foo = 1\nres = `fo<caret>o`")
    val resolved = myFixture.getReferenceAtCaretPosition()!!.resolve()
    assertThat(resolved).isInstanceOf(PklClassProperty::class.java)
  }

  fun `test unquoted reference resolves quoted property`() {
    myFixture.configureByText(PklFileType, "`foo` = 1\nres = fo<caret>o")
    val resolved = myFixture.getReferenceAtCaretPosition()!!.resolve()
    assertThat(resolved).isInstanceOf(PklClassProperty::class.java)
  }
}
