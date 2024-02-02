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
@file:Suppress("DuplicatedCode")

package org.pkl.intellij.type

import com.intellij.psi.PsiManager
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import java.util.*
import kotlin.math.min
import org.pkl.intellij.PklLanguage
import org.pkl.intellij.psi.*
import org.pkl.intellij.resolve.ResolveVisitor
import org.pkl.intellij.type.Type.*
import org.pkl.intellij.type.Type.Nothing
import org.pkl.intellij.util.escapeString
import org.pkl.intellij.util.unexpectedType

/**
 * A type whose names have been resolved to their definitions (PSIs).
 *
 * [nullable] types are represented as [Union] types. Function types are represented as the
 * corresponding [Class] types (but [render]ed as function types).
 *
 * [equals] is defined as *structural* equality and equivalence of referenced PSIs; constraints are
 * not taken into account. To test for type equivalence, use [isEquivalentTo].
 */
sealed class Type(val constraints: List<ConstraintExpr> = listOf()) {
  companion object {
    fun alias(
      psi: PklTypeAlias,
      specifiedTypeArguments: List<Type> = listOf(),
      constraints: List<ConstraintExpr> = listOf()
    ): Type =
      // Note: this is incomplete in that it doesn't detect the case
      // where recursion is introduced via type argument:
      // typealias Alias<T> = T|Boolean
      // p: Alias<Alias<String>>
      if (psi.isRecursive) Unknown else Alias.unchecked(psi, specifiedTypeArguments, constraints)

    fun module(psi: PklModule, referenceName: String): Module = Module.create(psi, referenceName)

    fun union(type1: Type, type2: Type, base: PklBaseModule): Type =
      Union.create(type1, type2, base)

    fun union(type1: Type, type2: Type, type3: Type, base: PklBaseModule): Type =
      Union.create(Union.create(type1, type2, base), type3, base)

    fun union(type1: Type, type2: Type, type3: Type, type4: Type, base: PklBaseModule): Type =
      Union.create(Union.create(Union.create(type1, type2, base), type3, base), type4, base)

    fun union(
      type1: Type,
      type2: Type,
      type3: Type,
      type4: Type,
      type5: Type,
      base: PklBaseModule
    ): Type =
      Union.create(
        Union.create(Union.create(Union.create(type1, type2, base), type3, base), type4, base),
        type5,
        base
      )

    fun union(
      type1: Type,
      type2: Type,
      type3: Type,
      type4: Type,
      type5: Type,
      type6: Type,
      base: PklBaseModule
    ): Type =
      Union.create(
        Union.create(
          Union.create(Union.create(Union.create(type1, type2, base), type3, base), type4, base),
          type5,
          base
        ),
        type6,
        base
      )

    fun union(types: List<Type>, base: PklBaseModule): Type =
      types.reduce { t1, t2 -> Union.create(t1, t2, base) }

    fun function1(param1Type: Type, returnType: Type, base: PklBaseModule): Type =
      base.function1Type.withTypeArguments(param1Type, returnType)
  }

  open val hasConstraints: Boolean = constraints.isNotEmpty()

  abstract fun withConstraints(constraints: List<ConstraintExpr>): Type

  abstract fun visitMembers(
    isProperty: Boolean,
    allowClasses: Boolean,
    base: PklBaseModule,
    visitor: ResolveVisitor<*>
  ): Boolean

  abstract fun resolveToDefinitions(base: PklBaseModule): List<PklNavigableElement>

  /** Tells whether this type is a (non-strict) subtype of [classType]. */
  abstract fun isSubtypeOf(classType: Class, base: PklBaseModule): Boolean

  /** Tells whether this type is a (non-strict) subtype of [type]. */
  abstract fun isSubtypeOf(type: Type, base: PklBaseModule): Boolean

  fun hasDefault(base: PklBaseModule) = if (isNullable(base)) true else hasDefaultImpl(base)

  protected abstract fun hasDefaultImpl(base: PklBaseModule): Boolean

  /** Helper for implementing [isSubtypeOf]. */
  protected fun doIsSubtypeOf(type: Type, base: PklBaseModule): Boolean =
    when (type) {
      Unknown -> true
      is Class -> isSubtypeOf(type, base)
      is Alias -> isSubtypeOf(type.aliasedType(base), base)
      is Union -> isSubtypeOf(type.leftType, base) || isSubtypeOf(type.rightType, base)
      else -> false
    }

  /** Note that `unknown` is equivalent to every type. */
  fun isEquivalentTo(type: Type, base: PklBaseModule): Boolean =
    isSubtypeOf(type, base) && type.isSubtypeOf(this, base)

  /**
   * Tells if there is a refinement of this type that is a subtype of [type]. The trivial
   * refinements `nothing` and `unkown` are not considered valid answers. Assumes
   * `!isSubtypeOf(type)`.
   *
   * The motivation for this method is to check if `!isSubtypeOf(type)` could be caused by the type
   * system being too weak, which is only the case if `hasCommonSubtypeWith(type)`.
   */
  abstract fun hasCommonSubtypeWith(type: Type, base: PklBaseModule): Boolean

