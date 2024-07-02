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
package org.pkl.intellij.annotator

import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.parentOfType
import com.intellij.psi.util.parentOfTypes
import com.intellij.refactoring.suggested.endOffset
import com.intellij.refactoring.suggested.startOffset
import org.pkl.intellij.PklVersion
import org.pkl.intellij.intention.*
import org.pkl.intellij.packages.dto.PklProject
import org.pkl.intellij.psi.*
import org.pkl.intellij.resolve.ResolveVisitors
import org.pkl.intellij.resolve.Resolvers
import org.pkl.intellij.type.*
import org.pkl.intellij.util.*

class PklExprAnnotator : PklAnnotator() {
  override fun doAnnotate(element: PsiElement, holder: AnnotationHolder) {
    if (element !is PklExpr) return

    val module = holder.currentModule
    val project = module.project
    val base = project.pklBaseModule
    val context = element.enclosingModule?.pklProject

    val expectedType = element.inferExprTypeFromContext(base, mapOf(), context)
    checkExprType(element, expectedType, base, holder)

    element.accept(
      object : PklVisitor<Unit>() {
        override fun visitUnqualifiedAccessExpr(element: PklUnqualifiedAccessExpr) {
          // don't resolve imports because whether import resolves is a separate issue/check
          val visitor =
            ResolveVisitors.firstElementNamed(
              element.memberNameText,
              base,
              resolveImports = false,
            )
          // resolving unqualified access may not require `this` type so don't compute/pass it
          // upfront
          val (target, lookupMode) =
            (element as PklUnqualifiedAccessExprBase).resolveAndGetLookupMode(
              base,
              null,
              mapOf(),
              visitor,
              context
            )
          when (target) {
            null -> {
              val thisType = element.computeThisType(base, mapOf(), context)
              if (
                thisType == Type.Unknown ||
                  (thisType == base.dynamicType && element.isPropertyAccess)
              ) {
                return // don't flag
              }
              reportUnresolvedAccess(element, thisType, base, holder, context)
            }
            is PklMethod -> {
              checkConstAccess(element, target, holder, lookupMode)
              checkArgumentCount(element, target, base, holder)
            }
            is PklProperty -> {
              checkConstAccess(element, target, holder, lookupMode)
              checkRecursivePropertyReference(element, target, holder)
            }
          }
        }

        override fun visitModuleExpr(o: PklModuleExpr) {
          checkConstAccess(o, holder)
        }

        override fun visitThisExpr(o: PklThisExpr) {
          checkConstAccess(o, holder)
        }

        override fun visitQualifiedAccessExpr(element: PklQualifiedAccessExpr) {
          val receiverType = element.receiverExpr.computeExprType(base, mapOf(), context)
          if (
            receiverType == Type.Unknown ||
              (receiverType == base.dynamicType && element.isPropertyAccess)
          ) {
            return // don't flag
          }

          val visitor =
            ResolveVisitors.firstElementNamed(
              element.memberNameText,
              base,
            )
          when (val target = element.resolve(base, receiverType, mapOf(), visitor, context)) {
            null -> {
              reportUnresolvedAccess(element, receiverType, base, holder, context)
            }
            is PklMethod -> {
              checkConstQualifiedAccess(element, target, holder)
              checkArgumentCount(element, target, base, holder)
              when (receiverType) {
                base.listType -> {
                  when {
                    target == base.listJoinMethod ||
                      target == base.listIsDistinctByMethod ||
                      target == base.listFoldMethod ||
                      target == base.listFoldIndexedMethod -> {
                      checkIsRedundantConversion(
                        element.receiverExpr,
                        base.listingToListMethod,
                        base,
                        holder,
                        context
                      )
                    }
                  }
                }
                base.mapType -> {
                  when {
                    target == base.mapContainsKeyMethod ||
                      target == base.mapGetOrNullMethod ||
                      target == base.mapFoldMethod -> {
                      checkIsRedundantConversion(
                        element.receiverExpr,
                        base.mappingToMapMethod,
                        base,
                        holder,
                        context
                      )
                    }
                  }
                }
                else -> {}
              }
            }
            is PklProperty -> {
              checkConstQualifiedAccess(element, target, holder)
              if (element.receiverExpr is PklThisExpr) {
                // because `target` is the property *definition*,
                // this check won't catch all qualified recursive references
                checkRecursivePropertyReference(element, target, holder)
              }
              when (receiverType) {
                base.listType -> {
                  when {
                    target == base.listIsEmptyProperty ||
                      target == base.listLengthProperty ||
                      target == base.listIsDistinctProperty -> {
                      checkIsRedundantConversion(
                        element.receiverExpr,
                        base.listingToListMethod,
                        base,
                        holder,
                        context
                      )
                    }
                  }
                }
                base.mapType -> {
                  when {
                    target == base.mapIsEmptyProperty ||
                      target == base.mapLengthProperty ||
                      target == base.mapKeysProperty -> {
                      checkIsRedundantConversion(
                        element.receiverExpr,
                        base.mappingToMapMethod,
                        base,
                        holder,
                        context
                      )
                    }
                  }
                }
                else -> {}
              }
            }
          }
        }

        override fun visitSuperAccessExpr(element: PklSuperAccessExpr) {
          val thisType = element.computeThisType(base, mapOf(), context)
          if (
            thisType == Type.Unknown || (thisType == base.dynamicType && element.isPropertyAccess)
          )
            return // don't flag

          val visitor =
            ResolveVisitors.firstElementNamed(
              element.memberNameText,
              base,
            )
          val target = element.resolve(base, thisType, mapOf(), visitor, context)
          if (target == null) reportUnresolvedAccess(element, thisType, base, holder, context)
          when (target) {
            is PklProperty -> checkConstAccess(element.memberName, target, holder, null)
            is PklMethod -> checkConstAccess(element.memberName, target, holder, null)
          }
        }

        override fun visitNewExpr(element: PklNewExpr) {
          val type =
            when (element.type) {
              null -> expectedType
              else -> element.type.toType(base, mapOf(), element.enclosingModule?.pklProject)
            }
          checkIsInstantiable(element, type, base, holder)
        }

        override fun visitAmendExpr(element: PklAmendExpr) {
          val elementType = element.parentExpr.computeExprType(base, mapOf(), context)
          checkIsAmendable(element, elementType, base, holder)
          if (element.updateInstantiationSyntax(project, isDryRun = true)) {
            val annotation =
              holder
                .newAnnotation(
                  HighlightSeverity.WARNING,
                  "Amends expression can be replaced with `new`"
                )
                .range(element.parentExpr)
            if (holder.currentFile.canModify()) {
              annotation.withFix(PklUpdateInstantationSyntaxQuickFix(element))
            }
            annotation.create()
          } else if (element.updateParenthesizedAmendExpression(project, isDryRun = true)) {
            val annotation =
              holder
                .newAnnotation(HighlightSeverity.ERROR, "Amends expressions must be parenthesized")
                .range(element.parentExpr)
            if (holder.currentFile.canModify()) {
              annotation.withFix(PklUpdateAmendSyntaxQuickFix(element))
            }
            annotation.create()
          }
        }

        override fun visitTypeTestExpr(element: PklTypeTestExpr) {
          val exprType = element.expr.computeExprType(base, mapOf(), context)
          val testedType = element.type.toType(base, mapOf(), context)
          if (testedType.hasConstraints) return
          if (exprType != Type.Unknown && exprType.isSubtypeOf(testedType, base, context)) {
            holder.newAnnotation(HighlightSeverity.WARNING, "Expression is always 'true'").create()
          } else if (!testedType.isSubtypeOf(exprType, base, context)) {
            holder.newAnnotation(HighlightSeverity.WARNING, "Expression is always 'false'").create()
          }
        }

        override fun visitTypeCastExpr(element: PklTypeCastExpr) {
          val exprType = element.expr.computeExprType(base, mapOf(), context)
          val testedType = element.type.toType(base, mapOf(), context)
          if (
            !testedType.hasConstraints &&
              exprType != Type.Unknown &&
              exprType.isSubtypeOf(testedType, base, context)
          ) {
            val annotation =
              holder
                .newAnnotation(HighlightSeverity.WARNING, "Type cast is redundant")
                .range(element.operator.textRange.union(element.type.textRange))
                .highlightType(ProblemHighlightType.LIKE_UNUSED_SYMBOL)
            if (holder.currentFile.canModify()) {
              annotation.withFix(PklRedundantTypeCastQuickFix(element))
            }
            annotation.create()
          } else if (
            !testedType.isSubtypeOf(exprType, base, context) &&
              !testedType.hasCommonSubtypeWith(exprType, base, context)
          ) {
            holder
              .newAnnotation(HighlightSeverity.ERROR, "Type cast cannot succeed")
              .range(element.operator.textRange.union(element.type.textRange))
              .create()
          }
        }

        override fun visitNullCoalesceBinExpr(element: PklNullCoalesceBinExpr) {
          val leftType = element.leftExpr.computeExprType(base, mapOf(), context)
          if (!leftType.isNullable(base)) {
            val rightExpr = element.rightExpr ?: return
            val annotation =
              holder
                .newAnnotation(HighlightSeverity.WARNING, "Null coalescing is redundant")
                .range(element.operator.textRange.union(rightExpr.textRange))
                .highlightType(ProblemHighlightType.LIKE_UNUSED_SYMBOL)
            if (holder.currentFile.canModify()) {
              annotation.withFix(PklRedundantNullCoalesceQuickFix(element))
            }
            annotation.create()
          }
        }

        override fun visitNonNullAssertionExpr(element: PklNonNullAssertionExpr) {
          val type = element.expr.computeExprType(base, mapOf(), context)
          if (!type.isNullable(base)) {
            val annotation =
              holder
                .newAnnotation(HighlightSeverity.WARNING, "Non-null assertion is redundant")
                .range(element.operator.textRange)
                .highlightType(ProblemHighlightType.LIKE_UNUSED_SYMBOL)
            if (holder.currentFile.canModify()) {
              annotation.withFix(PklRedundantNonNullAssertionQuickFix(element))
            }
            annotation.create()
          }
        }
      }
    )
  }

