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

package org.pkl.intellij.resolve

import com.intellij.psi.PsiElement
import com.intellij.psi.util.parentOfType
import com.intellij.psi.util.parentOfTypes
import org.pkl.intellij.packages.dto.PklProject
import org.pkl.intellij.psi.*
import org.pkl.intellij.type.Type
import org.pkl.intellij.type.TypeParameterBindings
import org.pkl.intellij.type.computeThisType
import org.pkl.intellij.type.inferExprTypeFromContext

/**
 * Starting from the given lexical [PsiElement] context, locate all [PklElement]s which a type name
 * in that context could refer to, and feed them into the provided [ResolveVisitor]. Resolution of
 * [PklImport]s and substitution of [PklTypeParameter]s is left to the visitor.
 */
object Resolvers {
  enum class LookupMode {
    NONE,
    LEXICAL,
    IMPLICIT_THIS,
    BASE
  }

  fun <R> resolveQualifiedTypeName(
    position: PsiElement,
    moduleName: String,
    // receives elements of type PklTypeDef, PklImport, and PklTypeParameter
    visitor: ResolveVisitor<R>,
    context: PklProject?
  ): R {

    val enclosingModule = position.enclosingModule ?: return visitor.result

    for (import in enclosingModule.imports) {
      if (import.memberName == moduleName) {
        val importedModule =
          import.resolve(context) as? SimpleModuleResolutionResult ?: return visitor.result
        importedModule.resolved?.cache(context)?.visitTypes(visitor, context)
        return visitor.result
      }
    }

    return visitor.result
  }

  fun <R> resolveUnqualifiedTypeName(
    position: PsiElement,
    base: PklBaseModule,
    bindings: TypeParameterBindings,
    // receives elements of type PklTypeDef, PklImport, and PklTypeParameter
    visitor: ResolveVisitor<R>,
    context: PklProject?
  ): R {
    // search type parameters of enclosing method
    val method = position.parentOfType<PklClassMethod>()
    if (method != null) {
      if (!method.typeParameterList.visit(mapOf(), visitor, context)) return visitor.result
    }

    // search type parameters of enclosing class or type alias
    val typeDef = position.parentOfTypes(PklTypeDef::class)
    if (typeDef != null) {
      if (!typeDef.typeParameterList.visit(bindings, visitor, context)) return visitor.result
    }

    // search enclosing module
    val module = position.enclosingModule
    if (module != null) {
      for (import in module.imports) {
        // globs do not import a type
        if (import.isGlob) continue
        if (!visitor.visitIfNotNull(import.memberName, import, mapOf(), context))
          return visitor.result
      }
      for (member in module.typeDefs) {
        if (!visitor.visitIfNotNull(member.name, member, mapOf(), context)) return visitor.result
      }

      // search supermodules
      val supermodule = module.supermodule(context)
      if (supermodule != null) {
        if (!supermodule.cache(context).visitTypes(visitor, context)) return visitor.result
      }
    }

    // search pkl.base
    base.psi.cache(context).visitTypes(visitor, context)
    return visitor.result
  }

  /** Note: For resolving [PklAccessExpr], use [PklAccessExpr.resolve] instead. */
  fun <R> resolveUnqualifiedAccess(
    position: PsiElement,
    // optionally provide the type of `this` at [position]
    // to avoid its recomputation in case it is needed
    thisType: Type?,
    isProperty: Boolean,
    base: PklBaseModule,
    bindings: TypeParameterBindings,
    visitor: ResolveVisitor<R>,
    context: PklProject?
  ): R {
    return resolveUnqualifiedAccess(
      position,
      thisType,
      isProperty,
      true,
      base,
      bindings,
      visitor,
      context
    )
  }

  fun <R> resolveUnqualifiedAccess(
    position: PsiElement,
    // optionally provide the type of `this` at [position]
    // to avoid its recomputation in case it is needed
    thisType: Type?,
    isProperty: Boolean,
    allowClasses: Boolean,
    base: PklBaseModule,
    bindings: TypeParameterBindings,
    visitor: ResolveVisitor<R>,
    context: PklProject?
  ): R {

    return if (isProperty) {
      resolveUnqualifiedVariableAccess(
          position,
          thisType,
          base,
          bindings,
          allowClasses,
          visitor,
          context
        )
        .first
    } else {
      resolveUnqualifiedMethodAccess(position, thisType, base, bindings, visitor, context).first
    }
  }

