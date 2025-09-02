/**
 * Copyright © 2024-2025 Apple Inc. and the Pkl project authors. All rights reserved.
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
import org.pkl.intellij.PklFileType
import org.pkl.intellij.PklTestCase
import org.pkl.intellij.packages.dto.PackageUri
import org.pkl.intellij.packages.pklPackageService

class CompletionTest : PklTestCase() {
  fun `test fake`() {
    require(false) { "This is a fake method ignore me" }
  }

  fun `test complete from lexical scope`() {
    myFixture.configureByText(
      PklFileType,
      """
      bar: Int = 1

      foo = bar.<caret>
    """
        .trimIndent()
    )
    myFixture.completeBasic()
    val lookupStrings = myFixture.lookupElementStrings
    assertThat(lookupStrings).contains("ms", "ns", "d", "h", "min")
  }

  fun `test complete with inferred type on local member`() {
    myFixture.configureByText(
      PklFileType,
      """
      local bar = 1
      
      foo = bar.<caret>
    """
        .trimIndent()
    )
    myFixture.completeBasic()
    val lookupStrings = myFixture.lookupElementStrings
    assertThat(lookupStrings).contains("ms", "ns", "d", "h", "min")
  }

  fun `test complete from implicit this`() {
    myFixture.configureByText(
      PklFileType,
      """
      class Person {
        firstName: String
        lastName: String
      }
      
      person: Person = new {
        firstName = <caret>
      }
    """
        .trimIndent()
    )
    myFixture.completeBasic()
    val lookupStrings = myFixture.lookupElementStrings
    assertThat(lookupStrings).contains("lastName")
  }

  fun `test complete from implicit this in amendin module`() {
    myFixture.configureByFiles("basic/child.pkl", "basic/Parent.pkl")
    myFixture.completeBasic()
    val lookupStrings = myFixture.lookupElementStrings
    assertThat(lookupStrings).contains("prop1", "prop2")
  }

  fun `test complete from amending package`() {
    myFixture.project.pklPackageService
      .downloadPackage(
        PackageUri.create("package://pkg.pkl-lang.org/pkl-pantry/k8s.contrib.appEnvCluster@1.0.1")!!
      )
      .get()
    myFixture.configureByText(
      PklFileType,
      """
      amends "package://pkg.pkl-lang.org/pkl-pantry/k8s.contrib.appEnvCluster@1.0.1#/AppEnvCluster.pkl"
      
      <caret>
    """
        .trimIndent()
    )
    myFixture.completeBasic()
    val lookupStrings = myFixture.lookupElementStrings
    assertThat(lookupStrings).contains("configMaps = ")
  }

  override val fixtureDir: Path?
    get() = Path.of("src/test/resources/completion")
}
