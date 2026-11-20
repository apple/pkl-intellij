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

class PklExprAnnotatorTest {
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
          "sample pkl project",
        )
        .fixture
    codeInsightTestFixture = fixtureFactory.createCodeInsightFixture(fixture)
    codeInsightTestFixture.setUp()
    PklAnnotator.enabledTestAnnotator = PklExprAnnotator::class
  }

  @After
  fun after() {
    codeInsightTestFixture.tearDown()
    PklAnnotator.enabledTestAnnotator = null
  }

  @Test
  fun `Amends -- new expression does not require parentheses`() {
    checkHighlighting("foo = new Listing {} { \"bar\" }")
  }

  @Test
  fun `Amends -- repeated chain from new does not require parentheses`() {
    checkHighlighting("foo = new Listing {} { \"bar\" } { \"baz\" }")
  }

  @Test
  fun `Amends -- parenthesized parent does not require more parentheses`() {
    checkHighlighting("foo = (new Listing {}) { \"bar\" }")
  }

  @Test
  fun `Amends -- unparenthesized reference still requires parentheses`() {
    checkHighlighting(
      """
      parent = new Listing {}
      foo = <error descr="Amends expressions must be parenthesized">parent</error> { "bar" }
      """
        .trimIndent()
    )
  }

  @Test
  fun `New -- plain expression does not require parentheses`() {
    checkHighlighting("foo = new Listing { \"bar\" }")
  }

  @Test
  fun `type test - constant value through complex types`() {
    checkHighlighting(
      """
      hidden const hex: String = "[0-9a-fA-F]"
      hidden const decByte: String = #"(25[0-5]|2[0-4]\d|[01]?\d{1,2})"#
      hidden const ipv4String = #"(\#(decByte)\.){3}\#(decByte)"#
      hidden const ipv6String =
        #""${'"'}
        (?x:
          (\#(hex){1,4}:){7}\#(hex){1,4}           # x:x:x:x:x:x:x:x
        | (\#(hex){1,4}:){1,7}:                    # x:: … x:x:x:x:x:x:x::
        | (\#(hex){1,4}:){6}:\#(hex){1,4}          # x:x:x:x:x:x::x
        | (\#(hex){1,4}:){5}(:\#(hex){1,4}){1,2}   # x:x:x:x:x::x … x:x:x:x:x::x:x
        | (\#(hex){1,4}:){4}(:\#(hex){1,4}){1,3}   # x:x:x:x::x … x:x:x:x::x:x:x
        | (\#(hex){1,4}:){3}(:\#(hex){1,4}){1,4}   # x:x:x::x … x:x:x::x:x:x:x
        | (\#(hex){1,4}:){2}(:\#(hex){1,4}){1,5}   # x:x::x … x:x::x:x:x:x:x
        | \#(hex){1,4}:(:\#(hex){1,4}){1,6}        # x::x … x::x:x:x:x:x:x
        | :((:\#(hex){1,4}){1,7}|:)                # ::x … ::x:x:x:x:x:x:x, ::
        | (?: # IPv6 with trailing IPv4
            (\#(hex){1,4}:){6}                      # x:x:x:x:x:x:
          | (\#(hex){1,4}:){5}:                     # x:x:x:x:x::
          | (\#(hex){1,4}:){4}(:\#(hex){1,4})?:     # x:x:x:x:: … x:x:x:x::x:
          | (\#(hex){1,4}:){3}(:\#(hex){1,4}){0,2}: # x:x:x:: … x:x:x::x:x:
          | (\#(hex){1,4}:){2}(:\#(hex){1,4}){0,3}: # x:x:: … x:x::x:x:x:
          | \#(hex){1,4}:(:\#(hex){1,4}){0,4}:      # x:: … x::x:x:x:x:
          | :(:\#(hex){1,4}){0,5}:                  # :: … ::x:x:x:x:x:
          )\#(ipv4String)                           # …d.d.d.d
        | \#(nonGlobalIPv6String)%[^%]+ # Scoped address
        )
        ""${'"'}#
      hidden const nonGlobalIPv6String =
        #""${'"'}
        (?xi-U:
          ff[0-9a-f][0-9a-d](?:                        # multicast (FF00::/8 with "scop" less than E (global scope))
            (:[0-9a-f]{1,4}){7}                        # FFxx:x:x:x:x:x:x:x
          | :(:|(:[0-9a-f]{1,4}){1,6})                 # FFxx:: … FFxx::x:x:x:x:x:x
          | :[0-9a-f]:(:|(:[0-9a-f]{1,4}){1,5})        # FFxx:x:: … FFxx:x::x:x:x:x:x
          | (:[0-9a-f]){2}:(:|(:[0-9a-f]{1,4}){1,4})   # FFxx:x:x:: … FFxx:x:x::x:x:x:x
          | (:[0-9a-f]){3}:(:|(:[0-9a-f]{1,4}){1,3})   # FFxx:x:x:x:: … FFxx:x:x:x::x:x:x
          | (:[0-9a-f]){4}:(:|(:[0-9a-f]{1,4}){1,2})   # FFxx:x:x:x:x:: … FFxx:x:x:x:x::x:x
          | (:[0-9a-f]){5}:(:|(:[0-9a-f]{1,4}))        # FFxx:x:x:x:x:x:: … FFxx:x:x:x:x:x::x
          | (:[0-9a-f]){6}::                           # FFxx:x:x:x:x:x:x::
          )
        | fe80(?:                                      # link-local unicast (FE80::/10)
            (:0{1,4}){3}(?:                            # FE80:0:0:0…
              :: | :(:[0-9a-f]{1,4}){1,3}              # FE80:0:0:0:: … FE80:0:0:0::x:x:x
            | :[0-9a-f]{1,4}:(:|(:[0-9a-f]{1,4}){1,2}) # FE80:0:0:0:x:: … FE80:0:0:0:x::x:x
            | (:[0-9a-f]{1,4}){2}::([0-9a-f]{1,4})?    # FE80:0:0:0:x:x:: … FE80:0:0:0:x:x::x
            | (:[0-9a-f]{1,4}){3}:(:|[0-9a-f]{1,4})    # FE80:0:0:0:x:x:x::, FE80:0:0:0:x:x:x:x
            )
          | (:0{1,4}){0,2}:(:|(:[0-9a-f]{1,4}){1,4})   # FE80:: … FE80:0:0::x:x:x:x
          )
        )
        ""${'"'}#

      typealias IPCIDRString = IPv4CIDRString | IPv6CIDRString
      typealias IPNetworkString = IPv4NetworkString | IPv6NetworkString
      typealias IPv4CIDRString = String(matches(Regex(#"\#(ipv4String)/([12]?\d|3[012])"#)))
      typealias IPv6CIDRString = String(matches(Regex(#"\#(ipv6String)/([1-9]?\d|1([01]\d|2[0-8]))"#)))
      typealias IPv4NetworkString = IPv4CIDRString(this + "foo" == "bar") // random non-solvable constraint
      typealias IPv6NetworkString = IPv6CIDRString(this + "foo" == "bar") // random non-solvable constraint

      res1 = <warning descr="Expression is always 'true'">"10.0.0.0/24" is IpNetworkString</warning>
      res1a = "10.0.0.0/24" <warning descr="Type cast is redundant">as IpNetworkString</warning>
      res2 = <warning descr="Expression is always 'true'">"2001:db8::/32" is IpNetworkString</warning>
      res2a = "2001:db8::/32" <warning descr="Type cast is redundant">as IpNetworkString</warning>
      res3 = <warning descr="Expression is always 'true'">"10.0.0.1/24" is IpNetworkString</warning>
      res3a = "10.0.0.1/24" <warning descr="Type cast is redundant">as IpNetworkString</warning>
      res4 = <warning descr="Expression is always 'true'">"2001:db8::1/32" is IpNetworkString</warning>
      res4a = "2001:db8::1/32" <warning descr="Type cast is redundant">as IpNetworkString</warning>
      res5 = <warning descr="Expression is always 'true'">"not a cidr" is IpNetworkString</warning>
      res5a = "not a cidr" <warning descr="Type cast is redundant">as IpNetworkString</warning>
      res6 = <warning descr="Expression is always 'true'">"" is IpNetworkString</warning>
      res6a = "" <warning descr="Type cast is redundant">as IpNetworkString</warning>
      res7 = <warning descr="Expression is always 'true'">"127.0.0.1:\u{FF11}" is IpAddressPortString</warning>
      res7a = "127.0.0.1:\u{FF11}" <warning descr="Type cast is redundant">as IpAddressPortString</warning>
      res8 = <warning descr="Expression is always 'true'">"[::1]:\u{FF11}" is IpAddressPortString</warning>
      res8a = "[::1]:\u{FF11}" <warning descr="Type cast is redundant">as IpAddressPortString</warning>
      res9 = <warning descr="Expression is always 'true'">"10.0.0.0/\u{FF11}" is Ipv4CidrString</warning> 
      res9a = "10.0.0.0/\u{FF11}" <warning descr="Type cast is redundant">as Ipv4CidrString</warning> 
      res0 = <warning descr="Expression is always 'true'">"::1/\u{FF11}" is Ipv6CidrString</warning>
      res0a = "::1/\u{FF11}" <warning descr="Type cast is redundant">as Ipv6CidrString</warning>
      """
        .trimIndent()
    )
  }
}
