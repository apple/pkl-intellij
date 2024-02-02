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
package org.pkl.intellij.psi

import org.pkl.intellij.resolve.ResolveVisitor
import org.pkl.intellij.type.Type
import org.pkl.intellij.type.TypeParameterBindings

interface PklAccessExpr : PklElement {
  val memberName: PklIdentifierOwner

  val memberNameText: String
    get() = memberName.identifier.text

  val argumentList: PklArgumentList?

  /**
   * Tells if this is a property access (e.g., `foo.bar`) rather than method access (e.g.,
   * `foo.bar()`).
   *
   * Whereas a method access always resolves to a method, a property access may resolve to a
   * property, let variable, for variable, method parameter, lambda parameter, type parameter,
   * class, type alias, or module.
   */
  val isPropertyAccess: Boolean
    get() = argumentList == null

  val isNullSafeAccess: Boolean
    get() = firstChildTokenType() == PklElementTypes.QDOT

  val hasArguments: Boolean
    get() = !argumentList?.elements.isNullOrEmpty()

  fun <R> resolve(
    base: PklBaseModule,
    /**
     * Optionally provide the receiver type to avoid its recomputation in case it is needed. For
     * [PklUnqualifiedAccessExpr] and [PklSuperAccessExpr], [receiverType] is the type of `this` and
     * `super`, respectively.
     */
    receiverType: Type?,
    bindings: TypeParameterBindings,
    visitor: ResolveVisitor<R>
  ): R
}
