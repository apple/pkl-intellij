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
package org.pkl.intellij.parser

import com.intellij.testFramework.ParsingTestCase
import org.junit.Ignore
import org.pkl.intellij.PklParserDefinition

@Ignore
class PklParserRecoveryTest : ParsingTestCase("", "pkl", PklParserDefinition()) {
  override fun skipSpaces(): Boolean {
    return false
  }

  override fun includeRanges(): Boolean {
    return true
  }

  override fun getTestDataPath(): String {
    return "src/test/resources/parser"
  }

  fun testModuleDeclarationReco1() {
    doCodeTest("123 module X")
  }

  fun testModuleDeclarationReco1a() {
    doCodeTest("if module X\n" + "import \"xx\"")
  }

  fun testModuleDeclarationReco1b() {
    doCodeTest("as module X\n" + "open class foo")
  }

  fun testAnnotationReco1() {
    doCodeTest("@A{foo=}class Foo{}")
  }

  fun testAnnotationReco2() {
    doCodeTest("@A{foo=2 bar=}class Foo{}")
  }

  fun testListReco1() {
    doCodeTest("@A{foo [1,] bar=3}module X")
  }

  fun testListReco2() {
    doCodeTest("@A{foo [,] bar=3}module X")
  }

  fun testMapReco1() {
    doCodeTest("@A{local foo [:2] bar=[1:] baz=3}class C")
  }

  fun testPropertyDefinitionReco1() {
    doCodeTest(
      "class Properties {\n" +
        "p1 23 p2 \n" +
        "p3:\"ak\" local p4 \n" +
        "p5 13\n" +
        "/// docs\n" +
        "p6 \n" +
        "p7. @A p8\n" +
        "p9\n" +
        "function foo()\n" +
        "}\n" +
        "class Foo{}"
    )
  }

  fun testContainerReco() {
    doCodeTest("function foo() = {\"xx\" foo}")
  }
}
