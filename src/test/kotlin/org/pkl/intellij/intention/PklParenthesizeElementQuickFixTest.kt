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
package org.pkl.intellij.intention

import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.pkl.intellij.annotator.PklAnnotator
import org.pkl.intellij.annotator.PklExprAnnotator

class PklParenthesizeElementQuickFixTest {
  private lateinit var fixture: CodeInsightTestFixture

  @Before
  fun before() {
    val factory = IdeaTestFixtureFactory.getFixtureFactory()
    val base =
      factory
        .createLightFixtureBuilder(
          LightProjectDescriptor.EMPTY_PROJECT_DESCRIPTOR,
          "sample pkl project",
        )
        .fixture
    fixture = factory.createCodeInsightFixture(base)
    fixture.setUp()
    PklAnnotator.enabledTestAnnotator = PklExprAnnotator::class
  }

  @After
  fun after() {
    fixture.tearDown()
    PklAnnotator.enabledTestAnnotator = null
  }

  private fun applyFix(source: String, expected: String) {
    fixture.configureByText("test.pkl", source.trimIndent())
    val fixes = fixture.getAllQuickFixes()
    val fix =
      fixes.firstOrNull { it.familyName == "Wrap in parentheses" }
        ?: throw AssertionError("Expected to find a quickfix, but did not find any")
    fixture.launchAction(fix)
    fixture.checkResult(expected.trimIndent())
  }

  @Test
  fun `wraps unqualified property access`() {
    applyFix(
      """
      foo = 1
      bar = 2
      baz = 3
      res = fo<caret>o < bar > (baz)
      """,
      """
      foo = 1
      bar = 2
      baz = 3
      res = (foo) < bar > (baz)
      """,
    )
  }

  @Test
  fun `wraps qualified property access including receiver`() {
    applyFix(
      """
      foo = 1
      bar = 2
      baz = 3
      res = module.fo<caret>o < bar > (baz)
      """,
      """
      foo = 1
      bar = 2
      baz = 3
      res = (module.foo) < bar > (baz)
      """,
    )
  }

  @Test
  fun `wraps super property access including super keyword`() {
    applyFix(
      """
      open class A { x = 1 }
      bar = 2
      baz = 3
      class B extends A { y = super.<caret>x < bar > (baz) }
      """,
      """
      open class A { x = 1 }
      bar = 2
      baz = 3
      class B extends A { y = (super.x) < bar > (baz) }
      """,
    )
  }

  @Test
  fun `no fix offered when member does not exist`() {
    fixture.configureByText("test.pkl", "res = no<caret>pe<String>(1)")
    assertThat(fixture.getAllQuickFixes().filter { it.familyName == "Wrap in parentheses" })
      .isEmpty()
  }
}
