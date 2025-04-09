/**
 * Copyright © 2024-2025 Apple Inc. and the Pkl project authors. All rights reserved.
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
package org.pkl.intellij.type

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.pkl.intellij.psi.PklPsiFactory
import org.pkl.intellij.psi.pklBaseModule

class TypeTest : BasePlatformTestCase() {
  fun testUnknownType() {
    val base = project.pklBaseModule

    assertTrue(Type.Unknown.isSubtypeOf(base.stringType, base, null))
    assertTrue(base.stringType.isSubtypeOf(Type.Unknown, base, null))

    val listOfString = base.listType.withTypeArguments(base.stringType)
    val listOfUnknown = base.listType.withTypeArguments(Type.Unknown)

    assertTrue(listOfUnknown.isSubtypeOf(listOfString, base, null))
    assertTrue(listOfString.isSubtypeOf(listOfUnknown, base, null))

    val listingOfString = base.listingType.withTypeArguments(base.stringType)
    val listingOfUnknown = base.listingType.withTypeArguments(Type.Unknown)

    assertTrue(listingOfUnknown.isSubtypeOf(listingOfString, base, null))
    assertTrue(listingOfString.isSubtypeOf(listingOfUnknown, base, null))

    val mappingOfString = base.listingType.withTypeArguments(base.stringType, base.stringType)
    val mappingOfUnknown = base.listingType.withTypeArguments(Type.Unknown, Type.Unknown)

    assertTrue(mappingOfUnknown.isSubtypeOf(mappingOfString, base, null))
    assertTrue(mappingOfString.isSubtypeOf(mappingOfUnknown, base, null))
  }

  fun testNullableType() {
    val base = project.pklBaseModule

    val nullableString = base.stringType.nullable(base, null)
    val nullableString2 = base.stringType.nullable(base, null)

    assertTrue(nullableString.isSubtypeOf(nullableString, base, null))
    assertTrue(nullableString.isSubtypeOf(nullableString2, base, null))
    assertTrue(nullableString2.isSubtypeOf(nullableString, base, null))

    val person = PklPsiFactory.createClass("Person", project)
    val nullablePerson = Type.Class(person).nullable(base, null)
    val nullablePerson2 = Type.Class(person).nullable(base, null)

    assertTrue(nullablePerson.isSubtypeOf(nullablePerson, base, null))
    assertTrue(nullablePerson.isSubtypeOf(nullablePerson2, base, null))
    assertTrue(nullablePerson2.isSubtypeOf(nullablePerson, base, null))

    assertTrue(base.nullType.isSubtypeOf(nullablePerson, base, null))
    assertTrue(
      Type.union(base.nullType, nullablePerson, base, null).isSubtypeOf(nullablePerson, base, null)
    )
  }

  fun testTypeAliasedUnionType() {
    val base = project.pklBaseModule

    val stringOrIntType = Type.union(base.stringType, base.intType, base, null)
    val alias = PklPsiFactory.createTypeAlias("alias", "String|Int", project)
    val aliasType = Type.alias(alias, null)

    assertTrue(stringOrIntType.isSubtypeOf(stringOrIntType, base, null))
    assertTrue(stringOrIntType.isSubtypeOf(aliasType, base, null))
    assertTrue(aliasType.isSubtypeOf(stringOrIntType, base, null))
    assertTrue(aliasType.isSubtypeOf(aliasType, base, null))
    assertTrue(base.stringType.isSubtypeOf(stringOrIntType, base, null))
    assertTrue(base.intType.isSubtypeOf(stringOrIntType, base, null))
  }

  fun testGenericType() {
    val base = project.pklBaseModule

    val t1 = base.mappingType.withTypeArguments(base.stringType, base.types["ValueRenderer"]!!)
    val t2 = base.mappingType.withTypeArguments(base.stringType, base.types["PcfRenderer"]!!)

    assertTrue(t2.isSubtypeOf(t1, base, null))
    assertFalse(t1.isSubtypeOf(t2, base, null))

    val f1 = base.function1Type.withTypeArguments(t1, t1)
    val f2 = base.function1Type.withTypeArguments(t2, t2)

    assertFalse(f1.isSubtypeOf(f2, base, null))
    assertFalse(f2.isSubtypeOf(f1, base, null))
    assertTrue(f1.hasCommonSubtypeWith(f2, base, null))
    assertTrue(f2.hasCommonSubtypeWith(f1, base, null))

    val mixinType = base.types["Mixin"]!! as Type.Alias
    val m1 = mixinType.withTypeArguments(t1)
    val m2 = mixinType.withTypeArguments(t2)

    assertFalse(m1.isSubtypeOf(m2, base, null))
    assertFalse(m2.isSubtypeOf(m1, base, null))
    assertTrue(m1.hasCommonSubtypeWith(m2, base, null))
    assertTrue(m2.hasCommonSubtypeWith(m1, base, null))
  }
}