  /** Helper for implementing [hasCommonSubtypeWith]. */
  protected fun doHasCommonSubtypeWith(type: Type, base: PklBaseModule): Boolean =
    when (type) {
      is Alias -> hasCommonSubtypeWith(type.aliasedType(base), base)
      is Union ->
        hasCommonSubtypeWith(type.leftType, base) || hasCommonSubtypeWith(type.rightType, base)
      else -> true
    }

  /**
   * Tells whether an unresolved member should be reported as error (rather than warning) for this
   * type.
   *
   * Implementations should return `false` if there is a chance that the member is declared by a
   * subtype.
   */
  abstract fun isUnresolvedMemberFatal(base: PklBaseModule): Boolean

  open fun toClassType(base: PklBaseModule): Class? = null

  open fun unaliased(base: PklBaseModule): Type? = this

  open fun nonNull(base: PklBaseModule): Type = if (this == base.nullType) Nothing else this

  fun nullable(base: PklBaseModule): Type =
    // Foo? is syntactic sugar for Null|Foo
    // Null|Foo and Foo|Null are equivalent for typing purposes but have different defaults
    union(base.nullType, this, base)

  open val bindings: TypeParameterBindings = mapOf()

  fun isNullable(base: PklBaseModule): Boolean = base.nullType.isSubtypeOf(this, base)

  fun isAmendable(base: PklBaseModule): Boolean = amended(base) != Nothing

  fun isInstantiable(base: PklBaseModule): Boolean = instantiated(base) != Nothing

  /**
   * The type of `expr {}` where `expr` has this type. Defaults to [Nothing], that is, not
   * amendable.
   */
  open fun amended(base: PklBaseModule): Type = Nothing

  /**
   * The type of `new T {}` where `T` is this type. (Assumption: `T` is exactly the instantiated
   * type, not a supertype.) Defaults to [Nothing], that is, not instantiable.
   */
  open fun instantiated(base: PklBaseModule): Type = amended(base)

  /**
   * Type inside an amend block whose parent has this type. Leniently defaults to [Unknown] (instead
   * of [Nothing]) because "cannot amend type" is reported separately.
   */
  open fun amending(base: PklBaseModule): Type = Unknown

  abstract fun render(builder: Appendable, nameRenderer: TypeNameRenderer = DefaultTypeNameRenderer)

  fun render(): String = buildString { render(this) }

  override fun toString(): String = render()

  object Unknown : Type() {
    override fun visitMembers(
      isProperty: Boolean,
      allowClasses: Boolean,
      base: PklBaseModule,
      visitor: ResolveVisitor<*>
    ): Boolean = true

    // Note: we aren't currently tracking constraints for unknown type (uncommon, would require a
    // class)
    override fun withConstraints(constraints: List<ConstraintExpr>): Type = this

    override fun amended(base: PklBaseModule): Type =
      // Ideally we'd return "`unknown` with upper bound `base.amendedType`",
      // but this cannot currently be expressed
      Unknown

    override fun amending(base: PklBaseModule): Type =
      // Ideally we'd return "`unknown` with upper bound `Object`",
      // but this cannot currently be expressed.
      Unknown

    override fun render(builder: Appendable, nameRenderer: TypeNameRenderer) {
      builder.append("unknown")
    }

    override fun resolveToDefinitions(base: PklBaseModule): List<PklTypeDefOrModule> = listOf()

    override fun isSubtypeOf(classType: Class, base: PklBaseModule): Boolean = true

    override fun isSubtypeOf(type: Type, base: PklBaseModule): Boolean = true

    // `unknown` is not considered a valid answer
    override fun hasCommonSubtypeWith(type: Type, base: PklBaseModule): Boolean = false

    override fun isUnresolvedMemberFatal(base: PklBaseModule): Boolean = false

    override fun hasDefaultImpl(base: PklBaseModule): Boolean = false
  }

  object Nothing : Type() {
    override fun visitMembers(
      isProperty: Boolean,
      allowClasses: Boolean,
      base: PklBaseModule,
      visitor: ResolveVisitor<*>
    ): Boolean = true

    // constraints for bottom type aren't meaningful -> don't track them
    override fun withConstraints(constraints: List<ConstraintExpr>): Type = this

    override fun render(builder: Appendable, nameRenderer: TypeNameRenderer) {
      builder.append("nothing")
    }

    override fun resolveToDefinitions(base: PklBaseModule): List<PklTypeDefOrModule> = listOf()

    override fun isSubtypeOf(classType: Class, base: PklBaseModule): Boolean = true

    override fun isSubtypeOf(type: Type, base: PklBaseModule): Boolean = true

    // `nothing` is not considered a valid answer
    override fun hasCommonSubtypeWith(type: Type, base: PklBaseModule): Boolean = false

    override fun isUnresolvedMemberFatal(base: PklBaseModule): Boolean = true

    override fun hasDefaultImpl(base: PklBaseModule): Boolean = false
  }