  fun <R> resolveUnqualifiedAccessAndLookupMode(
    position: PsiElement,
    // optionally provide the type of `this` at [position]
    // to avoid its recomputation in case it is needed
    thisType: Type?,
    isProperty: Boolean,
    allowClasses: Boolean,
    base: PklBaseModule,
    bindings: TypeParameterBindings,
    visitor: ResolveVisitor<R>,
    context: PklProject?
  ): Pair<R, LookupMode> {
    return if (isProperty) {
      resolveUnqualifiedVariableAccess(
        position,
        thisType,
        base,
        bindings,
        allowClasses,
        visitor,
        context
      )
    } else {
      resolveUnqualifiedMethodAccess(position, thisType, base, bindings, visitor, context)
    }
  }

  /** Note: For resolving [PklAccessExpr], use [PklAccessExpr.resolve] instead. */
  fun <R> resolveQualifiedAccess(
    receiverType: Type,
    isProperty: Boolean,
    base: PklBaseModule,
    visitor: ResolveVisitor<R>,
    context: PklProject?
  ): R {

    receiverType.visitMembers(isProperty, allowClasses = true, base, visitor, context)
    return visitor.result
  }

  private fun PklTypeParameterList?.visit(
    bindings: TypeParameterBindings,
    visitor: ResolveVisitor<*>,
    context: PklProject?
  ): Boolean {
    if (this == null) return true

    for (parameter in elements) {
      val parameterName = parameter.identifier.text
      if (!visitor.visit(parameterName, parameter, bindings, context)) return false
    }

    return true
  }

  private fun <R> resolveUnqualifiedVariableAccess(
    position: PsiElement,
    thisType: Type?,
    base: PklBaseModule,
    bindings: TypeParameterBindings,
    allowClasses: Boolean,
    visitor: ResolveVisitor<R>,
    context: PklProject?
  ): Pair<R, LookupMode> {

    var element: PsiElement? = position
    var skipNextObjectBody = false

    while (element != null) {
      when (element) {
        is PklExpr -> {
          if (element is PklFunctionLiteral) {
            val functionType = element.inferExprTypeFromContext(base, bindings, context)
            for (parameter in element.parameterList.elements) {
              if (
                !visitor.visitIfNotNull(
                  parameter.identifier.text,
                  parameter,
                  functionType.bindings,
                  context
                )
              )
                return visitor.result to LookupMode.LEXICAL
            }
          }
          when (val parent = element.parent) {
            is PklForGenerator -> {
              // members of directly enclosing object aren't in scope of iterable expression
              skipNextObjectBody = true
            }
            is PklWhenGenerator -> {
              // members of directly enclosing object aren't in scope of condition expression
              skipNextObjectBody = true
            }
            is PklLetExpr -> {
              if (element === parent.bodyExpr) {
                parent.typedIdentifier?.let { typedId ->
                  if (!visitor.visitIfNotNull(typedId.identifier.text, typedId, bindings, context))
                    return visitor.result to LookupMode.LEXICAL
                }
              }
            }
            // flow typing of `if (expr) ... else ...`
            is PklIfExpr -> {
              when {
                element === parent.thenExpr -> {
                  parent.conditionExpr?.let { condExpr ->
                    if (!visitSatisfiedCondition(condExpr, bindings, visitor, context))
                      return visitor.result to LookupMode.NONE
                  }
                }
                element === parent.elseExpr -> {
                  parent.conditionExpr?.let { condExpr ->
                    if (!visitUnsatisfiedCondition(condExpr, bindings, visitor, context))
                      return visitor.result to LookupMode.NONE
                  }
                }
              }
            }
            // flow typing of `expr && ...`
            is PklLogicalAndBinExpr -> {
              if (element === parent.rightExpr) {
                if (!visitSatisfiedCondition(parent.leftExpr, bindings, visitor, context))
                  return visitor.result to LookupMode.NONE
              }
            }
            // flow typing of `expr || ...`
            is PklLogicalOrBinExpr -> {
              if (element === parent.rightExpr) {
                if (!visitUnsatisfiedCondition(parent.leftExpr, bindings, visitor, context))
                  return visitor.result to LookupMode.NONE
              }
            }
          }
        }
        is PklObjectBody -> {
          if (skipNextObjectBody) {
            skipNextObjectBody = false
          } else {
            for (member in element.properties) {
              if (!visitor.visitIfNotNull(member.name, member, bindings, context))
                return visitor.result to LookupMode.LEXICAL
            }
            element.parameterList?.elements?.let { parameterList ->
              for (parameter in parameterList) {
                if (
                  !visitor.visitIfNotNull(parameter.identifier.text, parameter, bindings, context)
                )
                  return visitor.result to LookupMode.LEXICAL
              }
            }
          }

          // flow typing of `when (expr) { ... } else { ... }`
          val parent = element.parent
          if (parent is PklWhenGenerator) {
            when {
              element === parent.thenBody -> {
                parent.conditionExpr?.let { condExpr ->
                  if (!visitSatisfiedCondition(condExpr, bindings, visitor, context))
                    return visitor.result to LookupMode.NONE
                }
              }
              element === parent.elseBody -> {
                parent.conditionExpr?.let { condExpr ->
                  if (!visitUnsatisfiedCondition(condExpr, bindings, visitor, context))
                    return visitor.result to LookupMode.NONE
                }
              }
            }
          }
        }
        is PklForGenerator -> {
          for (typedId in element.keyValueVars) {
            if (!visitor.visitIfNotNull(typedId.identifier.text, typedId, bindings, context))
              return visitor.result to LookupMode.LEXICAL
          }
        }
        is PklMethod -> {
          element.parameterList?.elements?.let { parameterList ->
            for (parameter in parameterList) {
              if (!visitor.visitIfNotNull(parameter.identifier.text, parameter, bindings, context))
                return visitor.result to LookupMode.LEXICAL
            }
          }
        }
        is PklClass -> {
          for (property in element.properties) {
            if (!visitor.visitIfNotNull(property.name, property, bindings, context))
              return visitor.result to LookupMode.LEXICAL
          }
        }
        is PklModule -> {
          for (import in element.imports) {
            if (!visitor.visitIfNotNull(import.memberName, import, bindings, context))
              return visitor.result to LookupMode.LEXICAL
          }
          val members = if (allowClasses) element.typeDefsAndProperties else element.properties
          for (member in members) {
            if (!visitor.visitIfNotNull(member.name, member, bindings, context))
              return visitor.result to LookupMode.LEXICAL
          }
        }
      }
      element = element.parent
    }

    // if resolve happens within base module, this is a redundant lookup, but it shouldn't hurt
    if (allowClasses) {
      if (!base.psi.cache(context).visitTypeDefsAndProperties(visitor, context))
        return visitor.result to LookupMode.BASE
    } else {
      if (!base.psi.cache(context).visitProperties(visitor, context))
        return visitor.result to LookupMode.BASE
    }

    val myThisType = thisType ?: position.computeThisType(base, bindings, context)
    myThisType.visitMembers(isProperty = true, allowClasses, base, visitor, context)

    return visitor.result to LookupMode.IMPLICIT_THIS
  }

