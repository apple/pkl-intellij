/**
 * Copyright © 2024-2026 Apple Inc. and the Pkl project authors. All rights reserved.
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
package org.pkl.intellij.completion

import com.intellij.codeInsight.lookup.LookupElementPresentation
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.pkl.intellij.PklFileType
import org.pkl.intellij.PklTestCase
import org.pkl.intellij.packages.dto.PackageUri
import org.pkl.intellij.packages.pklPackageService

class CompletionTest : PklTestCase() {
  private fun lookupPresentableStrings(): List<String> {
    val presentation = LookupElementPresentation()
    return myFixture.lookupElements?.map {
      it.renderElement(presentation)
      presentation.itemText ?: ""
    }
      ?: emptyList()
  }

  fun `test complete from lexical scope`() {
    myFixture.configureByText(
      PklFileType,
      """
      bar: Int = 1

      foo = bar.<caret>
    """
        .trimIndent()
    )
    myFixture.completeBasic()
    val lookupStrings = myFixture.lookupElementStrings
    assertThat(lookupStrings).contains("ms", "ns", "d", "h", "min")
  }

  fun `test complete with inferred type on local member`() {
    myFixture.configureByText(
      PklFileType,
      """
      local bar = 1
      
      foo = bar.<caret>
    """
        .trimIndent()
    )
    myFixture.completeBasic()
    val lookupStrings = myFixture.lookupElementStrings
    assertThat(lookupStrings).contains("ms", "ns", "d", "h", "min")
  }

  fun `test complete from implicit this`() {
    myFixture.configureByText(
      PklFileType,
      """
      class Person {
        firstName: String
        lastName: String
      }
      
      person: Person = new {
        firstName = <caret>
      }
    """
        .trimIndent()
    )
    myFixture.completeBasic()
    val lookupStrings = myFixture.lookupElementStrings
    assertThat(lookupStrings).contains("lastName")
  }

  fun `test complete from implicit this in amendin module`() {
    myFixture.configureByFiles("basic/child.pkl", "basic/Parent.pkl")
    myFixture.completeBasic()
    val lookupStrings = myFixture.lookupElementStrings
    assertThat(lookupStrings).contains("prop1", "prop2")
  }

  fun `test complete from amending package`() {
    myFixture.project.pklPackageService
      .downloadPackage(
        PackageUri.create("package://pkg.pkl-lang.org/pkl-pantry/k8s.contrib.appEnvCluster@1.0.1")!!
      )
      .get()
    myFixture.configureByText(
      PklFileType,
      """
      amends "package://pkg.pkl-lang.org/pkl-pantry/k8s.contrib.appEnvCluster@1.0.1#/AppEnvCluster.pkl"
      
      <caret>
    """
        .trimIndent()
    )
    myFixture.completeBasic()
    val lookupStrings = myFixture.lookupElementStrings
    assertThat(lookupStrings).contains("configMaps = ")
  }

  fun `test BaseValueRenderer convertPropertyTransformers entry value object body this resolution`() {
    myFixture.configureByText(
      PklFileType,
      """
        /// this is foo
        class Foo extends ConvertProperty { 
          /// this is bar
          bar: String = "bar"
          baz: String
          render = (_, _) -> Pair(bar, bar) 
        }

        output {
          renderer {
            convertPropertyTransformers {
              [Foo] { b<caret> }
            }
          }
        }
        """
        .trimIndent()
    )
    myFixture.completeBasic()
    val lookupStrings = myFixture.lookupElementStrings
    assertThat(lookupStrings).contains("bar = ")
  }

  fun `test BaseValueRenderer convertPropertyTransformers entry value assign lambda amend this resolution`() {
    myFixture.configureByText(
      PklFileType,
      """
        /// this is foo
        class Foo extends ConvertProperty { 
          /// this is bar
          bar: String = "bar"
          baz: String
          render = (_, _) -> Pair(bar, bar) 
        }

        output {
          renderer {
            convertPropertyTransformers {
              [Foo] = (it) -> (it) { b<caret> }
            }
          }
        }
        """
        .trimIndent()
    )
    myFixture.completeBasic()
    val lookupStrings = myFixture.lookupElementStrings
    assertThat(lookupStrings).contains("bar = ")
  }

  fun `test complete implement member`() {
    myFixture.configureByText(
      PklFileType,
      """
        abstract class Base {
          abstract function someMethod(elems: Listing<String>)
        }

        class MyClass extends Base {
          function some<caret>
        }
        """
        .trimIndent()
    )
    myFixture.completeBasic()
    assertThat(myFixture.editor.document.text)
      .contains("function someMethod(elems: Listing<String>) = TODO()")
  }

  fun `test complete implement member shows all unimplemented methods`() {
    myFixture.configureByText(
      PklFileType,
      """
        abstract class Base {
          abstract function method1(): String
          abstract function method2(x: Int): Boolean
        }

        class MyClass extends Base {
          function m<caret>
        }
        """
        .trimIndent()
    )
    myFixture.completeBasic()
    val lookupStrings = lookupPresentableStrings()
    assertThat(lookupStrings)
      .contains("function method1(): String = …", "function method2(x: Int): Boolean = …")
  }

  fun `test complete implement member excludes already implemented methods`() {
    myFixture.configureByText(
      PklFileType,
      """
        abstract class Base {
          abstract function method1(): String
          abstract function method2(x: Int): Boolean
          abstract function method3(x: Int): Boolean
        }

        class MyClass extends Base {
          function method1(): String = "done"

          function m<caret>
        }
        """
        .trimIndent()
    )
    myFixture.completeBasic()
    val lookupStrings = lookupPresentableStrings()
    assertThat(lookupStrings).hasSize(2)
    assertThat(lookupStrings).contains("function method2(x: Int): Boolean = …")
    assertThat(lookupStrings).contains("function method3(x: Int): Boolean = …")
  }

  fun `test complete implement member in module`() {
    myFixture.configureByFiles(
      "implement-member/ConcreteModule.pkl",
      "implement-member/AbstractModule.pkl"
    )
    myFixture.completeBasic()
    assertThat(myFixture.editor.document.text).contains("function greet(name: String): String")
  }

  fun `test complete implement member in module excludes already implemented methods`() {
    myFixture.configureByFiles(
      "implement-member/PartiallyImplementedModule.pkl",
      "implement-member/AbstractModule.pkl"
    )
    myFixture.completeBasic()
    assertThat(myFixture.editor.document.text).contains("function greet(name: String): String")
  }

  override val fixtureDir: Path?
    get() = Path.of("src/test/resources/completion")
}