  class Variable(val psi: PklTypeParameter, constraints: List<ConstraintExpr> = listOf()) :
    Type(constraints) {
    override fun withConstraints(constraints: List<ConstraintExpr>): Type =
      Variable(psi, constraints)

    override fun visitMembers(
      isProperty: Boolean,
      allowClasses: Boolean,
      base: PklBaseModule,
      visitor: ResolveVisitor<*>
    ): Boolean = true

    override fun resolveToDefinitions(base: PklBaseModule): List<PklNavigableElement> = listOf(psi)

    override fun isSubtypeOf(classType: Class, base: PklBaseModule): Boolean =
      classType.classEquals(base.anyType)

    override fun isSubtypeOf(type: Type, base: PklBaseModule): Boolean =
      this == type || doIsSubtypeOf(type, base)

    override fun hasCommonSubtypeWith(type: Type, base: PklBaseModule): Boolean =
      type.unaliased(base) != Nothing

    override fun isUnresolvedMemberFatal(base: PklBaseModule): Boolean = true // treat like `Any`

    override fun amended(base: PklBaseModule): Type = this

    override fun amending(base: PklBaseModule): Type = Unknown

    override fun render(builder: Appendable, nameRenderer: TypeNameRenderer) {
      builder.append(psi.name)
    }

    override fun equals(other: Any?): Boolean {
      return other is Variable && areElementsEquivalent(psi, other.psi)
    }

    override fun hashCode(): Int {
      return psi.hashCode()
    }

    override fun hasDefaultImpl(base: PklBaseModule): Boolean = false

    override fun toString(): String = psi.identifier.text
  }

  class Module
  private constructor(
    val psi: PklModule,
    val referenceName: String,
    constraints: List<ConstraintExpr>
  ) : Type(constraints) {
    companion object {
      // this method exists because `Type.module()` can't see the private constructor
      internal fun create(
        psi: PklModule,
        referenceName: String,
        constraints: List<ConstraintExpr> = listOf()
      ): Module {
        var result = psi
        // a module's type is the topmost module in the module hierarchy that doesn't amend another
        // module.
        // if we can't resolve an amends reference, we bail out, i.e., invalid code may produce an
        // incorrect type.
        while (result.extendsAmendsClause.isAmend) {
          result = result.supermodule ?: return Module(result, referenceName, constraints)
        }
        return Module(result, referenceName, constraints)
      }
    }

    override fun withConstraints(constraints: List<ConstraintExpr>): Type =
      Module(psi, referenceName, constraints)

    override fun visitMembers(
      isProperty: Boolean,
      allowClasses: Boolean,
      base: PklBaseModule,
      visitor: ResolveVisitor<*>
    ): Boolean {
      return if (allowClasses) {
        psi.cache.visitTypeDefsAndPropertiesOrMethods(isProperty, visitor)
      } else {
        psi.cache.visitPropertiesOrMethods(isProperty, visitor)
      }
    }

    override fun resolveToDefinitions(base: PklBaseModule): List<PklTypeDefOrModule> = listOf(psi)

    override fun isSubtypeOf(classType: Class, base: PklBaseModule): Boolean =
      base.moduleType.isSubtypeOf(classType, base)

    override fun isSubtypeOf(type: Type, base: PklBaseModule): Boolean =
      when (type) {
        is Module -> isSubtypeOf(type)
        else -> doIsSubtypeOf(type, base)
      }

    private fun isSubtypeOf(type: Module): Boolean {
      var currPsi: PklModule? = psi
      while (currPsi != null) {
        // use psi equivalence to be consistent with equals()
        if (areElementsEquivalent(currPsi, type.psi)) return true
        currPsi = currPsi.supermodule
      }
      return false
    }

    fun supermodule(): Module? = psi.supermodule?.let { module(it, it.shortDisplayName) }

    // assumes `!this.isSubtypeOf(type)`
    override fun hasCommonSubtypeWith(type: Type, base: PklBaseModule): Boolean =
      when (type) {
        is Module -> type.isSubtypeOf(this)
        is Class -> type.isSubtypeOf(this, base)
        else -> doHasCommonSubtypeWith(type, base)
      }

    override fun isUnresolvedMemberFatal(base: PklBaseModule): Boolean = !psi.isAbstractOrOpen

    override fun amended(base: PklBaseModule): Type = this

    override fun amending(base: PklBaseModule): Type = this

    override fun render(builder: Appendable, nameRenderer: TypeNameRenderer) {
      nameRenderer.render(this, builder)
    }

    override fun equals(other: Any?): Boolean =
      // not certain which equality semantics we need (psi identity might be enough), but let's try
      // this one
      this === other || other is Module && areElementsEquivalent(psi, other.psi)

    // not sure if there's a way/need to have a hashCode() that's consistent with equals()
    override fun hashCode(): Int = psi.hashCode()

    override fun hasDefaultImpl(base: PklBaseModule): Boolean = true
  }