  /** Propagates flow typing information from a satisfied boolean expression. */
  fun visitSatisfiedCondition(
    expr: PklExpr,
    bindings: TypeParameterBindings,
    visitor: ResolveVisitor<*>,
    context: PklProject?
  ): Boolean {
    if (visitor !is FlowTypingResolveVisitor<*>) return true

    return when (expr) {
      // foo is Bar
      is PklTypeTestExpr -> {
        val leftExpr = expr.expr
        if (leftExpr is PklUnqualifiedAccessExpr && leftExpr.isPropertyAccess) {
          visitor.visitHasType(leftExpr.memberNameText, expr.type, bindings, false, context)
        } else if (leftExpr is PklThisExpr) {
          visitor.visitHasType(leftExpr.text, expr.type, bindings, false, context)
        } else true
      }
      // foo != null, null != foo
      is PklEqualityBinExpr -> {
        val leftExpr = expr.leftExpr
        val rightExpr = expr.rightExpr
        if (expr.operator.elementType == PklElementTypes.NOT_EQUAL) {
          if (
            leftExpr is PklUnqualifiedAccessExpr &&
              leftExpr.isPropertyAccess &&
              expr.rightExpr is PklNullLiteral
          ) {
            visitor.visitEqualsConstant(leftExpr.memberNameText, null, true, context)
          } else if (
            rightExpr is PklUnqualifiedAccessExpr &&
              rightExpr.isPropertyAccess &&
              expr.leftExpr is PklNullLiteral
          ) {
            visitor.visitEqualsConstant(rightExpr.memberNameText, null, true, context)
          } else true
        } else true
      }
      // leftExpr && rightExpr
      is PklLogicalAndBinExpr -> {
        // Go right to left, effectively treating `rightExpr` as inner scope.
        // This has the following effect on type resolution
        // (note that `is` terminates resolution):
        // foo is Foo && foo is Bar -> Bar
        // foo is Foo? && foo != null -> Foo
        // foo != null && foo is Foo? -> Foo?
        // This should be good enough for now.
        // As long Pkl doesn't have interface types,
        // there is no good reason to write `foo is Foo && foo is Bar`,
        // and use cases for `foo != null && foo is Foo?`
        // (or `if (foo != null) if (foo is Foo?)`) are limited.
        val rightExpr = expr.rightExpr
        (rightExpr == null || visitSatisfiedCondition(rightExpr, bindings, visitor, context)) &&
          visitSatisfiedCondition(expr.leftExpr, bindings, visitor, context)
      }
      is PklLogicalNotExpr -> {
        val childExpr = expr.expr
        childExpr == null || visitUnsatisfiedCondition(childExpr, bindings, visitor, context)
      }
      is PklParenthesizedExpr -> {
        val childExpr = expr.expr
        childExpr == null || visitSatisfiedCondition(childExpr, bindings, visitor, context)
      }
      else -> true
    }
  }

