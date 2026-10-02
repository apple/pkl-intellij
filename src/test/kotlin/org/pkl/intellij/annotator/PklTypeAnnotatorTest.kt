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
  fun `this - not allowed in typealias bodies`() {
    checkHighlighting(
      """
       typealias Foo = <error descr="Cannot reference `this` type within a type alias body">this</error>
    """
        .trimIndent()
    )
  }

  @Test
  fun `module - not allowed in typealias bodies`() {
    checkHighlighting(
      """
       typealias Foo = <warning descr="Cannot reference `module` type within a type alias body; this will be an error in a future release">module</warning>
    """
        .trimIndent()
    )
  }

  @Test
  fun `module - not allowed in class bodies`() {
    checkHighlighting(
      """
       class Foo {
         bar: <warning descr="Cannot reference `module` type within a class body; this will be an error in a future release">module</warning>
         // const properties in class should warn for the class, not the const property
         baz: <warning descr="Cannot reference `module` type within a class body; this will be an error in a future release">module</warning>
       }
    """
        .trimIndent()
    )
  }

  @Test
  fun `module - not allowed in annotation bodies`() {
    checkHighlighting(
      """
       @Foo{ bar = baz is <warning descr="Cannot reference `module` type within an annotation body; this will be an error in a future release">module</warning> }
       module test
    """
        .trimIndent()
    )
  }

  @Test
  fun `module - not allowed in const properties`() {
    checkHighlighting(
      """
      const res1 = foo is <warning descr="Cannot reference `module` type from const property `res1`; this will be an error in a future release">module</warning>
      const res2: <warning descr="Cannot reference `module` type from const property `res2`; this will be an error in a future release">module</warning> = foo
      const res3 = new { foo is <warning descr="Cannot reference `module` type from const property `res3`; this will be an error in a future release">module</warning> }
    """
        .trimIndent()
    )
  }

  @Test
  fun `module - not allowed in const methods`() {
    checkHighlighting(
      """
      const function res1() = foo is <warning descr="Cannot reference `module` type from const method `res1`; this will be an error in a future release">module</warning>
      const function res2(): <warning descr="Cannot reference `module` type from const method `res2`; this will be an error in a future release">module</warning> = foo
      const function res3() = new { foo is <warning descr="Cannot reference `module` type from const method `res3`; this will be an error in a future release">module</warning> }
      const function res4(foo: <warning descr="Cannot reference `module` type from const method `res4`; this will be an error in a future release">module</warning>) = null
    """
        .trimIndent()
    )
  }

  @Test
  fun `module - allowed in class extends clause`() {
    checkHighlighting(
      """
       open module test
       class Foo extends module
    """
        .trimIndent()
    )
  }
}