  private fun checkConstQualifiedAccess(
    element: PklQualifiedAccessExpr,
    target: PklModifierListOwner,
    holder: AnnotationHolder
  ) {
    // only need to check for const-ness if the receiver is `module` or `this`.
    if (element.receiverExpr !is PklModuleExpr && element.receiverExpr !is PklThisExpr) return
    checkConstAccess(
      element.memberName,
      target,
      holder,
      // pretend this is an implicit this lookup so that [checkConstAccess] can vet whether this
      // lookup is allowed.
      if (element.receiverExpr is PklThisExpr) Resolvers.LookupMode.IMPLICIT_THIS else null
    )
  }

  // TODO: provide checks for `outer`. Right now we don't have any editor support at all for `outer`
  // outside of syntax highlighting.
  private fun checkConstAccess(element: PklModuleExpr, holder: AnnotationHolder) {
    // `module.<prop>` is allowed even if inside a const property.
    // Instead, `<prop>` is the one that gets checked.
    if (element.parent is PklQualifiedAccessExpr) return
    val constPropertyOrMethod = element.parentOfTypes(PklClassProperty::class, PklMethod::class)
    val needsConst =
      constPropertyOrMethod?.isConst == true ||
        element.parentOfTypes(PklClass::class, PklAnnotation::class, PklTypeAlias::class) != null
    if (needsConst) {
      createAnnotation(
        HighlightSeverity.ERROR,
        element.textRange,
        "Cannot reference `module` from here because it is not `const`",
        """
            Cannot reference <code>module</code> from here because it is not <code>const</code>.
          """
          .trimIndent(),
        holder,
        null,
        null
      )
    }
  }

