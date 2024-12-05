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

import com.intellij.openapi.project.Project

fun PklAccessExpr.replaceDeprecated(
  module: PklModule,
  isDryRun: Boolean = false,
  applyIf: (PklAnnotationListOwner, Deprecated) -> Boolean = { _, _ -> true }
): Boolean {
  val target = memberName.reference?.resolve() as? PklAnnotationListOwner ?: return false
  val deprecated = target.annotationList?.deprecated ?: return false
  val replaceWith = deprecated.replaceWith ?: return false

  val replacementModule = PklPsiFactory.createModule("x = $replaceWith", project)
  val replacementExpr = replacementModule.properties.single().expr as? PklAccessExpr ?: return false

  if (!applyIf(target, deprecated)) return false
  if (isDryRun) return true

  val declImportList = deprecated.target?.enclosingModule?.importList
  val usageImportList = module.importList
  val declMethod = target as? PklMethod
  val declParameters = declMethod?.parameterList?.elements
  val usageArguments = argumentList?.elements
  replacementExpr.acceptChildren(
    ReplacementVisitor(declImportList, usageImportList, declParameters, usageArguments, project)
  )
  when {
    this is PklQualifiedAccessExpr && replacementExpr is PklUnqualifiedAccessExpr -> {
      memberName.identifier.replace(replacementExpr.memberName.identifier)
      val startList = argumentList
      when (val replacementList = replacementExpr.argumentList) {
        null -> startList?.delete()
        else ->
          when (startList) {
            null -> add(replacementList)
            else -> startList.replace(replacementList)
          }
      }
    }
    else -> {
      replace(replacementExpr)
    }
  }

  return true
}

fun PklTypeName.replaceDeprecated(
  module: PklModule,
  isDryRun: Boolean = false,
  applyIf: (PklAnnotationListOwner, Deprecated) -> Boolean = { _, _ -> true }
): Boolean {
  val context = module.pklProject
  val target = simpleName.resolve(context) as? PklAnnotationListOwner ?: return false
  val deprecated = target.annotationList?.deprecated ?: return false
  val replaceWith = deprecated.replaceWith ?: return false

  val replacementModule = PklPsiFactory.createModule("x: $replaceWith", project)
  val replacementType =
    replacementModule.properties.single().type as? PklDeclaredType ?: return false

  if (!applyIf(target, deprecated)) return false
  if (isDryRun) return true

  val declImportList = deprecated.target?.enclosingModule?.importList
  val usageImportList = module.importList
  replacementType.acceptChildren(
    ReplacementVisitor(declImportList, usageImportList, null, null, project)
  )
  // if this type is qualified but the replacement isn't, replace just the last part
  if (replacementType.typeName.moduleName != null) {
    replace(replacementType.typeName)
  } else {
    simpleName.replace(replacementType.typeName.simpleName)
  }

  return true
}

/**
 * If [this] is one of the following amend expressions, replaces it with the corresponding `new`
 * expression. Returns `true` iff [this] was replaced.
 * - `Foo {...}` -> `new Foo {...}`
 * - `foo.Bar {...}` -> `new foo.Bar {...}`
 * - `(Foo) {...}` -> `new Foo {...}`
 * - `(foo.Bar) {...}` -> `new foo.Bar {...}`
 */
fun PklAmendExpr.updateInstantiationSyntax(
  project: Project,
  // only explicitly set by recursive call
  parentExpr: PklExpr = this.parentExpr,
  isDryRun: Boolean = false
): Boolean {
  when (parentExpr) {
    is PklUnqualifiedAccessExpr -> {
      if (parentExpr.isPropertyAccess) {
        val target =
          parentExpr.memberName.pklReference?.resolveContextual(enclosingModule?.pklProject)
        if ((target is PklClass || target is PklTypeAlias)) {
          if (!isDryRun) {
            val newExpr =
              PklPsiFactory.createNewExpr(parentExpr.memberNameText, objectBody, project)
            replace(newExpr)
          }
          return true
        }
      }
    }
    is PklQualifiedAccessExpr -> {
      if (parentExpr.isPropertyAccess) {
        val receiverExpr = parentExpr.receiverExpr
        if (receiverExpr is PklUnqualifiedAccessExpr && receiverExpr.isPropertyAccess) {
          val receiverTarget = receiverExpr.memberName.reference?.resolve()
          if (receiverTarget is PklModule) {
            val target = parentExpr.memberName.reference?.resolve()
            if (target is PklClass || target is PklTypeAlias) {
              if (!isDryRun) {
                val newExpr =
                  PklPsiFactory.createQualifiedNewExpr(
                    receiverExpr.memberNameText,
                    parentExpr.memberNameText,
                    objectBody,
                    project
                  )
                replace(newExpr)
              }
              return true
            }
          }
        }
      }
    }
    is PklParenthesizedExpr -> {
      parentExpr.expr?.let {
        return updateInstantiationSyntax(project, it, isDryRun)
      }
    }
  }
  return false
}