  /**
   * Note: Function types, sucb as `(String) -> Int`, are normalized to the corresponding class
   * type, such as `Function1<String, Int>`. All such types are rendered in function type notation.
   */
  class Class(
    val psi: PklClass,
    specifiedTypeArguments: List<Type> = listOf(),
    constraints: List<ConstraintExpr> = listOf(),
    // enables the illusion that pkl.base#Class and pkl.base#TypeAlias
    // have a type parameter even though they currently don't
    private val typeParameters: List<PklTypeParameter> = psi.typeParameterList?.elements ?: listOf()
  ) : Type(constraints) {
    val typeArguments: List<Type> =
      when {
        typeParameters.size <= specifiedTypeArguments.size ->
          specifiedTypeArguments.take(typeParameters.size)
        else ->
          specifiedTypeArguments +
            List(typeParameters.size - specifiedTypeArguments.size) { Unknown }
      }

    override val bindings: TypeParameterBindings = typeParameters.zip(typeArguments).toMap()

    override fun withConstraints(constraints: List<ConstraintExpr>): Type =
      Class(psi, typeArguments, constraints, typeParameters)

    fun withTypeArguments(argument1: Type) =
      Class(psi, listOf(argument1), constraints, typeParameters)

    fun withTypeArguments(argument1: Type, argument2: Type) =
      Class(psi, listOf(argument1, argument2), constraints, typeParameters)

    fun withTypeArguments(arguments: List<Type>) =
      Class(psi, arguments, constraints, typeParameters)

    override fun visitMembers(
      isProperty: Boolean,
      allowClasses: Boolean,
      base: PklBaseModule,
      visitor: ResolveVisitor<*>
    ): Boolean {
      return psi.cache.visitPropertiesOrMethods(isProperty, bindings, visitor)
    }

    override fun render(builder: Appendable, nameRenderer: TypeNameRenderer) {
      if (isConcreteFunctionType) {
        for ((index, type) in typeArguments.withIndex()) {
          when {
            index == 0 && typeArguments.lastIndex == 0 -> builder.append("() -> ")
            index == 0 -> builder.append('(')
            index == typeArguments.lastIndex -> builder.append(") -> ")
            else -> builder.append(", ")
          }
          type.render(builder, nameRenderer)
        }
        return
      }

      nameRenderer.render(this, builder)
      if (typeArguments.any { it != Unknown }) {
        builder.append('<')
        var first = true
        for (arg in typeArguments) {
          if (first) first = false else builder.append(", ")
          arg.render(builder, nameRenderer)
        }
        builder.append('>')
      }
    }

    override fun isSubtypeOf(classType: Class, base: PklBaseModule): Boolean {
      // optimization
      if (classType.psi === base.anyType.psi) return true

      if (!psi.isSubclassOf(classType.psi)) return false

      if (typeArguments.isEmpty()) {
        assert(classType.typeArguments.isEmpty()) // holds for stdlib
        return true
      }

      val size = typeArguments.size
      val otherSize = classType.typeArguments.size
      assert(size >= otherSize) // holds for stdlib

      for (i in 1..otherSize) {
        // assume [typeArg] maps directly to [otherTypeArg] in extends clause(s) (holds for stdlib)
        val typeArg = typeArguments[size - i]
        val typeParam = typeParameters[size - i]
        val otherTypeArg = classType.typeArguments[otherSize - i]
        val isMatch =
          when (typeParam.firstChildTokenType()) {
            PklElementTypes.OUT -> typeArg.isSubtypeOf(otherTypeArg, base) // covariance
            PklElementTypes.IN -> otherTypeArg.isSubtypeOf(typeArg, base) // contravariance
            else -> typeArg.isEquivalentTo(otherTypeArg, base) // invariance
          }
        if (!isMatch) return false
      }
      return true
    }

    override fun isSubtypeOf(type: Type, base: PklBaseModule): Boolean =
      when (type) {
        is Module -> psi.isSubclassOf(type.psi)
        else -> doIsSubtypeOf(type, base)
      }

    // assumes `!this.isSubtypeOf(type)`
    override fun hasCommonSubtypeWith(type: Type, base: PklBaseModule): Boolean =
      when (type) {
        is Class -> hasCommonSubtypeWith(type, base)
        is Module -> type.isSubtypeOf(this, base)
        else -> doHasCommonSubtypeWith(type, base)
      }

    override fun isUnresolvedMemberFatal(base: PklBaseModule): Boolean = !psi.isAbstractOrOpen

    // assumes `!this.isSubtypeOf(type)`
    private fun hasCommonSubtypeWith(type: Class, base: PklBaseModule): Boolean {
      // optimization
      if (psi === base.anyType.psi) return true

      if (typeArguments.isEmpty()) {
        if (type.typeArguments.isNotEmpty()) return false // holds for stdlib
        assert(!psi.isSubclassOf(type.psi)) // due to `!this.isSubtypeOf(type)`
        return psi.hasCommonSubclassWith(type.psi)
      }

      if (!psi.isSubclassOf(type.psi) && !psi.hasCommonSubclassWith(type.psi)) return false

      val size = typeArguments.size
      val otherSize = type.typeArguments.size

      for (i in 1..min(size, otherSize)) {
        // assume [typeArg] maps directly to [otherTypeArg] in extends clause(s) (holds for stdlib)
        val typeArg = typeArguments[size - i]
        val typeParam = typeParameters[size - i]
        val otherTypeArg = type.typeArguments[otherSize - i]
        val result =
          when (typeParam.firstChildTokenType()) {
            PklElementTypes.OUT -> { // covariance
              typeArg.isSubtypeOf(otherTypeArg, base) ||
                typeArg.hasCommonSubtypeWith(otherTypeArg, base)
            }
            PklElementTypes.IN -> { // contravariance
              // can always weaken `typeArg` (e.g., to `typeArg|otherTypeArg`)
              // so that `otherTypeArg` is a subtype
              true
            }
            else -> { // invariance
              typeArg.isEquivalentTo(otherTypeArg, base)
            }
          }
        if (!result) return false
      }
      return true
    }

    val isNullType: Boolean by lazy { psi.name == "Null" && psi.isInPklBaseModule }

    val isFunctionType: Boolean by lazy {
      val name = psi.name
      name != null &&
        (name.length == 8 || name.length == 9 && name.last() in '0'..'5') &&
        name.startsWith("Function") &&
        psi.isInPklBaseModule
    }

    val isConcreteFunctionType: Boolean by lazy {
      val name = psi.name
      name != null &&
        name.length == 9 &&
        name.last() in '0'..'5' &&
        name.startsWith("Function") &&
        psi.isInPklBaseModule
    }

    override fun amended(base: PklBaseModule): Type =
      when {
        classEquals(base.classType) -> typeArguments[0].amended(base)
        isFunctionType -> this
        isSubtypeOf(base.objectType, base) -> this
        else -> Nothing
      }

    override fun instantiated(base: PklBaseModule): Type =
      when {
        psi.isExternal -> Nothing
        psi.isAbstract -> Nothing
        else -> this
      }

    override fun amending(base: PklBaseModule): Type {
      return when {
        isSubtypeOf(base.objectType, base) -> this
        classEquals(base.classType) -> typeArguments[0].amending(base)
        isFunctionType -> uncurriedResultType(base).amending(base)
        else -> {
          // Return `Unknown` instead of `Nothing` to avoid consecutive errors
          // inside an erroneous amend expression's object body.
          // Ideally we'd return "`unknown` with upper bound `Object`",
          // but this cannot currently be expressed.
          Unknown
        }
      }
    }

    fun classEquals(other: Class): Boolean =
      psi === other.psi || areElementsEquivalent(psi, other.psi)

    override fun toClassType(base: PklBaseModule): Class = this

    override fun resolveToDefinitions(base: PklBaseModule): List<PklTypeDefOrModule> = listOf(psi)

    override fun equals(other: Any?): Boolean =
      this === other || other is Class && classEquals(other) && typeArguments == other.typeArguments

    // not sure if there's a way/need to have a hashCode() that's consistent with equals()
    override fun hashCode(): Int = psi.hashCode() * 31 + typeArguments.hashCode()

    override fun hasDefaultImpl(base: PklBaseModule): Boolean =
      when (base.objectType) {
        is Class -> isSubtypeOf(base.objectType as Class, base) && !psi.isAbstract
        else -> false
      }

    // returns `C` given `(A) -> (B) -> C`
    private fun uncurriedResultType(base: PklBaseModule): Type {
      assert(isFunctionType)

      var type = typeArguments.last()
      var classType = type.toClassType(base)
      while (classType != null && classType.isFunctionType) {
        type = classType.typeArguments.last()
        classType = type.toClassType(base)
      }
      return type
    }
  }