  private fun PklThisExpr.isCustomThis(): Boolean {
    return when (
      parentOfTypes(
        PklConstrainedType::class,
        PklMemberPredicate::class,
        // stop class
        PklObjectBody::class
      )
    ) {
      is PklConstrainedType,
      is PklMemberPredicate -> true
      else -> false
    }
  }

  private fun PklElement.getConstScope(): Pair<Boolean, Boolean> {
    @Suppress("MoveVariableDeclarationIntoWhen")
    val parent = parentOfTypes(PklClassProperty::class, PklMethod::class, PklObjectBodyBase::class)
    return when (parent) {
      is PklModifierListOwner -> parent.isConst to false
      is PklObjectBodyBase -> {
        val isConst = parent.isConstScope()
        isConst to isConst
      }
      else -> false to false
    }
  }

  /**
   * Checks for `this` usage in the plain.
   *
   * ```
   * // not allowed (reference outside of const scope)
   * const foo = this
   *
   * // allowed (reference stays within const scope)
   * const foo = new {
   *   ["bar"] = this
   * }
   *
   * // allowed (custom this scope)
   * const foo = String(this == "bar")
   * ```
   *
   * Does not check qualified access (e.g. `const foo = this.bar`); this is handled by
   * [checkConstQualifiedAccess].
   */
  private fun checkConstAccess(element: PklThisExpr, holder: AnnotationHolder) {
    if (element.parent is PklQualifiedAccessExpr || element.isCustomThis()) return
    val (isConst, isInConstScope) = element.getConstScope()
    val needsConst = isConst && !isInConstScope
    if (needsConst) {
      createAnnotation(
        HighlightSeverity.ERROR,
        element.textRange,
        "Cannot reference `this` from here because it is not `const`",
        """
            Cannot reference <code>this</code> from here because it is not <code>const</code>.
          """
          .trimIndent(),
        holder,
        null,
        null
      )
    }
  }

