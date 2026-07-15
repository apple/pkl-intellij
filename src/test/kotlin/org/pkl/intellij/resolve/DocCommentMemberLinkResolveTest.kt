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
package org.pkl.intellij.resolve

import org.assertj.core.api.Assertions.assertThat
import org.pkl.intellij.PklFileType
import org.pkl.intellij.PklTestCase
import org.pkl.intellij.psi.PklClass
import org.pkl.intellij.psi.PklClassMethod
import org.pkl.intellij.psi.PklClassProperty
import org.pkl.intellij.psi.PklModule

class DocCommentMemberLinkResolveTest : PklTestCase() {
  fun `test resolve member link`() {
    myFixture.configureByText(
      PklFileType,
      """
        /// This points to [Bar<caret>]
        foo: String

        /// This is bar
        class Bar
        """
        .trimIndent()
    )
    val element = myFixture.getReferenceAtCaretPosition()!!
    val resolved = element.resolve()
    assertThat(resolved).isInstanceOf(PklClass::class.java)
    resolved as PklClass
    assertThat(resolved.name).isEqualTo("Bar")
  }

  fun `test resolve nested member link`() {
    myFixture.configureByText(
      PklFileType,
      """
        /// This points to [Bar.ba<caret>z]
        foo: String

        /// This is bar
        class Bar {
          baz: Int = 5
        }
        """
        .trimIndent()
    )
    val element = myFixture.getReferenceAtCaretPosition()!!
    val resolved = element.resolve()
    assertThat(resolved).isInstanceOf(PklClassProperty::class.java)
    resolved as PklClassProperty
    assertThat(resolved.name).isEqualTo("baz")
  }

  fun `test resolve parent of nested member link`() {
    myFixture.configureByText(
      PklFileType,
      """
        import ""

        /// This points to [Ba<caret>r.baz]
        foo: String

        /// This is bar
        class Bar {
          baz: Int = 5
        }
        """
        .trimIndent()
    )
    val element = myFixture.getReferenceAtCaretPosition()!!
    val resolved = element.resolve()
    assertThat(resolved).isInstanceOf(PklClass::class.java)
    resolved as PklClass
    assertThat(resolved.name).isEqualTo("Bar")
  }

  fun `test resolve qualified same name -- property`() {
    myFixture.configureByText(
      PklFileType,
      """
        /// This points to [Bar.ba<caret>z]
        foo: String

        /// This is bar
        class Bar {
          baz: Int = 5

          function baz() = 5
        }
        """
        .trimIndent()
    )

    val element = myFixture.getReferenceAtCaretPosition()!!
    val resolved = element.resolve()
    assertThat(resolved).isInstanceOf(PklClassProperty::class.java)
    resolved as PklClassProperty
    assertThat(resolved.name).isEqualTo("baz")
  }

  fun `test resolve qualified same name -- method`() {
    myFixture.configureByText(
      PklFileType,
      """
        /// This points to [Bar.ba<caret>z()]
        foo: String

        /// This is bar
        class Bar {
          baz: Int = 5

          function baz() = 5
        }
        """
        .trimIndent()
    )

    val element = myFixture.getReferenceAtCaretPosition()!!
    val resolved = element.resolve()
    assertThat(resolved).isInstanceOf(PklClassMethod::class.java)
    resolved as PklClassMethod
    assertThat(resolved.name).isEqualTo("baz")
  }

  fun `test resolve member link - this keyword`() {
    myFixture.configureByText(
      PklFileType,
      """
      module MyModule

      /// This is [this<caret>]
      prop1: String
    """
        .trimIndent()
    )
    val element = myFixture.getReferenceAtCaretPosition()!!
    val resolved = element.resolve()
    assertThat(resolved).isInstanceOf(PklModule::class.java)
  }

  fun `test resolve member link - this keyword 2`() {
    myFixture.configureByText(
      PklFileType,
      """
      /// This is [this<caret>]
      class MyClass
    """
        .trimIndent()
    )
    val element = myFixture.getReferenceAtCaretPosition()!!
    val resolved = element.resolve()
    assertThat(resolved).isInstanceOf(PklClass::class.java)
  }

  fun `test resolve member link - module keyword`() {
    myFixture.configureByText(
      PklFileType,
      """
      module MyModule

      /// This is like [module<caret>]
      class MyClass
    """
        .trimIndent()
    )
    val element = myFixture.getReferenceAtCaretPosition()!!
    val resolved = element.resolve()
    assertThat(resolved).isInstanceOf(PklModule::class.java)
  }

  fun `test resolve qualified member link - module keyword`() {
    myFixture.configureByText(
      PklFileType,
      """
      module MyModule

      /// This is like [module.MyClass<caret>]
      class MyClass
    """
        .trimIndent()
    )
    val element = myFixture.getReferenceAtCaretPosition()!!
    val resolved = element.resolve()
    assertThat(resolved).isInstanceOf(PklClass::class.java)
    resolved as PklClass
    assertThat(resolved.name).isEqualTo("MyClass")
  }

  fun `test resolve qualified member link - this keyword`() {
    myFixture.configureByText(
      PklFileType,
      """
      module MyModule

      /// The name on MyModule
      name: String

      /// This is like [this.name<caret>]
      class MyClass {
        /// The name inside MyClass
        name: String
      }
    """
        .trimIndent()
    )
    val element = myFixture.getReferenceAtCaretPosition()!!
    val resolved = element.resolve()
    assertThat(resolved).isInstanceOf(PklClassProperty::class.java)
    resolved as PklClassProperty
    assertThat(resolved.effectiveDocComment(null)!!.contents).isEqualTo("The name inside MyClass")
  }

  fun `test resolve qualified member link - three dots starting with module keyword`() {
    myFixture.configureByText(
      PklFileType,
      """
      module MyModule

      /// This is like [module.MyClass.name<caret>]
      name: String

      class MyClass {
        /// The name inside MyClass
        name: String
      }
    """
        .trimIndent()
    )
    val element = myFixture.getReferenceAtCaretPosition()!!
    val resolved = element.resolve()
    assertThat(resolved).isInstanceOf(PklClassProperty::class.java)
    resolved as PklClassProperty
    assertThat(resolved.effectiveDocComment(null)!!.contents).isEqualTo("The name inside MyClass")
  }

  fun `test resolve super property`() {
    myFixture.configureByText(
      PklFileType,
      """
      module MyModule

      /// This is like [module.MyClass.name<caret>]
      name: String

      open class Base {
        /// The name inside Base
        name: String
      }

      class MyClass extends Base
    """
        .trimIndent()
    )
    val element = myFixture.getReferenceAtCaretPosition()!!
    val resolved = element.resolve()
    assertThat(resolved).isInstanceOf(PklClassProperty::class.java)
    resolved as PklClassProperty
    assertThat(resolved.effectiveDocComment(null)!!.contents).isEqualTo("The name inside Base")
  }
}