  // from a typing perspective, type aliases are transparent, but from a tooling/abstraction
  // perspective, they aren't.
  // this raises questions such as how to define Object.equals() and whether/how to support other
  // forms of equality.
  class Alias
  private constructor(
    val psi: PklTypeAlias,
    specifiedTypeArguments: List<Type>,
    constraints: List<ConstraintExpr>
  ) : Type(constraints) {
    companion object {
      /** Use [Type.alias] instead except in [PklBaseModule]. */
      internal fun unchecked(
        psi: PklTypeAlias,
        specifiedTypeArguments: List<Type>,
        constraints: List<ConstraintExpr>
      ): Alias = Alias(psi, specifiedTypeArguments, constraints)
    }

    private val typeParameters: List<PklTypeParameter>
      get() = psi.typeParameterList?.elements ?: listOf()

    val typeArguments: List<Type> =
      when {
        typeParameters.size <= specifiedTypeArguments.size ->
          specifiedTypeArguments.take(typeParameters.size)
        else ->
          specifiedTypeArguments +
            List(typeParameters.size - specifiedTypeArguments.size) { Unknown }
      }

    fun withTypeArguments(argument1: Type) = Alias(psi, listOf(argument1), constraints)

    override fun withConstraints(constraints: List<ConstraintExpr>): Type =
      Alias(psi, typeArguments, constraints)

    override val hasConstraints: Boolean
      get() = constraints.isNotEmpty() || psi.body.hasConstraints

    override val bindings: TypeParameterBindings = typeParameters.zip(typeArguments).toMap()

    fun aliasedType(base: PklBaseModule): Type = psi.body.toType(base, bindings)

    override fun visitMembers(
      isProperty: Boolean,
      allowClasses: Boolean,
      base: PklBaseModule,
      visitor: ResolveVisitor<*>
    ): Boolean {
      return psi.body.toType(base, bindings).visitMembers(isProperty, allowClasses, base, visitor)
    }

    override fun isSubtypeOf(classType: Class, base: PklBaseModule): Boolean =
      aliasedType(base).isSubtypeOf(classType, base)

    override fun isSubtypeOf(type: Type, base: PklBaseModule): Boolean =
      aliasedType(base).isSubtypeOf(type, base)

    override fun hasCommonSubtypeWith(type: Type, base: PklBaseModule): Boolean =
      aliasedType(base).hasCommonSubtypeWith(type, base)

    override fun isUnresolvedMemberFatal(base: PklBaseModule): Boolean =
      aliasedType(base).isUnresolvedMemberFatal(base)

    override fun toClassType(base: PklBaseModule): Class? = unaliased(base) as? Class

    override fun nonNull(base: PklBaseModule): Type {
      val aliasedType = aliasedType(base)
      return if (aliasedType.isNullable(base)) aliasedType.nonNull(base) else this
    }

    override fun unaliased(base: PklBaseModule): Type {
      var type: Type = this
      // guard against (invalid) cyclic type alias definition
      val seen = IdentityHashMap<PklTypeAlias, PklTypeAlias>()
      while (type is Alias) {
        val typePsi = type.psi
        // returning `type` here could cause infinite recursion in caller
        if (seen.put(typePsi, typePsi) != null) return Unknown
        type = typePsi.body.toType(base, type.bindings)
      }
      return type
    }

    override fun amended(base: PklBaseModule): Type {
      val aliased = aliasedType(base)
      val amended = aliased.amended(base)
      return if (aliased == amended) this else amended // keep alias if possible
    }

    override fun instantiated(base: PklBaseModule): Type {
      // special case: `Mixin` is instantiable even though `Function1` isn't
      if (areElementsEquivalent(psi, base.mixinType?.psi)) return this

      val aliased = aliasedType(base)
      val instantiated = aliased.instantiated(base)
      return if (aliased == instantiated) this else instantiated // keep alias if possible
    }

    override fun amending(base: PklBaseModule): Type = aliasedType(base).amending(base)

    override fun resolveToDefinitions(base: PklBaseModule): List<PklTypeDefOrModule> = listOf(psi)

    override fun render(builder: Appendable, nameRenderer: TypeNameRenderer) {
      nameRenderer.render(this, builder)
      if (typeArguments.any { it != Unknown }) {
        builder.append('<')
        var first = true
        for (arg in typeArguments) {
          if (first) first = false else builder.append(", ")
          arg.render(builder, nameRenderer)
        }
        builder.append('>')
      }
    }

    // should this operate on aliases or (recursively resolved) aliased types?
    override fun equals(other: Any?): Boolean =
      this === other ||
        other is Alias
        // not sure if we need this or whether psi identity is good enough
        && areElementsEquivalent(psi, other.psi) && typeArguments == other.typeArguments

    override fun hasDefaultImpl(base: PklBaseModule): Boolean =
      aliasedType(base).hasDefaultImpl(base)

    // not sure if there's a way/need to have a hashCode() that's consistent with equals()
    override fun hashCode(): Int = psi.hashCode() * 31 + typeArguments.hashCode()
  }

