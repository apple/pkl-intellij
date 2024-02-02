/**
 * Copyright © 2024 Apple Inc. and the Pkl project authors. All rights reserved.
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

import com.intellij.codeInsight.daemon.impl.HighlightInfoType
import com.intellij.codeInsight.daemon.impl.SeveritiesProvider
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.psi.PsiElement
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.pkl.intellij.color.PklColor

internal class PklHighlightingAnnotatorTest {
  private class TestSeverityProvider(private val severities: List<HighlightSeverity>) :
    SeveritiesProvider() {
    override fun getSeveritiesHighlightInfoTypes(): List<HighlightInfoType> =
      severities.map(::TestHighlightingInfoType)
  }

  private class TestHighlightingInfoType(private val severity: HighlightSeverity) :
    HighlightInfoType {
    override fun getAttributesKey(): TextAttributesKey = DEFAULT_TEXT_ATTRIBUTES

    override fun getSeverity(psiElement: PsiElement?): HighlightSeverity = severity

    companion object {
      private val DEFAULT_TEXT_ATTRIBUTES =
        TextAttributesKey.createTextAttributesKey("DEFAULT_TEXT_ATTRIBUTES")
    }
  }

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
    PklAnnotator.enabledTestAnnotator = PklHighlightingAnnotator::class
    val testSeverityProvider = TestSeverityProvider(PklColor.values().map(PklColor::testSeverity))
    SeveritiesProvider.EP_NAME.point.registerExtension(
      testSeverityProvider,
      fixture.testRootDisposable
    )
  }

  @After
  fun after() {
    codeInsightTestFixture.tearDown()
    PklAnnotator.enabledTestAnnotator = null
  }

  @Test
  fun properties() {
    checkHighlighting(
      """
      <PROPERTY>p1</PROPERTY> = 42
      <PROPERTY>p2</PROPERTY> = <PROPERTY>p1</PROPERTY>
      <PROPERTY>p3</PROPERTY> = <PROPERTY>unresolved</PROPERTY>

      <PROPERTY>p4</PROPERTY> {
        <PROPERTY>p5</PROPERTY> = "value"
      }

      <PROPERTY>p6</PROPERTY> = <PROPERTY>p4</PROPERTY>.<PROPERTY>p5</PROPERTY>
      <PROPERTY>p7</PROPERTY> = <PROPERTY>p4</PROPERTY>.<PROPERTY>unresolved</PROPERTY>
      <PROPERTY>p8</PROPERTY> = <PROPERTY>unresolved</PROPERTY>.<PROPERTY>unresolved</PROPERTY>
      
      local <PROPERTY>p9</PROPERTY> = "value"
      local <PROPERTY>p10</PROPERTY> = <PROPERTY>p9</PROPERTY>
    """
        .trimIndent()
    )
  }

  @Test
  fun methods() {
    checkHighlighting(
      """
      function <METHOD>m1</METHOD>(<PARAMETER>n</PARAMETER>) = 
        <METHOD>m1</METHOD>(<PARAMETER>n</PARAMETER> - 1)
        
      <PROPERTY>p1</PROPERTY> = <METHOD>m1</METHOD>(42)
      <PROPERTY>p2</PROPERTY> = module.<METHOD>m1</METHOD>(42)
      <PROPERTY>p3</PROPERTY> = <METHOD>unresolved</METHOD>(42)
      <PROPERTY>p4</PROPERTY> = <PROPERTY>unresolved</PROPERTY>.<METHOD>unresolved</METHOD>(42)
    """
        .trimIndent()
    )
  }

  @Test
  fun classes() {
    checkHighlighting(
      """
      open class <CLASS>Person</CLASS>
      class <CLASS>Teacher</CLASS> extends <CLASS>Person</CLASS>
      
      <PROPERTY>p1</PROPERTY>: <CLASS>Int</CLASS>
      <PROPERTY>p2</PROPERTY>: <CLASS>Person</CLASS>
      <PROPERTY>p3</PROPERTY>: <CLASS>List</CLASS><<CLASS>Teacher</CLASS>>
      <PROPERTY>p4</PROPERTY>: <CLASS>Unresolved</CLASS>
    """
        .trimIndent()
    )
  }

  @Test
  fun typeAliases() {
    checkHighlighting(
      """
      typealias <TYPE_ALIAS>Integer</TYPE_ALIAS> = <CLASS>Int</CLASS>
      typealias <TYPE_ALIAS>Positive</TYPE_ALIAS> = 
        <TYPE_ALIAS>Integer</TYPE_ALIAS>(<PROPERTY>isPositive</PROPERTY>)
      
      <PROPERTY>p1</PROPERTY>: <TYPE_ALIAS>Int32</TYPE_ALIAS>
      <PROPERTY>p2</PROPERTY>: <TYPE_ALIAS>Integer</TYPE_ALIAS>
      <PROPERTY>p3</PROPERTY>: <CLASS>List</CLASS><<TYPE_ALIAS>Positive</TYPE_ALIAS>>
    """
        .trimIndent()
    )
  }

  @Test
  fun modules() {
    checkHighlighting(
      """
      import "pkl:xml"
      import "pkl:xml" as <MODULE>other</MODULE>
      
      // type use is highlighted as CLASS rather than MODULE
      <PROPERTY>p1</PROPERTY>: <CLASS>xml</CLASS> = <MODULE>xml</MODULE>
      
      <PROPERTY>p2</PROPERTY>: <MODULE>xml</MODULE>.<CLASS>Renderer</CLASS> = 
        new <MODULE>other</MODULE>.<CLASS>Renderer</CLASS> {}
        
        <PROPERTY>p3</PROPERTY>: <MODULE>unresolved</MODULE>.<CLASS>Unresolved</CLASS> = 
        new <MODULE>unresolved</MODULE>.<CLASS>Unresolved</CLASS> {}
    """
        .trimIndent()
    )
  }

  @Test
  fun annotations() {
    checkHighlighting(
      """
      import "pkl:base"
      
      // annotation class name is currently highlighted as CLASS rather than ANNOTATION
      class <CLASS>MyAnn</CLASS> extends <CLASS>Annotation</CLASS>
      
      <ANNOTATION>@</ANNOTATION><ANNOTATION>Deprecated</ANNOTATION>
      <PROPERTY>p1</PROPERTY> = 42
      
      // "base" is highlighted as ANNOTATION rather than MODULE (cf. Kotlin)
      <ANNOTATION>@</ANNOTATION><ANNOTATION>base</ANNOTATION>.<ANNOTATION>Deprecated</ANNOTATION>
      <PROPERTY>p2</PROPERTY> = 42
      
      <ANNOTATION>@</ANNOTATION><ANNOTATION>MyAnn</ANNOTATION>
      <PROPERTY>p3</PROPERTY> = 42
      
      <ANNOTATION>@</ANNOTATION><ANNOTATION>Unresolved</ANNOTATION>
      <PROPERTY>p4</PROPERTY> = 42
    """
        .trimIndent()
    )
  }

  @Test
  fun localVariables() {
    checkHighlighting(
      """
      <PROPERTY>p1</PROPERTY> = 
        let (<LOCAL_VARIABLE>x</LOCAL_VARIABLE> = 42) 
          3 * <LOCAL_VARIABLE>x</LOCAL_VARIABLE>
      <PROPERTY>p2</PROPERTY> {
        for (<LOCAL_VARIABLE>x</LOCAL_VARIABLE>, <LOCAL_VARIABLE>y</LOCAL_VARIABLE> in <PROPERTY>z</PROPERTY>) {
          <PROPERTY>p3</PROPERTY> = <LOCAL_VARIABLE>x</LOCAL_VARIABLE>
          <PROPERTY>p4</PROPERTY> = <LOCAL_VARIABLE>y</LOCAL_VARIABLE>
        }
      }
    """
        .trimIndent()
    )
  }
}
