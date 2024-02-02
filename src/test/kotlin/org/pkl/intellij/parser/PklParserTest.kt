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
import org.pkl.intellij.PklParserDefinition

class PklParserTest : ParsingTestCase("", "pkl", PklParserDefinition()) {
  override fun skipSpaces(): Boolean {
    return false
  }

  override fun includeRanges(): Boolean {
    return true
  }

  override fun getTestDataPath(): String {
    return "src/test/resources/parser"
  }

  //    public void testCompleteSyntax() {
  //        doTest(true);
  //    }
  //////////////////////////////////////////////////////////////////////
  // Module declaration
  fun testModuleDeclaration1() {
    doCodeTest("module X")
  }

  fun testModuleDeclaration2() {
    doCodeTest("amends \"other.pkl\"")
  }

  fun testModuleDeclaration3() {
    doCodeTest("/// docs\nmodule X")
  }

  fun testModuleDeclaration4() {
    doCodeTest("extends \"module.uri\"")
  }

  fun testModuleDeclaration5() {
    doCodeTest("module a.b.c\n" + "extends \"module.uri\"")
  }

  fun testModuleDeclaration6() {
    doCodeTest("\t/// line1\n" + "\t///line2\n" + "open module a.b.c")
  }

  fun testModuleDeclaration7() {
    doCodeTest("\t/// line1\n" + "amends \"other.pkl\"")
  }

  fun testModuleDeclaration8() {
    doCodeTest("/// line1\n" + "@A.B\n" + "module x")
  }

  fun testModuleDeclaration9() {
    doCodeTest("@A.B{prop1 = \"ak\"}" + "module x")
  }

  //////////////////////////////////////////////////////////////////////
  // Method Definition
  fun testMethodDefinition1() {
    doCodeTest("external function readme(uri: String): Object")
  }

  fun testMethodDefinition2() {
    doCodeTest("function readme(uri: String)")
  }

  fun testMethodDefinition3() {
    doCodeTest("function bar1() = 42")
  }

  fun testMethodDefinition4() {
    doCodeTest("\t///pkldoc\n" + "function foo(p1:Int,p2:Int): Object")
  }

  fun testMethodDefinition5() {
    doCodeTest("function foo<A,B>(a: A): B")
  }

  fun testMethodDefinition6() {
    doCodeTest("\t///pkldoc\n" + "@A{x=42}" + "function readme(uri: String): Object")
  }

  fun testMethodDefinition7() {
    doCodeTest("@A{x=42} " + "@B{p{\"a\";\"b\"}}" + "function foo()")
  }

  fun testMethodDefinition8() {
    doCodeTest("function foo(a,b)")
  }

  //////////////////////////////////////////////////////////////////////
  // Property Definition
  fun testPropertyDefinition1() {
    doCodeTest("bat = (a,b) -> foo(a,b)")
  }

  //////////////////////////////////////////////////////////////////////
  // Class Definition
  fun testClassDefinition1() {
    doCodeTest("abstract class Foo")
  }

  fun testClassDefinition2() {
    doCodeTest(
      "external MinInt: Int\n" + "/// Base class for modules.\n" + "abstract external class Module"
    )
  }

  fun testClassDefinitionWithPropAmendObject() {
    doCodeTest("class Foo {" + "p: Type = Type {}" + "}")
  }

  fun testImportExpression1() {
    doCodeTest("function TODO() = (import(\"x.pkl\") { foo=\"abc\"}).print()")
  }

  fun testMemberPredicate() {
    doCodeTest(
      """
       res1 { [[a]] {} }
       res2 { [[a > 3]] {} }
       res3 { [[a.isEven]] {} }
     """
        .trimIndent()
    )
  }

  fun testMemberPredicateEndingInSubscriptExpression() {
    doCodeTest(
      """
       res1 { [[a[b]]] {} }
       res2 { [[a[b[c]]]] {} }
       res3 { [[x + a[x + b[x + c]]]] {} }
     """
        .trimIndent()
    )
  }

  fun testSubscriptExpression() {
    doCodeTest(
      """
       res1 = a[b]
       res2 = a[b + c]
       res3 = a[b.c(d)]
       res4 = a[b[c]]
       res5 = a[x + b[x + c[x + d]]]
     """
        .trimIndent()
    )
  }

  fun testDuration() {
    doCodeTest(
      """
      res1 = 5.min
      res2 = 0.5.min
      res3 = 0.500_000.min
      res4 = 1_000.h
    """
        .trimIndent()
    )
  }

  fun testAmendsChain() {
    doCodeTest(
      """
      foo {
        bar = 0
      } {
        bar = 1
      }
    """
        .trimIndent()
    )
  }

  fun testGlobs() {
    doCodeTest(
      """
      import* "*.pkl" as myGlob
      
      res1 = import*("*.pkl")
      res2 = read*("*.txt")
    """
        .trimIndent()
    )
  }

  fun testDefaultUnionTypes() {
    doCodeTest(
      """
      typealias Foo = "bar"|*"foo"|Int
      
      foo: *Foo|"bar"
    """
        .trimIndent()
    )
  }
}