  class StringLiteral(val value: String, constraints: List<ConstraintExpr> = listOf()) :
    Type(constraints) {
    override fun withConstraints(constraints: List<ConstraintExpr>): Type =
      StringLiteral(value, constraints)

    override fun visitMembers(
      isProperty: Boolean,
      allowClasses: Boolean,
      base: PklBaseModule,
      visitor: ResolveVisitor<*>
    ): Boolean {
      return base.stringType.visitMembers(isProperty, allowClasses, base, visitor)
    }

    override fun isSubtypeOf(classType: Class, base: PklBaseModule): Boolean {
      return classType.classEquals(base.stringType) || classType.classEquals(base.anyType)
    }

    override fun isSubtypeOf(type: Type, base: PklBaseModule): Boolean =
      when (type) {
        is StringLiteral -> value == type.value
        else -> doIsSubtypeOf(type, base)
      }

    // assumes `!isSubtypeOf(type)`
    override fun hasCommonSubtypeWith(type: Type, base: PklBaseModule): Boolean = false

    override fun isUnresolvedMemberFatal(base: PklBaseModule): Boolean = true

    override fun resolveToDefinitions(base: PklBaseModule): List<PklTypeDefOrModule> =
      listOf(base.stringType.psi)

    override fun render(builder: Appendable, nameRenderer: TypeNameRenderer) = render(builder, "\"")

    fun render(builder: Appendable, startDelimiter: String) {
      builder
        .append(startDelimiter)
        .append(escapeString(value, startDelimiter))
        .append(startDelimiter.reversed())
    }

    fun render(startDelimiter: String) = buildString { render(this, startDelimiter) }

    override fun equals(other: Any?): Boolean =
      this === other || other is StringLiteral && value == other.value

    override fun hashCode(): Int = javaClass.hashCode() * 31 + value.hashCode()

    override fun hasDefaultImpl(base: PklBaseModule): Boolean = true

    override fun toString(): String = "\"$value\""
  }

