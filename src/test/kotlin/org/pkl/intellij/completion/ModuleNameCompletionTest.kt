/**
 * Copyright © 2024-2026 Apple Inc. and the Pkl project authors. All rights reserved.
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

import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.pkl.intellij.PklTestCase

class ModuleNameCompletionTest : PklTestCase() {
  override fun setUp() {
    super.setUp()
    syncProjects()
  }

  override val fixtureDir: Path = Path.of("src/test/resources/completion")

  fun `test completion of module names from filename only, from scratch`() {
    ModuleNameCompletionProvider.allowSourceRootCompletion = false
    myFixture.configureByFiles("module-name/filename-only-scratch.pkl")
    val completions = myFixture.completeBasic()
    assertThat(completions).hasSize(1)
    assertThat(completions[0].lookupString).isEqualTo("`filename-only-scratch`")
  }

  fun `test completion of module names from filename only, with prefix match`() {
    ModuleNameCompletionProvider.allowSourceRootCompletion = false
    myFixture.configureByFiles("module-name/filename-only-prefix.pkl")
    val completions = myFixture.completeBasic()
    assertThat(completions).hasSize(1)
    assertThat(completions[0].lookupString).isEqualTo("`filename-only-prefix`")
  }

  fun `test completion of module names from filename only, with no prefix match`() {
    ModuleNameCompletionProvider.allowSourceRootCompletion = false
    myFixture.configureByFiles("module-name/filename-only-noprefix.pkl")
    val completions = myFixture.completeBasic()
    assertThat(completions).isEmpty()
  }

  fun `test completion of module names from package, from scratch`() {
    ModuleNameCompletionProvider.allowSourceRootCompletion = false
    myFixture.configureByFiles("module-name/project/baz/from-scratch.pkl")
    val completions = myFixture.completeBasic()
    assertThat(completions).hasSize(1)
    assertThat(completions[0].lookupString).isEqualTo("foo.bar.baz.`from-scratch`")
  }

  fun `test completion of module names from package, with prefix match`() {
    ModuleNameCompletionProvider.allowSourceRootCompletion = false
    myFixture.configureByFiles("module-name/project/baz/prefix.pkl")
    val completions = myFixture.completeBasic()
    assertThat(completions).isNull()
    assertThat(myFixture.editor.document.text).startsWith("module foo.bar.baz.prefix")
  }

  fun `test completion of module names from package, with no prefix match`() {
    ModuleNameCompletionProvider.allowSourceRootCompletion = false
    myFixture.configureByFiles("module-name/project/baz/noprefix.pkl")
    val completions = myFixture.completeBasic()
    assertThat(completions).isEmpty()
  }

  fun `test completion of module names from package with partial project name and from scratch`() {
    ModuleNameCompletionProvider.allowSourceRootCompletion = false
    myFixture.configureByFiles("module-name/project/baz/partial-project-name.pkl")
    val completions = myFixture.completeBasic()
    assertThat(completions).hasSize(1)
    assertThat(completions[0].lookupString).isEqualTo("bar.baz.`partial-project-name`")
  }

  fun `test completion of module names from package with partial project name and prefix match`() {
    ModuleNameCompletionProvider.allowSourceRootCompletion = false
    myFixture.configureByFiles("module-name/project/baz/partial-project-name-prefix.pkl")
    val completions = myFixture.completeBasic()
    assertThat(completions).isNull()
    assertThat(myFixture.editor.document.text)
      .startsWith("module foo.bar.baz.`partial-project-name-prefix`")
  }

  fun `test completion of module names from package with partial project name and no prefix match`() {
    ModuleNameCompletionProvider.allowSourceRootCompletion = false
    myFixture.configureByFiles("module-name/project/baz/partial-project-name-noprefix.pkl")
    val completions = myFixture.completeBasic()
    assertThat(completions).isEmpty()
  }

  fun `test completion of module names from package with partial file path and from scratch`() {
    ModuleNameCompletionProvider.allowSourceRootCompletion = false
    myFixture.configureByFiles("module-name/project/baz/partial-filename.pkl")
    val completions = myFixture.completeBasic()
    assertThat(completions).hasSize(1)
    assertThat(completions[0].lookupString).isEqualTo("`partial-filename`")
  }

  fun `test completion of module names from package with partial file path and prefix match`() {
    ModuleNameCompletionProvider.allowSourceRootCompletion = false
    myFixture.configureByFiles("module-name/project/baz/partial-filename-prefix.pkl")
    val completions = myFixture.completeBasic()
    assertThat(completions).hasSize(1)
    assertThat(completions[0].lookupString).isEqualTo("`partial-filename-prefix`")
  }

  fun `test completion of module names from package with partial file path and no prefix match`() {
    ModuleNameCompletionProvider.allowSourceRootCompletion = false
    myFixture.configureByFiles("module-name/project/baz/partial-filename-noprefix.pkl")
    val completions = myFixture.completeBasic()
    assertThat(completions).isEmpty()
  }

  fun `test completion of module names from sourceroot from scratch`() {
    ModuleNameCompletionProvider.allowSourceRootCompletion = true
    myFixture.configureByFiles("module-name/sourceroot/foo/bar/baz/from-scratch.pkl")
    val completions = myFixture.completeBasic()
    assertThat(completions).hasSize(1)
    assertThat(completions[0].lookupString)
      .isEqualTo("`module-name`.sourceroot.foo.bar.baz.`from-scratch`")
  }

  fun `test completion of module names from sourceroot with prefix match`() {
    ModuleNameCompletionProvider.allowSourceRootCompletion = true
    myFixture.configureByFiles("module-name/sourceroot/foo/bar/baz/prefix.pkl")
    val completions = myFixture.completeBasic()
    assertThat(completions).hasSize(1)
    assertThat(completions[0].lookupString).isEqualTo("`module-name`.sourceroot.foo.bar.baz.prefix")
  }

  fun `test completion of module names from sourceroot with no prefix match`() {
    ModuleNameCompletionProvider.allowSourceRootCompletion = true
    myFixture.configureByFiles("module-name/sourceroot/foo/bar/baz/noprefix.pkl")
    val completions = myFixture.completeBasic()
    assertThat(completions).isEmpty()
  }

  fun `test completion of module names from sourceroot with partial file path and from scratch`() {
    ModuleNameCompletionProvider.allowSourceRootCompletion = true
    myFixture.configureByFiles("module-name/sourceroot/foo/bar/baz/partial-filename.pkl")
    val completions = myFixture.completeBasic()
    assertThat(completions).hasSize(1)
    assertThat(completions[0].lookupString).isEqualTo("baz.`partial-filename`")
  }

  fun `test completion of module names from sourceroot with partial file path and prefix match`() {
    ModuleNameCompletionProvider.allowSourceRootCompletion = true
    myFixture.configureByFiles("module-name/sourceroot/foo/bar/baz/partial-filename-prefix.pkl")
    val completions = myFixture.completeBasic()
    assertThat(completions).isNull()
    assertThat(myFixture.editor.document.text)
      .startsWith("module `module-name`.sourceroot.foo.bar.baz.`partial-filename-prefix`")
  }

  fun `test completion of module names from sourceroot with partial file path and no prefix match`() {
    ModuleNameCompletionProvider.allowSourceRootCompletion = true
    myFixture.configureByFiles("module-name/sourceroot/foo/bar/baz/partial-filename-noprefix.pkl")
    val completions = myFixture.completeBasic()
    assertThat(completions).isEmpty()
  }
}
