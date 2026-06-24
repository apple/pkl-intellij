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

class PklTypeAnnotatorTest {
  private lateinit var codeInsightTestFixture: CodeInsightTestFixture

  private fun checkHighlighting(src: String) {
    codeInsightTestFixture.configureByText("test.pkl", src)
    codeInsightTestFixture.checkHighlighting()
  }

  @Before
  fun before() {
    val fixtureFactory = IdeaTestFixtureFactory.getFixtureFactory()
    val fixture =
      fixtureFactory
        .createLightFixtureBuilder(
          LightProjectDescriptor.EMPTY_PROJECT_DESCRIPTOR,
          "sample pkl project"
        )
        .fixture
    codeInsightTestFixture = fixtureFactory.createCodeInsightFixture(fixture)
    codeInsightTestFixture.setUp()
    PklAnnotator.enabledTestAnnotator = PklTypeAnnotator::class
  }

  @After
  fun after() {
    codeInsightTestFixture.tearDown()
    PklAnnotator.enabledTestAnnotator = null
  }

  @Test
  fun `References -- referent type with type constraint`() {
    checkHighlighting(
      """
      import "pkl:ref"

      class MyDomain extends ref.Domain

      r: <error descr="Reference type annotations may not contain type constraints.">ref.Reference<MyDomain, String(!isEmpty)></error>
    """
        .trimIndent()
    )
  }

  @Test
  fun `Reference -- referent type with type constraint through alias`() {
    checkHighlighting(
      """
      import "pkl:ref"

      class MyDomain extends ref.Domain
      
      typealias MyReference<T> = ref.Reference<MyDomain, T>

      r: <error descr="Reference type annotations may not contain type constraints.">MyReference<String(!isEmpty)></error>
    """
        .trimIndent()
    )
  }
}