  class Union
  private constructor(val leftType: Type, val rightType: Type, constraints: List<ConstraintExpr>) :
    Type(constraints) {
    companion object {
      // this method exists because `Type.union(t1, t2)` can't see the private constructor
      internal fun create(leftType: Type, rightType: Type, base: PklBaseModule): Type {
        val atMostOneTypeHasConstraints = !leftType.hasConstraints || !rightType.hasConstraints
        return when {
          // Only normalize if we don't lose relevant constraints in the process.
          // Note that if `a` is a subtype of `b` and `b` has no constraints, `a`'s constraints are
          // irrelevant.
          // Also don't normalize `String|"stringLiteral"` because we need the string literal type
          // for code completion.
          atMostOneTypeHasConstraints &&
            leftType.isSubtypeOf(rightType, base) &&
            rightType.unaliased(base) != base.stringType -> {
            rightType
          }
          atMostOneTypeHasConstraints &&
            rightType.isSubtypeOf(leftType, base) &&
            leftType.unaliased(base) != base.stringType -> {
            leftType
          }
          else -> Union(leftType, rightType, listOf())
        }
      }
    }

    override fun withConstraints(constraints: List<ConstraintExpr>): Type =
      Union(leftType, rightType, constraints)

    override val hasConstraints: Boolean
      get() = constraints.isNotEmpty() || leftType.hasConstraints || rightType.hasConstraints

    override fun isSubtypeOf(classType: Class, base: PklBaseModule): Boolean =
      leftType.isSubtypeOf(classType, base) && rightType.isSubtypeOf(classType, base)

    override fun isSubtypeOf(type: Type, base: PklBaseModule): Boolean =
      leftType.isSubtypeOf(type, base) && rightType.isSubtypeOf(type, base)

    // assumes `!this.isSubtypeOf(type)`
    override fun hasCommonSubtypeWith(type: Type, base: PklBaseModule): Boolean =
      leftType.isSubtypeOf(type, base) ||
        leftType.hasCommonSubtypeWith(type, base) ||
        rightType.isSubtypeOf(type, base) ||
        rightType.hasCommonSubtypeWith(type, base)

    override fun isUnresolvedMemberFatal(base: PklBaseModule): Boolean =
      leftType.isUnresolvedMemberFatal(base) && rightType.isUnresolvedMemberFatal(base)

    override fun toClassType(base: PklBaseModule): Class? =
      if (leftType.hasConstraints && rightType.hasConstraints) {
        // Ensure that `toClassType(CT(c1)|CT(c2)|CT(c3))`,
        // whose argument isn't normalized due to different constraints,
        // returns `CT`.
        leftType.toClassType(base)?.let { leftClassType ->
          rightType.toClassType(base)?.let { rightClassType ->
            if (leftClassType.classEquals(rightClassType)) leftClassType else null
          }
        }
      } else null

    override fun resolveToDefinitions(base: PklBaseModule): List<PklNavigableElement> =
      when {
        isUnionOfStringLiterals -> listOf(base.stringType.psi)
        else -> leftType.resolveToDefinitions(base) + rightType.resolveToDefinitions(base)
      }

    override fun nonNull(base: PklBaseModule): Type =
      when {
        leftType == base.nullType -> rightType.nonNull(base)
        rightType == base.nullType -> leftType.nonNull(base)
        else ->
          create(leftType.nonNull(base), rightType.nonNull(base), base).withConstraints(constraints)
      }

    override fun amended(base: PklBaseModule): Type =
      create(leftType.amended(base), rightType.amended(base), base).withConstraints(constraints)

    override fun hasDefaultImpl(base: PklBaseModule): Boolean = leftType.hasDefaultImpl(base)

    override fun instantiated(base: PklBaseModule): Type =
      create(leftType.instantiated(base), rightType.instantiated(base), base)

    override fun amending(base: PklBaseModule): Type =
      when {
        // assume this type is amendable (checked separately)
        // and remove alternatives that can't
        !leftType.isAmendable(base) -> rightType.amending(base)
        !rightType.isAmendable(base) -> leftType.amending(base)
        else ->
          create(leftType.amending(base), rightType.amending(base), base)
            .withConstraints(constraints)
      }

    override fun render(builder: Appendable, nameRenderer: TypeNameRenderer) {
      if (leftType is Class && leftType.isNullType) {
        val addParens = rightType is Union || rightType is Class && rightType.isConcreteFunctionType
        if (addParens) builder.append('(')
        rightType.render(builder, nameRenderer)
        if (addParens) builder.append(')')
        builder.append('?')
        return
      }

      leftType.render(builder, nameRenderer)
      builder.append('|')
      rightType.render(builder, nameRenderer)
    }

    override fun visitMembers(
      isProperty: Boolean,
      allowClasses: Boolean,
      base: PklBaseModule,
      visitor: ResolveVisitor<*>
    ): Boolean {
      if (isUnionOfStringLiterals) {
        // visit pkl.base#String once rather than for every string literal
        // (unions of 70+ string literals have been seen in the wild)
        return base.stringType.visitMembers(isProperty, allowClasses, base, visitor)
      }

      return leftType.visitMembers(isProperty, allowClasses, base, visitor) &&
        rightType.visitMembers(isProperty, allowClasses, base, visitor)
    }

    fun contains(type: Type): Boolean {
      return leftType == type ||
        rightType == type ||
        leftType is Union && leftType.contains(type) ||
        rightType is Union && rightType.contains(type)
    }

    fun eachElementType(processor: (Type) -> Unit) {
      if (leftType is Union) leftType.eachElementType(processor) else processor(leftType)
      if (rightType is Union) rightType.eachElementType(processor) else processor(rightType)
    }

    override fun equals(other: Any?): Boolean =
      when {
        this === other -> true
        other is Union ->
          when {
            leftType == other.leftType && rightType == other.rightType -> true
            leftType == other.rightType && rightType == other.leftType -> true
            else -> false
          }
        else -> false
      }

    override fun hashCode(): Int {
      // use straight sum to be consistent with equals()
      return leftType.hashCode() + rightType.hashCode()
    }

    val isUnionOfStringLiterals: Boolean by lazy {
      (leftType is StringLiteral || leftType is Union && leftType.isUnionOfStringLiterals) &&
        (rightType is StringLiteral || rightType is Union && rightType.isUnionOfStringLiterals)
    }
  }
}