  private fun checkConstAccess(
    element: PklElement,
    target: PklModifierListOwner,
    holder: AnnotationHolder,
    lookupMode: Resolvers.LookupMode?
  ) {
    val (isConst, isInConstScope) = element.getConstScope()
    val targetObjectParent = target.parentOfType<PklObjectBodyBase>()
    // if the target resides in a lexical scope within a const property, it is always allowed.
    if (targetObjectParent?.isConstScope() == true || target.isConst) return
    val isCustomThisScope =
      element
        .parentOfTypes(
          PklConstrainedType::class,
          PklMemberPredicate::class,
          /* stop class */ PklClassProperty::class
        )
        .let { it != null && it !is PklClassProperty }
    // lookups on `this` is always allowed in custom this scopes.
    if (isCustomThisScope && lookupMode == Resolvers.LookupMode.IMPLICIT_THIS) return
    // if the lookup is in a const scope, `super` and `this` lookups are always allowed
    if (isInConstScope) {
      if (lookupMode == Resolvers.LookupMode.IMPLICIT_THIS || element.parent is PklSuperAccessExpr)
        return
      val receiverExpr = (element.parent as? PklQualifiedAccessExpr)?.receiverExpr
      if (receiverExpr is PklThisExpr) return
    }

    // scenario 1: this is a reference from a const property, and can only reference other const
    // properties
    if (isConst) {
      val action = if (target is PklProperty) "reference property" else "call method"
      val name = if (target is PklProperty) target.name else (target as PklMethod).name
      createAnnotation(
        HighlightSeverity.ERROR,
        element.textRange,
        "Cannot $action `$name` from here because it is not `const`",
        """
            Cannot $action <code>$name</code> from here because it is not <code>const</code>.

            <p>To fix, either make the accessed member <code>const</code>, or add a self-import of this module, and access this member off of the self import.</p>
          """
          .trimIndent(),
        holder,
        null,
        null
      )
      return
    }

    // scenario 2: methods/properties on a module that are referenced from inside a class,
    // annotation, or typealias
    // need to be const
    val staticParent =
      element.parentOfTypes(PklClass::class, PklAnnotation::class, PklTypeAlias::class)
    if (staticParent != null) {
      if (
        target.parent is PklModuleMemberList &&
          !target.isConst &&
          target.containingFile == element.containingFile
      ) {
        val action = if (target is PklProperty) "reference property" else "call method"
        val name = if (target is PklProperty) target.name else (target as PklMethod).name
        if (staticParent is PklTypeAlias) {
          // const requirement on typealiases added in Pkl 0.26. If the declared Pkl version is
          // 0.25.x, display a warning rather than an error.
          element.enclosingModule?.effectivePklVersion?.let { effectivePklVersion ->
            if (effectivePklVersion < PklVersion.VERSION_0_26) {
              return warnTypealiasConst(element, action, name, holder)
            }
          }
        }
        createAnnotation(
          HighlightSeverity.ERROR,
          element.textRange,
          "Cannot $action `$name` from here because it is not `const`",
          """
            Cannot $action <code>${name?.escapeXml()}</code> from here because it is not <code>const</code>.<br/>
            <br/>
            Classes, typealiases, and annotations can only reference <code>const</code> members of their enclosing module.
            
            <p>To fix, either make the accessed member <code>const</code>, or add a self-import of this module, and access this member off of the self import.</p>
            """
            .trimIndent(),
          holder,
          null,
          null
        )
      }
    }
  }

