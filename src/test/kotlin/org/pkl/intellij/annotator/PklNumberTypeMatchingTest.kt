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
package org.pkl.intellij.annotator

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class PklNumberTypeMatchingTest {
  private lateinit var codeInsightTestFixture: CodeInsightTestFixture

  @Before
  fun before() {
    val fixtureFactory = IdeaTestFixtureFactory.getFixtureFactory()
    val fixture =
      fixtureFactory
        .createLightFixtureBuilder(
          LightProjectDescriptor.EMPTY_PROJECT_DESCRIPTOR,
          "sample pkl project",
        )
        .fixture
    codeInsightTestFixture = fixtureFactory.createCodeInsightFixture(fixture)
    codeInsightTestFixture.setUp()
    PklAnnotator.enabledTestAnnotator = PklExprAnnotator::class
  }

  @After
  fun after() {
    codeInsightTestFixture.tearDown()
    PklAnnotator.enabledTestAnnotator = null
  }

  private fun checkHighlighting(src: String) {
    codeInsightTestFixture.configureByText("test.pkl", src.trimIndent())
    codeInsightTestFixture.checkHighlighting()
  }

  @Test
  fun `Number matches both orders of numeric union`() {
    checkHighlighting(
      """
      bar: Number = 1
      foo: Int|Float = bar
      reversed: Float|Int = bar
      same: Number = bar
      any: Any = bar
      """
    )
  }

  @Test
  fun `Number and union aliases match`() {
    checkHighlighting(
      """
      typealias Numeric = Number
      typealias NumericUnion = Int|Float
      bar: Numeric = 1.5
      foo: NumericUnion = bar
      """
    )
  }

  @Test
  fun `Nullable Number matches nullable numeric union`() {
    checkHighlighting(
      """
      bar: Number? = null
      foo: (Int|Float)? = bar
      """
    )
  }

  @Test
  fun `Number still warns for an incomplete numeric union`() {
    checkHighlighting(
      """
      bar: Number = 1
      foo: Int|String = <warning descr="Type mismatch. Required: Int | String Found: Number">bar</warning>
      integer: Int = <warning descr="Type mismatch. Required: Int Found: Number">bar</warning>
      floating: Float = <warning descr="Type mismatch. Required: Float Found: Number">bar</warning>
      """
    )
  }

  @Test
  fun `Non numeric values still fail numeric union matching`() {
    checkHighlighting(
      """
      bar: String = "text"
      foo: Int|Float = <error descr="Type mismatch. Required: Int | Float Found: String">bar</error>
      """
    )
  }

  @Test
  fun `Numeric union constraints are still checked`() {
    checkHighlighting(
      """
      bar: Number = 1
      foo: Int(this > 0)|Float = bar
      """
    )
    codeInsightTestFixture.configureByText(
      "test.pkl",
      "bar: Number = 1\nfoo: Int(false)|Float = bar",
    )
    val errors = codeInsightTestFixture.doHighlighting(HighlightSeverity.ERROR)
    assertEquals(1, errors.size)
    assertEquals(
      "bar",
      codeInsightTestFixture.file.text.substring(errors[0].startOffset, errors[0].endOffset),
    )
    assertEquals("Constraint violation.\nRequired: false\nFound: <Error>\n", errors[0].description)
  }
}