typealias TypeParameterBindings = Map<PklTypeParameter, Type>

fun PklType?.toType(
  base: PklBaseModule,
  bindings: Map<PklTypeParameter, Type>,
  preserveUnboundTypeVars: Boolean = false
): Type =
  when (this) {
    null -> Unknown
    is PklDeclaredType -> {
      val simpleName = typeName.simpleName
      when (val resolved = simpleName.resolve()) {
        null -> Unknown
        is PklModule -> Type.module(resolved, simpleName.identifier.text)
        is PklClass -> {
          val typeArguments = typeArgumentList?.elements ?: listOf()
          Class(resolved, typeArguments.toTypes(base, bindings, preserveUnboundTypeVars))
        }
        is PklTypeAlias -> {
          val typeArguments = typeArgumentList?.elements ?: listOf()
          Type.alias(resolved, typeArguments.toTypes(base, bindings, preserveUnboundTypeVars))
        }
        is PklTypeParameter -> bindings[resolved]
            ?: if (preserveUnboundTypeVars) Variable(resolved) else Unknown
        else -> unexpectedType(resolved)
      }
    }
    is PklUnionType ->
      Type.union(
        leftType.toType(base, bindings, preserveUnboundTypeVars),
        rightType.toType(base, bindings, preserveUnboundTypeVars),
        base
      )
    is PklFunctionType -> {
      val parameterTypes =
        functionTypeParameterList.elements.toTypes(base, bindings, preserveUnboundTypeVars)
      val returnType = type.toType(base, bindings, preserveUnboundTypeVars)
      when (parameterTypes.size) {
        0 -> base.function0Type.withTypeArguments(parameterTypes + returnType)
        1 -> base.function1Type.withTypeArguments(parameterTypes + returnType)
        2 -> base.function2Type.withTypeArguments(parameterTypes + returnType)
        3 -> base.function3Type.withTypeArguments(parameterTypes + returnType)
        4 -> base.function4Type.withTypeArguments(parameterTypes + returnType)
        5 -> base.function5Type.withTypeArguments(parameterTypes + returnType)
        else ->
          base.functionType.withTypeArguments(
            listOf(returnType)
          ) // approximation (invalid Pkl code)
      }
    }
    is PklParenthesizedType -> type.toType(base, bindings, preserveUnboundTypeVars)
    is PklDefaultType -> type.toType(base, bindings, preserveUnboundTypeVars)
    is PklConstrainedType -> {
      val project = base.project
      val cacheManager = CachedValuesManager.getManager(project)
      val psiManager = PsiManager.getInstance(project)
      val constraintExprs =
        cacheManager.getCachedValue(this) {
          CachedValueProvider.Result.create(
            typeConstraintList.elements.toConstraintExprs(project.pklBaseModule),
            psiManager.modificationTracker.forLanguage(PklLanguage)
          )
        }
      type.toType(base, bindings, preserveUnboundTypeVars).withConstraints(constraintExprs)
    }
    is PklNullableType -> type.toType(base, bindings, preserveUnboundTypeVars).nullable(base)
    is PklUnknownType -> Unknown
    is PklNothingType -> Nothing
    is PklModuleType -> {
      // TODO: for `open` modules, `module` is a self-type
      enclosingModule?.let { Type.module(it, "module") } ?: base.moduleType
    }
    is PklStringLiteralType -> stringConstant.content.escapedText()?.let { StringLiteral(it) }
        ?: Unknown
    is PklTypeParameter -> bindings[this]
        ?: if (preserveUnboundTypeVars) Variable(this) else Unknown
    else -> unexpectedType(this)
  }

fun List<PklType>.toTypes(
  base: PklBaseModule,
  bindings: Map<PklTypeParameter, Type>,
  preserveTypeVariables: Boolean = false
): List<Type> = map { it.toType(base, bindings, preserveTypeVariables) }
