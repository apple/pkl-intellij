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
package org.pkl.intellij.type

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.pkl.intellij.psi.PklPsiFactory
import org.pkl.intellij.psi.pklBaseModule

class TypeTest : BasePlatformTestCase() {
  fun testUnknownType() {
    val base = project.pklBaseModule

    assertTrue(Type.Unknown.isSubtypeOf(base.stringType, base))
    assertTrue(base.stringType.isSubtypeOf(Type.Unknown, base))

    val listOfString = base.listType.withTypeArguments(base.stringType)
    val listOfUnknown = base.listType.withTypeArguments(Type.Unknown)

    assertTrue(listOfUnknown.isSubtypeOf(listOfString, base))
    assertTrue(listOfString.isSubtypeOf(listOfUnknown, base))

    val listingOfString = base.listingType.withTypeArguments(base.stringType)
    val listingOfUnknown = base.listingType.withTypeArguments(Type.Unknown)

    assertTrue(listingOfUnknown.isSubtypeOf(listingOfString, base))
    assertTrue(listingOfString.isSubtypeOf(listingOfUnknown, base))

    val mappingOfString = base.listingType.withTypeArguments(base.stringType, base.stringType)
    val mappingOfUnknown = base.listingType.withTypeArguments(Type.Unknown, Type.Unknown)

    assertTrue(mappingOfUnknown.isSubtypeOf(mappingOfString, base))
    assertTrue(mappingOfString.isSubtypeOf(mappingOfUnknown, base))
  }

  fun testNullableType() {
    val base = project.pklBaseModule

    val nullableString = base.stringType.nullable(base)
    val nullableString2 = base.stringType.nullable(base)

    assertTrue(nullableString.isSubtypeOf(nullableString, base))
    assertTrue(nullableString.isSubtypeOf(nullableString2, base))
    assertTrue(nullableString2.isSubtypeOf(nullableString, base))

    val person = PklPsiFactory.createClass("Person", project)
    val nullablePerson = Type.Class(person).nullable(base)
    val nullablePerson2 = Type.Class(person).nullable(base)

    assertTrue(nullablePerson.isSubtypeOf(nullablePerson, base))
    assertTrue(nullablePerson.isSubtypeOf(nullablePerson2, base))
    assertTrue(nullablePerson2.isSubtypeOf(nullablePerson, base))

    assertTrue(base.nullType.isSubtypeOf(nullablePerson, base))
    assertTrue(Type.union(base.nullType, nullablePerson, base).isSubtypeOf(nullablePerson, base))
  }

  fun testTypeAliasedUnionType() {
    val base = project.pklBaseModule

    val stringOrIntType = Type.union(base.stringType, base.intType, base)
    val alias = PklPsiFactory.createTypeAlias("alias", "String|Int", project)
    val aliasType = Type.alias(alias)

    assertTrue(stringOrIntType.isSubtypeOf(stringOrIntType, base))
    assertTrue(stringOrIntType.isSubtypeOf(aliasType, base))
    assertTrue(aliasType.isSubtypeOf(stringOrIntType, base))
    assertTrue(aliasType.isSubtypeOf(aliasType, base))
    assertTrue(base.stringType.isSubtypeOf(stringOrIntType, base))
    assertTrue(base.intType.isSubtypeOf(stringOrIntType, base))
  }

  fun testGenericType() {
    val base = project.pklBaseModule

    val t1 = base.mappingType.withTypeArguments(base.stringType, base.types["ValueRenderer"]!!)
    val t2 = base.mappingType.withTypeArguments(base.stringType, base.types["PcfRenderer"]!!)

    assertTrue(t2.isSubtypeOf(t1, base))
    assertFalse(t1.isSubtypeOf(t2, base))

    val f1 = base.function1Type.withTypeArguments(t1, t1)
    val f2 = base.function1Type.withTypeArguments(t2, t2)

    assertFalse(f1.isSubtypeOf(f2, base))
    assertFalse(f2.isSubtypeOf(f1, base))
    assertTrue(f1.hasCommonSubtypeWith(f2, base))
    assertTrue(f2.hasCommonSubtypeWith(f1, base))

    val mixinType = base.types["Mixin"]!! as Type.Alias
    val m1 = mixinType.withTypeArguments(t1)
    val m2 = mixinType.withTypeArguments(t2)

    assertFalse(m1.isSubtypeOf(m2, base))
    assertFalse(m2.isSubtypeOf(m1, base))
    assertTrue(m1.hasCommonSubtypeWith(m2, base))
    assertTrue(m2.hasCommonSubtypeWith(m1, base))
  }
}
