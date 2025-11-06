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
package org.pkl.intellij.formatter

import com.intellij.psi.formatter.FormatterTestCase

class PklFormatterTest : FormatterTestCase() {
  fun `test lambdas`() {
    doTextTest(
      """
      foo = (x) -> (x) {
        }
      """
        .trimIndent(),
      """
      foo = (x) -> (x) {}
      
      """
        .trimIndent()
    )
  }

  fun `test basic formatting`() {
    doTextTest(
      """
      module
      foo
      .bar
      
      class Foo{bar=1;baz=2}
      
      typealias
      Bar = Int|*String
      """
        .trimIndent(),
      """
      module foo.bar
      
      class Foo {
        bar = 1
      
        baz = 2
      }
      
      typealias Bar = Int | *String
      
      """
        .trimIndent()
    )
  }

  fun `test advanced formatting`() {
    doTextTest(
      """
      name=""${'"'}
      foo
      
      \(bar)
      baz
      ""${'"'}
      
      foo = bar
        .baz.qux((it) -> new {
        1
        it
        }
        )
      """
        .trimIndent(),
      """
      name =
        ""${'"'}
        foo
      
        \(bar)
        baz
        ""${'"'}
      
      foo =
        bar.baz.qux((it) -> new {
          1
          it
        })
      
      """
        .trimIndent()
    )
  }

  override fun getTestDataPath() = "src/test/resources"

  override fun getBasePath() = "formatter"

  override fun getFileExtension() = "pkl"
}
