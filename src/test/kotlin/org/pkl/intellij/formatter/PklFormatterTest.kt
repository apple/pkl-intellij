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
package org.pkl.intellij.formatter

import com.intellij.psi.formatter.FormatterTestCase

class PklFormatterTest : FormatterTestCase() {
  fun `test lambdas`() {
    doTextTest(
      """
      foo = (x) -> x {
        }
      bar {
      asdf = "hey"whoa="no"
       cccc = "lol" 
         aaaa ="cool" 
         bbb =   "neato"    ddd = "burrito"
      } class Hey {
      asdf = "hey"whoa="no"
       cccc = "lol" 
         aaaa ="cool" 
         bbb =   "neato"    ddd = "burrito"
      }
      masdf = "hey"mwhoa="no"
       mcccc = "lol" 
         maaaa ="cool" 
         mbbb =   "neato"    mddd = "burrito"
      """
        .trimIndent(),
      """
      foo = (x) -> x {
      }
      bar {
        asdf = "hey" whoa = "no"
        cccc = "lol"
        aaaa = "cool"
        bbb = "neato" ddd = "burrito"
      } class Hey {
        asdf = "hey" whoa = "no"
        cccc = "lol"
        aaaa = "cool"
        bbb = "neato" ddd = "burrito"
      }
      masdf = "hey" mwhoa = "no"
      mcccc = "lol"
      maaaa = "cool"
      mbbb = "neato" mddd = "burrito"
      """
        .trimIndent()
    )
  }

  override fun getTestDataPath() = "src/test/resources"

  override fun getBasePath() = "formatter"

  override fun getFileExtension() = "pkl"
}