  /** Propagates flow typing information from an unsatisfied boolean expression. */
  private fun visitUnsatisfiedCondition(
    expr: PklExpr,
    bindings: TypeParameterBindings,
    visitor: ResolveVisitor<*>,
    context: PklProject?
  ): Boolean {
    if (visitor !is FlowTypingResolveVisitor<*>) return true

    return when (expr) {
      // foo is Bar -negated-> !(foo is Bar)
      is PklTypeTestExpr -> {
        val leftExpr = expr.expr
        if (leftExpr is PklUnqualifiedAccessExpr && leftExpr.isPropertyAccess) {
          visitor.visitHasType(leftExpr.memberNameText, expr.type, bindings, true, context)
        } else if (leftExpr is PklThisExpr) {
          visitor.visitHasType(leftExpr.text, expr.type, bindings, true, context)
        } else true
      }
      // foo == null -negated-> foo != null
      // null == foo -negated-> null != foo
      is PklEqualityBinExpr -> {
        val leftExpr = expr.leftExpr
        val rightExpr = expr.rightExpr
        if (expr.operator.elementType == PklElementTypes.EQUAL) {
          if (
            leftExpr is PklUnqualifiedAccessExpr &&
              leftExpr.isPropertyAccess &&
              expr.rightExpr is PklNullLiteral
          ) {
            visitor.visitEqualsConstant(leftExpr.memberNameText, null, true, context)
          } else if (
            rightExpr is PklUnqualifiedAccessExpr &&
              rightExpr.isPropertyAccess &&
              expr.leftExpr is PklNullLiteral
          ) {
            visitor.visitEqualsConstant(rightExpr.memberNameText, null, true, context)
          } else true
        } else true
      }
      // leftExpr || rightExpr -negated-> !leftExpr && !rightExpr
      is PklLogicalOrBinExpr -> {
        val rightExpr = expr.rightExpr
        (rightExpr == null || visitUnsatisfiedCondition(rightExpr, bindings, visitor, context)) &&
          visitUnsatisfiedCondition(expr.leftExpr, bindings, visitor, context)
      }
      // !expr -negated-> expr
      is PklLogicalNotExpr -> {
        val childExpr = expr.expr
        childExpr == null || visitSatisfiedCondition(childExpr, bindings, visitor, context)
      }
      // (expr) -negated-> !expr
      is PklParenthesizedExpr -> {
        val childExpr = expr.expr
        childExpr == null || visitUnsatisfiedCondition(childExpr, bindings, visitor, context)
      }
      else -> true
    }
  }

  private fun <R> resolveUnqualifiedMethodAccess(
    position: PsiElement,
    thisType: Type?,
    base: PklBaseModule,
    bindings: TypeParameterBindings,
    visitor: ResolveVisitor<R>,
    context: PklProject?
  ): Pair<R, LookupMode> {

    var element: PsiElement? = position

    while (element != null) {
      when (element) {
        is PklObjectBody -> {
          for (member in element.methods) {
            if (!visitor.visitIfNotNull(member.name, member, bindings, context))
              return visitor.result to LookupMode.LEXICAL
          }
        }
        is PklClass -> {
          for (method in element.methods) {
            if (!visitor.visitIfNotNull(method.name, method, bindings, context))
              return visitor.result to LookupMode.LEXICAL
          }
        }
        is PklModule -> {
          for (method in element.methods) {
            if (!visitor.visitIfNotNull(method.name, method, bindings, context))
              return visitor.result to LookupMode.LEXICAL
          }
        }
      }
      element = element.parent
    }

    // if resolve happens within base module, this is a redundant lookup, but it shouldn't hurt
    if (!base.psi.cache(context).visitMethods(visitor, context))
      return visitor.result to LookupMode.BASE

    val myThisType = thisType ?: position.computeThisType(base, bindings, context)
    myThisType.visitMembers(isProperty = false, allowClasses = true, base, visitor, context)

    return visitor.result to LookupMode.IMPLICIT_THIS
  }
}
