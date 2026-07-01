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

import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import org.junit.After
import org.junit.Before
import org.junit.Test

class PklDocCommentAnnotatorTest {
  private lateinit var fixture: CodeInsightTestFixture

  private fun checkHighlighting(src: String) {
    fixture.configureByText("test.pkl", src.trimIndent())
    fixture.checkHighlighting()
  }

  @Before
  fun before() {
    val factory = IdeaTestFixtureFactory.getFixtureFactory()
    val base =
      factory
        .createLightFixtureBuilder(
          LightProjectDescriptor.EMPTY_PROJECT_DESCRIPTOR,
          "sample pkl project"
        )
        .fixture
    fixture = factory.createCodeInsightFixture(base)
    fixture.setUp()
    PklAnnotator.enabledTestAnnotator = PklDocCommentAnnotator::class
  }

  @After
  fun after() {
    fixture.tearDown()
    PklAnnotator.enabledTestAnnotator = null
  }

  @Test
  fun `diagnostic for unresolved member link`() {
    checkHighlighting(
      """
      /// This is a [<warning descr="Unresolved reference: bar">bar</warning>]
      foo: String
    """
        .trimIndent()
    )
  }

  @Test
  fun `no diagnostic for resolved member link`() {
    checkHighlighting(
      """
      /// This is a [foo]
      foo: String
    """
        .trimIndent()
    )
  }

  @Test
  fun `no diagnostic for keywords`() {
    checkHighlighting(
      """
      /// This is never [null]
      foo: String
    """
        .trimIndent()
    )
  }
}
