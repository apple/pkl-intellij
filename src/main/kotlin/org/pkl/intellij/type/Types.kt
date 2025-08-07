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
@file:Suppress("DuplicatedCode")

package org.pkl.intellij.type

import com.intellij.psi.PsiManager
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.ParameterizedCachedValueProvider
import java.util.*
import kotlin.math.min
import org.pkl.intellij.PklLanguage
import org.pkl.intellij.cacheKeyService
import org.pkl.intellij.packages.dto.PklProject
import org.pkl.intellij.packages.pklProjectService
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
      context: PklProject?,
      specifiedTypeArguments: List<Type> = listOf(),
      constraints: List<ConstraintExpr> = listOf()
    ): Type =
      // Note: this is incomplete in that it doesn't detect the case
      // where recursion is introduced via type argument:
      // typealias Alias<T> = T|Boolean
      // p: Alias<Alias<String>>
      if (psi.isRecursive(context)) Unknown
      else Alias.unchecked(psi, specifiedTypeArguments, constraints)

    fun module(psi: PklModule, referenceName: String, context: PklProject?): Module =
      Module.create(psi, referenceName, context)

    fun union(type1: Type, type2: Type, base: PklBaseModule, context: PklProject?): Type =
      Union.create(type1, type2, base, context)

    fun union(
      type1: Type,
      type2: Type,
      type3: Type,
      base: PklBaseModule,
      context: PklProject?
    ): Type = Union.create(Union.create(type1, type2, base, context), type3, base, context)

    fun union(
      type1: Type,
      type2: Type,
      type3: Type,
      type4: Type,
      base: PklBaseModule,
      context: PklProject?
    ): Type =
      Union.create(
        Union.create(Union.create(type1, type2, base, context), type3, base, context),
        type4,
        base,
        context
      )

    fun union(
      type1: Type,
      type2: Type,
      type3: Type,
      type4: Type,
      type5: Type,
      base: PklBaseModule,
      context: PklProject?
    ): Type =
      Union.create(
        Union.create(
          Union.create(Union.create(type1, type2, base, context), type3, base, context),
          type4,
          base,
          context
        ),
        type5,
        base,
        context
      )

    fun union(
      type1: Type,
      type2: Type,
      type3: Type,
      type4: Type,
      type5: Type,
      type6: Type,
      base: PklBaseModule,
      context: PklProject?
    ): Type =
      Union.create(
        Union.create(
          Union.create(
            Union.create(Union.create(type1, type2, base, context), type3, base, context),
            type4,
            base,
            context
          ),
          type5,
          base,
          context
        ),
        type6,
        base,
        context
      )

    fun union(types: List<Type>, base: PklBaseModule, context: PklProject?): Type =
      types.reduce { t1, t2 -> Union.create(t1, t2, base, context) }

    fun function1(param1Type: Type, returnType: Type, base: PklBaseModule): Type =
      base.function1Type.withTypeArguments(param1Type, returnType)
  }

  open val hasConstraints: Boolean = constraints.isNotEmpty()

  abstract fun withConstraints(constraints: List<ConstraintExpr>): Type

  abstract fun visitMembers(
    isProperty: Boolean,
    allowClasses: Boolean,
    base: PklBaseModule,
    visitor: ResolveVisitor<*>,
    context: PklProject?,
  ): Boolean

  abstract fun resolveToDefinitions(base: PklBaseModule): List<PklNavigableElement>

  /** Tells whether this type is a (non-strict) subtype of [classType]. */
  abstract fun isSubtypeOf(classType: Class, base: PklBaseModule, context: PklProject?): Boolean

  /** Tells whether this type is a (non-strict) subtype of [type]. */
  abstract fun isSubtypeOf(type: Type, base: PklBaseModule, context: PklProject?): Boolean

  fun hasDefault(base: PklBaseModule, context: PklProject?) =
    if (isNullable(base)) true else hasDefaultImpl(base, context)

  protected abstract fun hasDefaultImpl(base: PklBaseModule, context: PklProject?): Boolean

  /** Helper for implementing [isSubtypeOf]. */
  protected fun doIsSubtypeOf(type: Type, base: PklBaseModule, context: PklProject?): Boolean =
    when (type) {
      Unknown -> true
      is Class -> isSubtypeOf(type, base, context)
      is Alias -> isSubtypeOf(type.aliasedType(base, context), base, context)
      is Union ->
        isSubtypeOf(type.leftType, base, context) || isSubtypeOf(type.rightType, base, context)
      else -> false
    }

  /** Note that `unknown` is equivalent to every type. */
  fun isEquivalentTo(type: Type, base: PklBaseModule, context: PklProject?): Boolean =
    isSubtypeOf(type, base, context) && type.isSubtypeOf(this, base, context)

  /**
   * Tells if there is a refinement of this type that is a subtype of [type]. The trivial
   * refinements `nothing` and `unkown` are not considered valid answers. Assumes
   * `!isSubtypeOf(type)`.
   *
   * The motivation for this method is to check if `!isSubtypeOf(type)` could be caused by the type
   * system being too weak, which is only the case if `hasCommonSubtypeWith(type)`.
   */
  abstract fun hasCommonSubtypeWith(type: Type, base: PklBaseModule, context: PklProject?): Boolean

  /** Helper for implementing [hasCommonSubtypeWith]. */
  protected fun doHasCommonSubtypeWith(
    type: Type,
    base: PklBaseModule,
    context: PklProject?
  ): Boolean =
    when (type) {
      is Alias -> hasCommonSubtypeWith(type.aliasedType(base, context), base, context)
      is Union ->
        hasCommonSubtypeWith(type.leftType, base, context) ||
          hasCommonSubtypeWith(type.rightType, base, context)
      else -> true
    }

  /**
   * Tells whether an unresolved member should be reported as error (rather than warning) for this
   * type.
   *
   * Implementations should return `false` if there is a chance that the member is declared by a
   * subtype.
   */
  abstract fun isUnresolvedMemberFatal(base: PklBaseModule, context: PklProject?): Boolean

  open fun toClassType(base: PklBaseModule, context: PklProject?): Class? = null

  open fun unaliased(base: PklBaseModule, context: PklProject?): Type? = this

  open fun nonNull(base: PklBaseModule, context: PklProject?): Type =
    if (this == base.nullType) Nothing else this

  fun nullable(base: PklBaseModule, context: PklProject?): Type =
    union(base.nullType, this, base, context)

  open val bindings: TypeParameterBindings = mapOf()

  fun isNullable(base: PklBaseModule): Boolean = base.nullType.isSubtypeOf(this, base, null)

  fun isAmendable(base: PklBaseModule, context: PklProject?): Boolean =
    amended(base, context) != Nothing

  fun isInstantiable(base: PklBaseModule, context: PklProject?): Boolean =
    instantiated(base, context) != Nothing

  /**
   * The type of `expr {}` where `expr` has this type. Defaults to [Nothing], that is, not
   * amendable.
   */
  open fun amended(base: PklBaseModule, context: PklProject?): Type = Nothing

  /**
   * The type of `new T {}` where `T` is this type. (Assumption: `T` is exactly the instantiated
   * type, not a supertype.) Defaults to [Nothing], that is, not instantiable.
   */
  open fun instantiated(base: PklBaseModule, context: PklProject?): Type = amended(base, context)

  /**
   * Type inside an amend block whose parent has this type. Leniently defaults to [Unknown] (instead
   * of [Nothing]) because "cannot amend type" is reported separately.
   */
  open fun amending(base: PklBaseModule, context: PklProject?): Type = Unknown

  abstract fun render(builder: Appendable, nameRenderer: TypeNameRenderer = DefaultTypeNameRenderer)

  fun render(): String = buildString { render(this) }

  override fun toString(): String = render()

  object Unknown : Type() {
    override fun visitMembers(
      isProperty: Boolean,
      allowClasses: Boolean,
      base: PklBaseModule,
      visitor: ResolveVisitor<*>,
      context: PklProject?
    ): Boolean = true

    // Note: we aren't currently tracking constraints for unknown type (uncommon, would require a
    // class)
    override fun withConstraints(constraints: List<ConstraintExpr>): Type = this

    override fun amended(base: PklBaseModule, context: PklProject?): Type =
      // Ideally we'd return "`unknown` with upper bound `base.amendedType`",
      // but this cannot currently be expressed
      Unknown

    override fun amending(base: PklBaseModule, context: PklProject?): Type =
      // Ideally we'd return "`unknown` with upper bound `Object`",
      // but this cannot currently be expressed.
      Unknown

    override fun render(builder: Appendable, nameRenderer: TypeNameRenderer) {
      builder.append("unknown")
    }

    override fun resolveToDefinitions(base: PklBaseModule): List<PklTypeDefOrModule> = listOf()

    override fun isSubtypeOf(classType: Class, base: PklBaseModule, context: PklProject?): Boolean =
      true

    override fun isSubtypeOf(type: Type, base: PklBaseModule, context: PklProject?): Boolean = true

    // `unknown` is not considered a valid answer
    override fun hasCommonSubtypeWith(
      type: Type,
      base: PklBaseModule,
      context: PklProject?
    ): Boolean = false

    override fun isUnresolvedMemberFatal(base: PklBaseModule, context: PklProject?): Boolean = false

    override fun hasDefaultImpl(base: PklBaseModule, context: PklProject?): Boolean = false
  }

  object Nothing : Type() {
    override fun visitMembers(
      isProperty: Boolean,
      allowClasses: Boolean,
      base: PklBaseModule,
      visitor: ResolveVisitor<*>,
      context: PklProject?
    ): Boolean = true

    // constraints for bottom type aren't meaningful -> don't track them
    override fun withConstraints(constraints: List<ConstraintExpr>): Type = this

    override fun render(builder: Appendable, nameRenderer: TypeNameRenderer) {
      builder.append("nothing")
    }

    override fun resolveToDefinitions(base: PklBaseModule): List<PklTypeDefOrModule> = listOf()

    override fun isSubtypeOf(classType: Class, base: PklBaseModule, context: PklProject?): Boolean =
      true

    override fun isSubtypeOf(type: Type, base: PklBaseModule, context: PklProject?): Boolean = true

    // `nothing` is not considered a valid answer
    override fun hasCommonSubtypeWith(
      type: Type,
      base: PklBaseModule,
      context: PklProject?
    ): Boolean = false

    override fun isUnresolvedMemberFatal(base: PklBaseModule, context: PklProject?): Boolean = true

    override fun hasDefaultImpl(base: PklBaseModule, context: PklProject?): Boolean = false
  }

  class Variable(val psi: PklTypeParameter, constraints: List<ConstraintExpr> = listOf()) :
    Type(constraints) {
    override fun withConstraints(constraints: List<ConstraintExpr>): Type =
      Variable(psi, constraints)

    override fun visitMembers(
      isProperty: Boolean,
      allowClasses: Boolean,
      base: PklBaseModule,
      visitor: ResolveVisitor<*>,
      context: PklProject?
    ): Boolean = true

    override fun resolveToDefinitions(base: PklBaseModule): List<PklNavigableElement> = listOf(psi)

    override fun isSubtypeOf(classType: Class, base: PklBaseModule, context: PklProject?): Boolean =
      classType.classEquals(base.anyType)

    override fun isSubtypeOf(type: Type, base: PklBaseModule, context: PklProject?): Boolean =
      this == type || doIsSubtypeOf(type, base, context)

    override fun hasCommonSubtypeWith(
      type: Type,
      base: PklBaseModule,
      context: PklProject?
    ): Boolean = type.unaliased(base, context) != Nothing

    override fun isUnresolvedMemberFatal(base: PklBaseModule, context: PklProject?): Boolean =
      true // treat like `Any`

    override fun amended(base: PklBaseModule, context: PklProject?): Type = this

    override fun amending(base: PklBaseModule, context: PklProject?): Type = Unknown

    override fun render(builder: Appendable, nameRenderer: TypeNameRenderer) {
      builder.append(psi.name)
    }

    override fun equals(other: Any?): Boolean {
      return other is Variable && areElementsEquivalent(psi, other.psi)
    }

    override fun hashCode(): Int {
      return psi.hashCode()
    }

    override fun hasDefaultImpl(base: PklBaseModule, context: PklProject?): Boolean = false

    override fun toString(): String = psi.identifier.text
  }

  class Module
  private constructor(
    val psi: PklModule,
    val referenceName: String,
    constraints: List<ConstraintExpr>,
  ) : Type(constraints) {
    companion object {
      // this method exists because `Type.module()` can't see the private constructor
      internal fun create(
        psi: PklModule,
        referenceName: String,
        context: PklProject?,
        constraints: List<ConstraintExpr> = listOf()
      ): Module {
        var result = psi
        // a module's type is the topmost module in the module hierarchy that doesn't amend another
        // module.
        // if we can't resolve an amends reference, we bail out, i.e., invalid code may produce an
        // incorrect type.
        while (result.extendsAmendsClause.isAmend) {
          result = result.supermodule(context) ?: return Module(result, referenceName, constraints)
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
      visitor: ResolveVisitor<*>,
      context: PklProject?
    ): Boolean {
      return if (allowClasses) {
        psi.cache(context).visitTypeDefsAndPropertiesOrMethods(isProperty, visitor, context)
      } else {
        psi.cache(context).visitPropertiesOrMethods(isProperty, visitor, context)
      }
    }

    override fun resolveToDefinitions(base: PklBaseModule): List<PklTypeDefOrModule> = listOf(psi)

    override fun isSubtypeOf(classType: Class, base: PklBaseModule, context: PklProject?): Boolean =
      base.moduleType.isSubtypeOf(classType, base, context)

    override fun isSubtypeOf(type: Type, base: PklBaseModule, context: PklProject?): Boolean =
      when (type) {
        is Module -> isSubtypeOf(type, context)
        else -> doIsSubtypeOf(type, base, context)
      }

    private fun isSubtypeOf(type: Module, context: PklProject?): Boolean {
      var currPsi: PklModule? = psi
      while (currPsi != null) {
        // use psi equivalence to be consistent with equals()
        if (areElementsEquivalent(currPsi, type.psi)) return true
        currPsi = currPsi.supermodule(context)
      }
      return false
    }

    fun supermodule(context: PklProject?): Module? =
      psi.supermodule(context)?.let { module(it, it.shortDisplayName, context) }

    // assumes `!this.isSubtypeOf(type)`
    override fun hasCommonSubtypeWith(
      type: Type,
      base: PklBaseModule,
      context: PklProject?
    ): Boolean =
      when (type) {
        is Module -> type.isSubtypeOf(this, context)
        is Class -> type.isSubtypeOf(this, base, context)
        else -> doHasCommonSubtypeWith(type, base, context)
      }

    override fun isUnresolvedMemberFatal(base: PklBaseModule, context: PklProject?): Boolean =
      !psi.isAbstractOrOpen

    override fun amended(base: PklBaseModule, context: PklProject?): Type = this

    override fun amending(base: PklBaseModule, context: PklProject?): Type = this

    override fun render(builder: Appendable, nameRenderer: TypeNameRenderer) {
      nameRenderer.render(this, builder)
    }

    override fun equals(other: Any?): Boolean =
      // not certain which equality semantics we need (psi identity might be enough), but let's try
      // this one
      this === other || other is Module && areElementsEquivalent(psi, other.psi)

    // not sure if there's a way/need to have a hashCode() that's consistent with equals()
    override fun hashCode(): Int = psi.hashCode()

    override fun hasDefaultImpl(base: PklBaseModule, context: PklProject?): Boolean = true
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
    private val typeParameters: List<PklTypeParameter> =
      psi.typeParameterList?.elements ?: listOf(),
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
      visitor: ResolveVisitor<*>,
      context: PklProject?
    ): Boolean {
      return psi.cache(context).visitPropertiesOrMethods(isProperty, bindings, visitor, context)
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

    override fun isSubtypeOf(classType: Class, base: PklBaseModule, context: PklProject?): Boolean {
      // optimization
      if (classType.psi === base.anyType.psi) return true

      if (!psi.isSubclassOf(classType.psi, context)) return false

      if (typeArguments.isEmpty()) {
        assert(classType.typeArguments.isEmpty()) // holds for stdlib
      } else {
        val size = typeArguments.size
        val otherSize = classType.typeArguments.size
        assert(size >= otherSize) // holds for stdlib

        for (i in 1..otherSize) {
          // assume [typeArg] maps directly to [otherTypeArg] in extends clause(s) (holds for
          // stdlib)
          val typeArg = typeArguments[size - i]
          val typeParam = typeParameters[size - i]
          val otherTypeArg = classType.typeArguments[otherSize - i]
          val isMatch =
            when (typeParam.firstChildTokenType()) {
              PklElementTypes.OUT -> typeArg.isSubtypeOf(otherTypeArg, base, context) // covariance
              PklElementTypes.IN ->
                otherTypeArg.isSubtypeOf(typeArg, base, context) // contravariance
              else -> typeArg.isEquivalentTo(otherTypeArg, base, context) // invariance
            }
          if (!isMatch) return false
        }
      }

      // this class is a subtype of classType iff classType's constraints are a subset of this
      // class's constraints.
      return constraints.containsAll(classType.constraints)
    }

    override fun isSubtypeOf(type: Type, base: PklBaseModule, context: PklProject?): Boolean =
      when (type) {
        is Module -> psi.isSubclassOf(type.psi, context)
        else -> doIsSubtypeOf(type, base, context)
      }

    // assumes `!this.isSubtypeOf(type)`
    override fun hasCommonSubtypeWith(
      type: Type,
      base: PklBaseModule,
      context: PklProject?
    ): Boolean =
      when (type) {
        is Class -> hasCommonSubtypeWith(type, base, context)
        is Module -> type.isSubtypeOf(this, base, context)
        else -> doHasCommonSubtypeWith(type, base, context)
      }

    override fun isUnresolvedMemberFatal(base: PklBaseModule, context: PklProject?): Boolean =
      !psi.isAbstractOrOpen

    // assumes `!this.isSubtypeOf(type)`
    private fun hasCommonSubtypeWith(
      type: Class,
      base: PklBaseModule,
      context: PklProject?
    ): Boolean {
      // optimization
      if (psi === base.anyType.psi) return true

      if (typeArguments.isEmpty()) {
        if (type.typeArguments.isNotEmpty()) return false // holds for stdlib
        assert(!psi.isSubclassOf(type.psi, context)) // due to `!this.isSubtypeOf(type)`
        return psi.hasCommonSubclassWith(type.psi, context)
      }

      if (!psi.isSubclassOf(type.psi, context) && !psi.hasCommonSubclassWith(type.psi, context))
        return false

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
              typeArg.isSubtypeOf(otherTypeArg, base, context) ||
                typeArg.hasCommonSubtypeWith(otherTypeArg, base, context)
            }
            PklElementTypes.IN -> { // contravariance
              // can always weaken `typeArg` (e.g., to `typeArg|otherTypeArg`)
              // so that `otherTypeArg` is a subtype
              true
            }
            else -> { // invariance
              typeArg.isEquivalentTo(otherTypeArg, base, context)
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

    override fun amended(base: PklBaseModule, context: PklProject?): Type =
      when {
        classEquals(base.classType) -> typeArguments[0].amended(base, context)
        isFunctionType -> this
        isSubtypeOf(base.objectType, base, context) -> this
        else -> Nothing
      }

    override fun instantiated(base: PklBaseModule, context: PklProject?): Type =
      when {
        psi.isExternal -> Nothing
        psi.isAbstract -> Nothing
        else -> this
      }

    override fun amending(base: PklBaseModule, context: PklProject?): Type {
      return when {
        isSubtypeOf(base.objectType, base, context) -> this
        classEquals(base.classType) -> typeArguments[0].amending(base, context)
        isFunctionType -> uncurriedResultType(base, context).amending(base, context)
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

    override fun toClassType(base: PklBaseModule, context: PklProject?): Class = this

    override fun resolveToDefinitions(base: PklBaseModule): List<PklTypeDefOrModule> = listOf(psi)

    override fun equals(other: Any?): Boolean =
      this === other || other is Class && classEquals(other) && typeArguments == other.typeArguments

    // not sure if there's a way/need to have a hashCode() that's consistent with equals()
    override fun hashCode(): Int = psi.hashCode() * 31 + typeArguments.hashCode()

    override fun hasDefaultImpl(base: PklBaseModule, context: PklProject?): Boolean =
      when (base.objectType) {
        is Class -> isSubtypeOf(base.objectType, base, context) && !psi.isAbstract
        else -> false
      }

    // returns `C` given `(A) -> (B) -> C`
    private fun uncurriedResultType(base: PklBaseModule, context: PklProject?): Type {
      assert(isFunctionType)

      var type = typeArguments.last()
      var classType = type.toClassType(base, context)
      while (classType != null && classType.isFunctionType) {
        type = classType.typeArguments.last()
        classType = type.toClassType(base, context)
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
      get() = constraints.isNotEmpty() || psi.body.hasConstraints()

    override val bindings: TypeParameterBindings = typeParameters.zip(typeArguments).toMap()

    fun aliasedType(base: PklBaseModule, context: PklProject?): Type =
      psi.body.toType(base, bindings, context)

    override fun visitMembers(
      isProperty: Boolean,
      allowClasses: Boolean,
      base: PklBaseModule,
      visitor: ResolveVisitor<*>,
      context: PklProject?
    ): Boolean {
      return psi.body
        .toType(base, bindings, context)
        .visitMembers(isProperty, allowClasses, base, visitor, context)
    }

    override fun isSubtypeOf(classType: Class, base: PklBaseModule, context: PklProject?): Boolean =
      aliasedType(base, context).isSubtypeOf(classType, base, context)

    override fun isSubtypeOf(type: Type, base: PklBaseModule, context: PklProject?): Boolean =
      aliasedType(base, context).isSubtypeOf(type, base, context)

    override fun hasCommonSubtypeWith(
      type: Type,
      base: PklBaseModule,
      context: PklProject?
    ): Boolean = aliasedType(base, context).hasCommonSubtypeWith(type, base, context)

    override fun isUnresolvedMemberFatal(base: PklBaseModule, context: PklProject?): Boolean =
      aliasedType(base, context).isUnresolvedMemberFatal(base, context)

    override fun toClassType(base: PklBaseModule, context: PklProject?): Class? =
      unaliased(base, context) as? Class

    override fun nonNull(base: PklBaseModule, context: PklProject?): Type {
      val aliasedType = aliasedType(base, context)
      return if (aliasedType.isNullable(base)) aliasedType.nonNull(base, context) else this
    }

    override fun unaliased(base: PklBaseModule, context: PklProject?): Type {
      var type: Type = this
      // guard against (invalid) cyclic type alias definition
      val seen = IdentityHashMap<PklTypeAlias, PklTypeAlias>()
      while (type is Alias) {
        val typePsi = type.psi
        // returning `type` here could cause infinite recursion in caller
        if (seen.put(typePsi, typePsi) != null) return Unknown
        type = typePsi.body.toType(base, type.bindings, context)
      }
      return type
    }

    override fun amended(base: PklBaseModule, context: PklProject?): Type {
      val aliased = aliasedType(base, context)
      val amended = aliased.amended(base, context)
      return if (aliased == amended) this else amended // keep alias if possible
    }

    override fun instantiated(base: PklBaseModule, context: PklProject?): Type {
      // special case: `Mixin` is instantiable even though `Function1` isn't
      if (areElementsEquivalent(psi, base.mixinType.psi)) return this

      val aliased = aliasedType(base, context)
      val instantiated = aliased.instantiated(base, context)
      return if (aliased == instantiated) this else instantiated // keep alias if possible
    }

    override fun amending(base: PklBaseModule, context: PklProject?): Type =
      aliasedType(base, context).amending(base, context)

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

    override fun hasDefaultImpl(base: PklBaseModule, context: PklProject?): Boolean =
      aliasedType(base, context).hasDefaultImpl(base, context)

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
      visitor: ResolveVisitor<*>,
      context: PklProject?
    ): Boolean {
      return base.stringType.visitMembers(isProperty, allowClasses, base, visitor, context)
    }

    override fun isSubtypeOf(classType: Class, base: PklBaseModule, context: PklProject?): Boolean {
      return classType.classEquals(base.stringType) || classType.classEquals(base.anyType)
    }

    override fun isSubtypeOf(type: Type, base: PklBaseModule, context: PklProject?): Boolean =
      when (type) {
        is StringLiteral -> value == type.value
        else -> doIsSubtypeOf(type, base, context)
      }

    // assumes `!isSubtypeOf(type)`
    override fun hasCommonSubtypeWith(
      type: Type,
      base: PklBaseModule,
      context: PklProject?
    ): Boolean = false

    override fun isUnresolvedMemberFatal(base: PklBaseModule, context: PklProject?): Boolean = true

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

    override fun hasDefaultImpl(base: PklBaseModule, context: PklProject?): Boolean = true

    override fun toString(): String = "\"$value\""
  }

  class Union
  private constructor(
    val leftType: Type,
    val rightType: Type,
    constraints: List<ConstraintExpr>,
    private val context: PklProject?
  ) : Type(constraints) {
    companion object {
      // this method exists because `Type.union(t1, t2)` can't see the private constructor
      internal fun create(
        leftType: Type,
        rightType: Type,
        base: PklBaseModule,
        context: PklProject?
      ): Type {
        val atMostOneTypeHasConstraints = !leftType.hasConstraints || !rightType.hasConstraints
        return when {
          // Only normalize if we don't lose relevant constraints in the process.
          // Note that if `a` is a subtype of `b` and `b` has no constraints, `a`'s constraints are
          // irrelevant.
          // Also don't normalize `String|"stringLiteral"` because we need the string literal type
          // for code completion.
          atMostOneTypeHasConstraints &&
            leftType.isSubtypeOf(rightType, base, context) &&
            rightType.unaliased(base, context) != base.stringType -> {
            rightType
          }
          atMostOneTypeHasConstraints &&
            rightType.isSubtypeOf(leftType, base, context) &&
            leftType.unaliased(base, context) != base.stringType -> {
            leftType
          }
          else -> Union(leftType, rightType, listOf(), context)
        }
      }
    }

    override fun withConstraints(constraints: List<ConstraintExpr>): Type =
      Union(leftType, rightType, constraints, context)

    override val hasConstraints: Boolean
      get() = constraints.isNotEmpty() || leftType.hasConstraints || rightType.hasConstraints

    override fun isSubtypeOf(classType: Class, base: PklBaseModule, context: PklProject?): Boolean =
      leftType.isSubtypeOf(classType, base, context) &&
        rightType.isSubtypeOf(classType, base, context)

    override fun isSubtypeOf(type: Type, base: PklBaseModule, context: PklProject?): Boolean =
      leftType.isSubtypeOf(type, base, context) && rightType.isSubtypeOf(type, base, context)

    // assumes `!this.isSubtypeOf(type)`
    override fun hasCommonSubtypeWith(
      type: Type,
      base: PklBaseModule,
      context: PklProject?
    ): Boolean =
      leftType.isSubtypeOf(type, base, context) ||
        leftType.hasCommonSubtypeWith(type, base, context) ||
        rightType.isSubtypeOf(type, base, context) ||
        rightType.hasCommonSubtypeWith(type, base, context)

    override fun isUnresolvedMemberFatal(base: PklBaseModule, context: PklProject?): Boolean =
      leftType.isUnresolvedMemberFatal(base, context) &&
        rightType.isUnresolvedMemberFatal(base, context)

    override fun toClassType(base: PklBaseModule, context: PklProject?): Class? =
      if (leftType.hasConstraints && rightType.hasConstraints) {
        // Ensure that `toClassType(CT(c1)|CT(c2)|CT(c3))`,
        // whose argument isn't normalized due to different constraints,
        // returns `CT`.
        leftType.toClassType(base, context)?.let { leftClassType ->
          rightType.toClassType(base, context)?.let { rightClassType ->
            if (leftClassType.classEquals(rightClassType)) leftClassType else null
          }
        }
      } else null

    override fun resolveToDefinitions(base: PklBaseModule): List<PklNavigableElement> =
      when {
        isUnionOfStringLiterals -> listOf(base.stringType.psi)
        else -> leftType.resolveToDefinitions(base) + rightType.resolveToDefinitions(base)
      }

    override fun nonNull(base: PklBaseModule, context: PklProject?): Type =
      when {
        leftType == base.nullType -> rightType.nonNull(base, context)
        rightType == base.nullType -> leftType.nonNull(base, context)
        else ->
          create(leftType.nonNull(base, context), rightType.nonNull(base, context), base, context)
            .withConstraints(constraints)
      }

    override fun amended(base: PklBaseModule, context: PklProject?): Type =
      create(leftType.amended(base, context), rightType.amended(base, context), base, context)
        .withConstraints(constraints)

    override fun hasDefaultImpl(base: PklBaseModule, context: PklProject?): Boolean =
      leftType.hasDefaultImpl(base, context)

    override fun instantiated(base: PklBaseModule, context: PklProject?): Type =
      create(
        leftType.instantiated(base, context),
        rightType.instantiated(base, context),
        base,
        context
      )

    override fun amending(base: PklBaseModule, context: PklProject?): Type =
      when {
        // assume this type is amendable (checked separately)
        // and remove alternatives that can't
        !leftType.isAmendable(base, context) -> rightType.amending(base, context)
        !rightType.isAmendable(base, context) -> leftType.amending(base, context)
        else ->
          create(leftType.amending(base, context), rightType.amending(base, context), base, context)
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
      visitor: ResolveVisitor<*>,
      context: PklProject?
    ): Boolean {
      if (isUnionOfStringLiterals) {
        // visit pkl.base#String once rather than for every string literal
        // (unions of 70+ string literals have been seen in the wild)
        return base.stringType.visitMembers(isProperty, allowClasses, base, visitor, context)
      }

      return leftType.visitMembers(isProperty, allowClasses, base, visitor, context) &&
        rightType.visitMembers(isProperty, allowClasses, base, visitor, context)
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

    fun <T> map(f: (Type) -> T): List<T> {
      return buildList { eachElementType { add(f(it)) } }
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

    val cardinality: Int by lazy {
      val left = if (leftType is Union) leftType.cardinality else 1
      val right = if (rightType is Union) rightType.cardinality else 1
      left + right
    }
  }
}

typealias TypeParameterBindings = Map<PklTypeParameter, Type>

private val constraintExprProvider:
  ParameterizedCachedValueProvider<List<ConstraintExpr>, Pair<PklConstrainedType, PklProject?>> =
  ParameterizedCachedValueProvider { (elem, context) ->
    val project = elem.project
    val result = elem.typeConstraintList.elements.toConstraintExprs(project.pklBaseModule, context)
    val dependencies = buildList {
      add(PsiManager.getInstance(project).modificationTracker.forLanguage(PklLanguage))
      if (context != null) {
        add(project.pklProjectService)
      }
    }
    CachedValueProvider.Result.create(result, dependencies)
  }

fun PklType?.toType(
  base: PklBaseModule,
  bindings: Map<PklTypeParameter, Type>,
  context: PklProject?,
  preserveUnboundTypeVars: Boolean = false
): Type =
  when (this) {
    null -> Unknown
    is PklDeclaredType -> {
      val simpleName = typeName.simpleName
      when (val resolved = simpleName.resolve(context)) {
        null -> Unknown
        is PklModule -> Type.module(resolved, simpleName.identifier.text, context)
        is PklClass -> {
          val typeArguments = typeArgumentList?.elements ?: listOf()
          Class(resolved, typeArguments.toTypes(base, bindings, preserveUnboundTypeVars, context))
        }
        is PklTypeAlias -> {
          val typeArguments = typeArgumentList?.elements ?: listOf()
          Type.alias(
            resolved,
            context,
            typeArguments.toTypes(base, bindings, preserveUnboundTypeVars, context)
          )
        }
        is PklTypeParameter -> bindings[resolved]
            ?: if (preserveUnboundTypeVars) Variable(resolved) else Unknown
        else -> unexpectedType(resolved)
      }
    }
    is PklUnionType ->
      Type.union(
        leftType.toType(base, bindings, context, preserveUnboundTypeVars),
        rightType.toType(base, bindings, context, preserveUnboundTypeVars),
        base,
        context
      )
    is PklFunctionType -> {
      val parameterTypes =
        functionTypeParameterList.elements.toTypes(base, bindings, preserveUnboundTypeVars, context)
      val returnType = type.toType(base, bindings, context, preserveUnboundTypeVars)
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
    is PklParenthesizedType -> type.toType(base, bindings, context, preserveUnboundTypeVars)
    is PklDefaultType -> type.toType(base, bindings, context, preserveUnboundTypeVars)
    is PklConstrainedType -> {
      val project = base.project
      val constraintExprs =
        CachedValuesManager.getManager(project)
          .getParameterizedCachedValue(
            this,
            project.cacheKeyService.getKey("PklType.toType", context),
            constraintExprProvider,
            false,
            this to context
          )
      type.toType(base, bindings, context, preserveUnboundTypeVars).withConstraints(constraintExprs)
    }
    is PklNullableType ->
      type.toType(base, bindings, context, preserveUnboundTypeVars).nullable(base, context)
    is PklUnknownType -> Unknown
    is PklNothingType -> Nothing
    is PklModuleType -> {
      // TODO: for `open` modules, `module` is a self-type
      enclosingModule?.let { Type.module(it, "module", context) } ?: base.moduleType
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
  preserveTypeVariables: Boolean = false,
  context: PklProject?
): List<Type> = map { it.toType(base, bindings, context, preserveTypeVariables) }
