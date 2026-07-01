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
package org.pkl.intellij.completion

import org.assertj.core.api.Assertions.assertThat
import org.pkl.intellij.PklFileType
import org.pkl.intellij.PklTestCase
import org.pkl.intellij.documentation.PkldocLinkGeneratingProvider

class DocCommentMemberLinkCompletionTest : PklTestCase() {
  fun `test complete unqualified member link`() {
    myFixture.configureByText(
      PklFileType,
      """
      /// This is a [fo<caret>]
      bar: String

      foo: Int

      function foo(): String
      """
        .trimIndent()
    )
    myFixture.completeBasic()
    val lookupStrings = myFixture.lookupElementStrings
    assertThat(lookupStrings).contains("foo")
  }

  fun `test complete unqualified member link -- function parameters`() {
    myFixture.configureByText(
      PklFileType,
      """
      /// This is a [fo<caret>]
      function myFunc(foo: String, fooey: String) = "foo"
      """
        .trimIndent()
    )
    myFixture.completeBasic()
    val lookupStrings = myFixture.lookupElementStrings
    assertThat(lookupStrings).contains("foo", "fooey")
  }

  fun `test unqualified member link contains keywords`() {
    myFixture.configureByText(
      PklFileType,
      """
      /// This is a [<caret>]
      bar: String

      foo: Int

      function foo(): String
      """
        .trimIndent()
    )
    myFixture.completeBasic()
    val lookupStrings = myFixture.lookupElementStrings
    assertThat(lookupStrings).containsAll(PkldocLinkGeneratingProvider.keywords)
  }

  fun `test complete qualified member link`() {
    myFixture.configureByText(
      PklFileType,
      """
      /// This is a [Foo.<caret>]
      bar: String

      class Foo {
        bar: Int
        baz: Int
      }
      """
        .trimIndent()
    )
    myFixture.completeBasic()
    val lookupStrings = myFixture.lookupElementStrings
    assertThat(lookupStrings).contains("bar", "baz")
  }
}