  private fun warnTypealiasConst(
    element: PklElement,
    action: String,
    name: String?,
    holder: AnnotationHolder
  ) {
    createAnnotation(
      HighlightSeverity.WARNING,
      element.textRange,
      "Should not $action `$name` from here because it is not `const`",
      """
            Should not $action <code>${name?.escapeXml()}</code> from here because it is not <code>const</code>.<br/>
            <br/>
            In Pkl 0.26, typealiases can only reference <code>const</code> members of their enclosing module, and this code is considered breaking.
            
            <p>To fix, either make the accessed member <code>const</code>, or add a self-import of this module, and access this member off of the self import.</p>
            """
        .trimIndent(),
      holder,
      null,
      null
    )
  }

  private fun checkIsRedundantConversion(
    expr: PklExpr,
    conversionMethod: PklClassMethod,
    base: PklBaseModule,
    holder: AnnotationHolder,
    context: PklProject?
  ) {
    if (expr !is PklQualifiedAccessExpr) return

    val methodName = expr.memberNameText
    val visitor =
      ResolveVisitors.firstElementNamed(
        methodName,
        base,
      )
    val target = expr.resolve(base, null, mapOf(), visitor, context)
    if (target == conversionMethod) {
      val annotation =
        holder
          .newAnnotation(HighlightSeverity.WARNING, "'$methodName()' conversion is redundant")
          .range(TextRange(expr.memberName.startOffset, expr.endOffset))
          .highlightType(ProblemHighlightType.LIKE_UNUSED_SYMBOL)
      if (holder.currentFile.canModify()) {
        annotation.withFix(PklRedundantConversionQuickFix(expr, methodName))
      }
      annotation.create()
    }
  }

  private fun checkArgumentCount(
    expr: PklAccessExpr,
    method: PklMethod,
    base: PklBaseModule,
    holder: AnnotationHolder,
  ) {
    val paramList = method.parameterList ?: return
    val params = paramList.elements
    val argList = expr.argumentList ?: return
    val args = argList.elements
    val paramCount = params.size
    val argCount = args.size
    when {
      argCount < paramCount -> {
        if (argCount == paramCount - 1 && method.isVarArgs(base)) return

        val closingParen = argList.lastChildOfType(PklElementTypes.RPAREN) ?: return

        for (idx in argCount until paramCount) {
          val message = buildString {
            append("Missing argument for ")
            renderTypedIdentifier(params[idx], mapOf())
          }
          val htmlMessage = buildString {
            append("Missing argument for ")
            code { renderTypedIdentifier(params[idx], mapOf()) }
          }

          holder
            .newAnnotation(HighlightSeverity.ERROR, message)
            .range(closingParen.textRange)
            .tooltip(htmlMessage)
            .create()
        }
      }
      argCount > paramCount -> {
        if (method.isVarArgs(base)) return

        val message = buildString {
          append("Too many arguments for method ")
          append(method.name)
          renderParameterList(method.parameterList, mapOf())
        }
        val htmlMessage = buildString {
          append("Too many arguments for ")
          code {
            append("method ")
            append(method.name)
            renderParameterList(method.parameterList, mapOf())
          }
        }
        holder
          .newAnnotation(HighlightSeverity.ERROR, message)
          .range(argList.elements[paramCount].textRange)
          .tooltip(htmlMessage)
          .create()
      }
    }
  }

  private fun reportUnresolvedAccess(
    expr: PklAccessExpr,
    receiverType: Type,
    base: PklBaseModule,
    holder: AnnotationHolder,
    context: PklProject?
  ) {
    createAnnotation(
      if (receiverType.isUnresolvedMemberFatal(base, context)) HighlightSeverity.ERROR
      else HighlightSeverity.WARNING,
      expr.memberName.textRange,
      "Unresolved reference: ${expr.memberNameText}",
      "Unresolved reference: <code>${expr.memberNameText.escapeXml()}<code>",
      holder,
      PklProblemGroups.unresolvedElement,
      expr
    )
  }

  private fun checkRecursivePropertyReference(
    expr: PklAccessExpr,
    property: PklProperty,
    holder: AnnotationHolder
  ) {
    val parent =
      expr.parentOfTypes(
        PklObjectMember::class,
        PklModuleMember::class, // includes `PklClassMember`
        PklFunctionLiteral::class
      )

    if (areElementsEquivalent(parent, property)) {
      holder
        .newAnnotation(HighlightSeverity.ERROR, "Recursive property reference")
        .range(expr.memberName)
        .create()
    }
  }
}
