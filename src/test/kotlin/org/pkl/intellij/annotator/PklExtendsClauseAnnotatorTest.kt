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
import kotlin.text.trimIndent
import org.junit.After
import org.junit.Before
import org.junit.Test

class PklExtendsClauseAnnotatorTest {
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
    PklAnnotator.enabledTestAnnotator = PklExtendsClauseAnnotator::class
  }

  @After
  fun after() {
    fixture.tearDown()
    PklAnnotator.enabledTestAnnotator = null
  }

  private fun checkHighlighting(src: String) {
    fixture.configureByText("test.pkl", src.trimIndent())
    fixture.checkHighlighting()
  }

  private fun checkQuickFix(before: String, after: String) {
    fixture.configureByText("test.pkl", before.trimIndent())
    implementMembers()
    fixture.checkResult(after.trimIndent())
  }

  private fun implementMembers() {
    val fix = fixture.getAllQuickFixes().first { it.text == "Implement members" }
    fixture.launchAction(fix)
  }

  @Test
  fun `missing abstract property`() {
    checkHighlighting(
      """
      abstract class Base {
        abstract name: String
      }
      class Child <error descr="class Child is not abstract and does not implement property 'name'">extends Base</error> {}
      """
    )
  }

  @Test
  fun `missing abstract method`() {
    checkHighlighting(
      """
      abstract class Base {
        abstract function greet(): String
      }
      class Child <error descr="class Child is not abstract and does not implement method 'greet'">extends Base</error> {}
      """
    )
  }

  @Test
  fun `missing fixed property default`() {
    checkHighlighting(
      """
      abstract class Base {
        fixed name: String
      }
      class Child <error descr="class Child is not abstract and does not define a default value for property 'name'">extends Base</error> {}
      """
    )
  }

  @Test
  fun `abstract child does not error`() {
    checkHighlighting(
      """
      abstract class Base {
        abstract name: String
      }
      abstract class Child extends Base {}
      """
    )
  }

  @Test
  fun `child that implements all members does not error`() {
    checkHighlighting(
      """
      abstract class Base {
        abstract function name(): String
      }
      class Child extends Base {
        function name(): String = "Bob"
      }
      """
    )
  }

  @Test
  fun `quickfix implements abstract property`() {
    checkQuickFix(
      before =
        """
        abstract class Base {
          abstract name: String
        }
        class Child extends Base {}
        """,
      after =
        """
        abstract class Base {
          abstract name: String
        }
        class Child extends Base {
          name: String = TODO()
        }
        """
    )
  }

  @Test
  fun `quickfix implements abstract method`() {
    checkQuickFix(
      before =
        """
        abstract class Base {
          abstract function greet(): String
        }
        class Child extends Base {}
        """,
      after =
        """
        abstract class Base {
          abstract function greet(): String
        }
        class Child extends Base {
          function greet(): String = TODO()
        }
        """
    )
  }

  @Test
  fun `quickfix fills fixed property value`() {
    checkQuickFix(
      before =
        """
        abstract class Base {
          fixed name: String
        }
        class Child extends Base {}
        """,
      after =
        """
        abstract class Base {
          fixed name: String
        }
        class Child extends Base {
          fixed name = TODO()
        }
        """
    )
  }

  @Test
  fun `quickfix implements abstract fixed property`() {
    checkQuickFix(
      before =
        """
        abstract class Base {
          abstract fixed name: String
        }
        class Child extends Base {}
        """,
      after =
        """
        abstract class Base {
          abstract fixed name: String
        }
        class Child extends Base {
          fixed name: String = TODO()
        }
        """
    )
  }

  @Test
  fun `quickfix implements multiple missing members`() {
    checkQuickFix(
      before =
        """
        abstract class Base {
          abstract name: String
          abstract function greet(): String
        }
        class Child extends Base {}
        """,
      after =
        """
        abstract class Base {
          abstract name: String
          abstract function greet(): String
        }
        class Child extends Base {
          name: String = TODO()

          function greet(): String = TODO()
        }
        """
    )
  }

  @Test
  fun `quickfix implements class members through nested hierarchy`() {
    checkQuickFix(
      before =
        """
        abstract class Base {
          abstract name: String
          abstract function greet(): String
        }

        abstract class Base2 extends Base {}

        class Child extends Base2 {}
        """,
      after =
        """
        abstract class Base {
          abstract name: String
          abstract function greet(): String
        }

        abstract class Base2 extends Base {}

        class Child extends Base2 {
          name: String = TODO()

          function greet(): String = TODO()
        }
        """
    )
  }

  @Test
  fun `quickfix implements module members`() {
    fixture.configureByText(
      "foo.pkl",
      """
      abstract module foo

      abstract bar: Int
    """
        .trimIndent()
    )
    fixture.configureByText(
      "test.pkl",
      """
      extends "foo.pkl"
    """
        .trimIndent()
    )
    implementMembers()
    fixture.checkResult(
      """
      extends "foo.pkl"

      bar: Int = TODO()

    """
        .trimIndent()
    )
  }

  @Test
  fun `module type in same module`() {
    checkQuickFix(
      before =
        """
        abstract class Base {
          abstract myModule: module
        }

        class Child extends Base {}
        """,
      after =
        """
        abstract class Base {
          abstract myModule: module
        }

        class Child extends Base {
          myModule: module = TODO()
        }
        """
    )
  }

  @Test
  fun `import type in different module`() {
    fixture.configureByText(
      "foo.pkl",
      """
      abstract module foo

      import "Bar.pkl"

      abstract class Base {
        abstract bar: Bar
      }
    """
        .trimIndent()
    )

    fixture.configureByText("Bar.pkl", "")
    fixture.configureByText(
      "test.pkl",
      """
      import "foo.pkl"
      
      class Child extends foo.Base
    """
        .trimIndent()
    )
    implementMembers()
    fixture.checkResult(
      """
      import "Bar.pkl"
      import "foo.pkl"

      class Child extends foo.Base {
        bar: Bar = TODO()
      }
    """
        .trimIndent()
    )
  }

  @Test
  fun `import nested type in different module`() {
    fixture.configureByText(
      "foo.pkl",
      """
      abstract module foo

      import "bar.pkl"

      abstract class Base {
        abstract bar: bar.Qux
      }
    """
        .trimIndent()
    )

    fixture.configureByText(
      "bar.pkl",
      """
      class Qux
    """
        .trimIndent()
    )
    fixture.configureByText(
      "test.pkl",
      """
      import "foo.pkl"
      
      class Child extends foo.Base
    """
        .trimIndent()
    )
    implementMembers()
    fixture.checkResult(
      """
      import "bar.pkl"
      import "foo.pkl"

      class Child extends foo.Base {
        bar: bar.Qux = TODO()
      }
    """
        .trimIndent()
    )
  }

  @Test
  fun `import type in different module, with conflicts`() {
    fixture.configureByText(
      "foo.pkl",
      """
      abstract module foo

      import "bar.pkl"

      abstract class Base {
        abstract bar: bar.Qux
      }
    """
        .trimIndent()
    )

    fixture.configureByText(
      "bar.pkl",
      """
      class Qux
    """
        .trimIndent()
    )

    fixture.configureByText(
      "test.pkl",
      """
      import "foo.pkl"
      import "other.pkl" as bar

      class Child extends foo.Base
    """
        .trimIndent()
    )
    implementMembers()
    fixture.checkResult(
      """
      import "bar.pkl" as bar2
      import "foo.pkl"
      import "other.pkl" as bar

      class Child extends foo.Base {
        bar: bar2.Qux = TODO()
      }
    """
        .trimIndent()
    )
  }

  @Test
  fun `module type in different module`() {
    fixture.configureByText(
      "foo.pkl",
      """
      abstract module foo

      import "Bar.pkl"

      abstract class Base extends Bar
    """
        .trimIndent()
    )

    fixture.configureByText(
      "Bar.pkl",
      """
      abstract foo: module
    """
        .trimIndent()
    )

    fixture.configureByText(
      "test.pkl",
      """
      import "foo.pkl"

      class Child extends foo.Base
    """
        .trimIndent()
    )
    implementMembers()
    fixture.checkResult(
      """
        import "Bar.pkl"
        import "foo.pkl"

        class Child extends foo.Base {
          foo: Bar = TODO()
        }
        """
        .trimIndent()
    )
  }
}