/**
 * If [this] is an unparenthesized amend expressions, replaces it with the corresponding
 * parenthesized expression. Returns `true` iff [this] was replaced.
 */
fun PklAmendExpr.updateParenthesizedAmendExpression(
  project: Project,
  // only explicitly set by recursive call
  parentExpr: PklExpr = this.parentExpr,
  isDryRun: Boolean = false,
  isParenthesized: Boolean = false,
): Boolean {
  if (parentExpr is PklAmendExpr) {
    return parentExpr.updateParenthesizedAmendExpression(
      project,
      isDryRun = isDryRun,
      isParenthesized = isParenthesized
    )
  }

  if (parentExpr is PklParenthesizedExpr) {
    parentExpr.expr?.let {
      return updateParenthesizedAmendExpression(project, it, isDryRun, true)
    }
  }

  if (!isParenthesized) {
    if (!isDryRun) {
      val parenthesizedExpr =
        PklPsiFactory.createParenthesizedAmendExpr(parentExpr, objectBody, project)
      replace(parenthesizedExpr)
    }
    return true
  }

  return false
}

/**
 * Handles referenced imports, inlined imports, and referenced method parameters in replacement
 * expressions/types.
 */
private class ReplacementVisitor(
  /** Import list of the module that declares the deprecated element. */
  private val declImportList: PklImportList?,

  /**
   * Import list of the module that uses the deprecated element. Can be identical to
   * [declImportList].
   */
  private val usageImportList: PklImportList?,

  /** The deprecated element's method parameters, if any. */
  private val declParameters: List<PklTypedIdentifier>?,

  /** The method arguments of the deprecated element's usage, if any. */
  private val usageArguments: List<PklExpr>?,
  private val project: Project
) : PklRecursiveVisitor<Unit>() {
  /**
   * Handles referenced method parameters and referenced imports, such as `replaceWith =
   * "math.maxInt"`.
   */
  override fun visitUnqualifiedAccessExpr(expr: PklUnqualifiedAccessExpr) {
    if (!expr.isPropertyAccess) {
      expr.acceptChildren(this)
      return
    }

    val propertyName = expr.memberNameText

    if (declParameters != null && usageArguments != null) {
      val index = declParameters.indexOfFirst { it.identifier.textMatches(propertyName) }
      val argument = usageArguments.getOrNull(index)
      if (argument != null) {
        expr.replace(argument)
        return
      }
    }

    handleReferencedImport(propertyName) { aliasName ->
      expr.replace(PklPsiFactory.createUnqualifiedPropertyAccessExpr(aliasName, project))
    }
  }

  /**
   * Handles inlined imports in expressions, such as `replaceWith = #"import("pkl:math").maxInt"#`.
   */
  override fun visitImportExpr(expr: PklImportExpr) {
    val importUri = expr.moduleUri.escapedContent ?: return
    val importName = usageImportList?.findOrInsertImport(importUri) ?: return
    expr.replace(PklPsiFactory.createUnqualifiedPropertyAccessExpr(importName, project))
  }

  /**
   * Handles referenced imports in type names, such as `replaceWith = "SomeModule"` or `replaceWith
   * = "someModule.Person"`.
   */
  override fun visitTypeName(typeName: PklTypeName) {
    typeName.moduleName?.let { moduleName ->
      handleReferencedImport(moduleName.identifier.text) { aliasName ->
        moduleName.replace(PklPsiFactory.createModuleName(aliasName, project))
      }
      return
    }

    handleReferencedImport(typeName.simpleName.identifier.text) { aliasName ->
      typeName.simpleName.replace(PklPsiFactory.createSimpleTypeName(aliasName, project))
    }
  }

  private fun handleReferencedImport(name: String, nameReplacer: (String) -> Unit) {
    if (declImportList != null && usageImportList != null) {
      for (declImport in declImportList.elements) {
        val declImportName = declImport.memberName ?: continue
        if (declImportName == name) {
          val declImportUri = declImport.moduleUri?.escapedContent ?: return
          val usageImportName = usageImportList.findOrInsertImport(declImportUri) ?: return
          if (usageImportName != name) {
            // import had to be aliased due to naming conflict -> replace reference with alias
            nameReplacer(usageImportName)
          }
          return
        }
      }
    }
  }
}
