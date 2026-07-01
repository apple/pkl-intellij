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

import com.intellij.codeInsight.intention.impl.preview.IntentionPreviewPopupUpdateProcessor
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.openapi.application.ReadAction
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.pkl.intellij.PklUnusedLocalDefinitionsInspection

class PklRemoveImportQuickFixTest {
  private lateinit var fixture: CodeInsightTestFixture

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
    fixture.enableInspections(PklUnusedLocalDefinitionsInspection())
  }

  @After
  fun after() {
    fixture.tearDown()
  }

  // `math` and `yaml` are used; `json` and `xml` are unused
  private val source =
    """
    import "pkl:json"
    import "pkl:math"
    import "pkl:xml"
    import "pkl:yaml"

    a = math
    b = yaml
    """
      .trimIndent()

  @Test
  fun `preview lists every unused import that will be removed`() {
    fixture.configureByText("test.pkl", source)
    val fix = fixture.getAllQuickFixes().first { it.text == "Remove unused imports" }
    val info =
      ReadAction.compute<IntentionPreviewInfo, RuntimeException> {
        IntentionPreviewPopupUpdateProcessor.getPreviewInfo(
          fixture.project,
          fix,
          fixture.file,
          fixture.editor,
          0
        )
      }
    val content = (info as IntentionPreviewInfo.Html).content().toString()
    assertThat(content).contains("pkl:json")
    assertThat(content).contains("pkl:xml")
    assertThat(content).doesNotContain("pkl:math")
    assertThat(content).doesNotContain("pkl:yaml")
  }

  @Test
  fun `preview escapes html in import uris`() {
    fixture.configureByText("test.pkl", """import "a<b>&c"""")
    val fix = fixture.getAllQuickFixes().first { it.text == "Remove unused imports" }
    val info =
      ReadAction.compute<IntentionPreviewInfo, RuntimeException> {
        IntentionPreviewPopupUpdateProcessor.getPreviewInfo(
          fixture.project,
          fix,
          fixture.file,
          fixture.editor,
          0
        )
      }
    val content = (info as IntentionPreviewInfo.Html).content().toString()
    assertThat(content).doesNotContain("<b>")
    assertThat(content).contains("&lt;b&gt;")
  }

  @Test
  fun `apply removes all unused imports`() {
    fixture.configureByText("test.pkl", source)
    val fix = fixture.getAllQuickFixes().first { it.text == "Remove unused imports" }
    fixture.launchAction(fix)
    fixture.checkResult(
      """
      import "pkl:math"
      import "pkl:yaml"

      a = math
      b = yaml
      """
        .trimIndent()
    )
  }
}
