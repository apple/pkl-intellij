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

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiElement
import com.intellij.psi.util.parentOfTypes
import com.intellij.refactoring.suggested.createSmartPointer
import com.jetbrains.rd.util.first
import org.pkl.intellij.intention.PklAddModifierQuickFix
import org.pkl.intellij.intention.PklDefinePropertiesValuesQuickFix
import org.pkl.intellij.packages.dto.PklProject
import org.pkl.intellij.psi.*
import org.pkl.intellij.type.toType
import org.pkl.intellij.util.canModify
import org.pkl.intellij.util.currentFile
import org.pkl.intellij.util.currentProject
import org.pkl.intellij.util.escapeXml

class PklExtendsClauseAnnotator : PklAnnotator() {
  override fun doAnnotate(element: PsiElement, holder: AnnotationHolder) {
    when (element) {
      is PklModuleExtendsAmendsClause -> checkModuleExtendsClause(element, holder)
      is PklClassExtendsClause -> checkClassExtendsClause(element, holder, element.type)
    }
  }

  private fun checkModuleExtendsClause(
    clause: PklModuleExtendsAmendsClause,
    holder: AnnotationHolder
  ) {
    if (!clause.isExtend) return
    val moduleUri = clause.moduleUri ?: return
    val resolved =
      moduleUri.resolve(clause.enclosingModule?.pklProject)
        ?: return // checked by [PklModuleUriAndVersionAnnotator]
    val context = clause.enclosingModule?.pklProject
    if (!resolved.isAbstractOrOpen) {
      val moduleName = resolved.shortDisplayName
      val annotation =
        holder
          .newAnnotation(HighlightSeverity.ERROR, "Cannot extend module '$moduleName'")
          .range(moduleUri.stringConstant.content)
      val modifierList = resolved.modifierList
      // simplification: only offer quick fix if module already has module clause
      if (modifierList != null && holder.currentFile.canModify()) {
        annotation.withFix(
          PklAddModifierQuickFix("Make '$moduleName' open", modifierList, PklElementTypes.OPEN)
        )
        annotation.withFix(
          PklAddModifierQuickFix(
            "Make '$moduleName' abstract",
            modifierList,
            PklElementTypes.ABSTRACT
          )
        )
      }
      annotation.create()
    }
    val module = clause.parentOfTypes(PklModule::class) ?: return
    checkParentClassDef(clause, module, holder.currentProject.pklBaseModule, holder, context)
  }

  private fun checkClassExtendsClause(
    clause: PklClassExtendsClause,
    holder: AnnotationHolder,
    supertype: PklType?
  ) {
    val context = clause.enclosingModule?.pklProject
    fun reportError(message: String, fixes: List<IntentionAction> = listOf()) {
      val element =
        when (val type = clause.type!!) {
          is PklDeclaredType -> type.typeName.simpleName
          else -> type
        }
      val annotation = holder.newAnnotation(HighlightSeverity.ERROR, message)
      for (fix in fixes) annotation.withFix(fix)
      annotation.range(element).create()
    }

    fun checkSupermodule(module: PklModule) {
      if (!module.isAbstractOrOpen) {
        val moduleName = module.shortDisplayName
        val modifierList = module.modifierList
        reportError(
          "Cannot extend module '$moduleName'",
          // simplification: only offer quick fix if module already has module clause
          if (modifierList != null && holder.currentFile.canModify()) {
            listOf(
              PklAddModifierQuickFix("Make '$moduleName' open", modifierList, PklElementTypes.OPEN),
              PklAddModifierQuickFix(
                "Make '$moduleName' abstract",
                modifierList,
                PklElementTypes.ABSTRACT
              )
            )
          } else listOf()
        )
      }
    }

    when (supertype) {
      null -> {}
      is PklDeclaredType -> {
        val typeName = supertype.typeName
        val resolved = typeName.resolve(context) ?: return // checked by [PklTypeNameAnnotator]

        when (resolved) {
          is PklClass -> {
            val className = resolved.name.orEmpty()
            if (!resolved.isAbstractOrOpen) {
              reportError(
                "Cannot extend class '${resolved.name}'",
                if (holder.currentFile.canModify()) {
                  listOf(
                    PklAddModifierQuickFix(
                      "Make '$className' open",
                      resolved.modifierList,
                      PklElementTypes.OPEN
                    ),
                    PklAddModifierQuickFix(
                      "Make '$className' abstract",
                      resolved.modifierList,
                      PklElementTypes.ABSTRACT
                    )
                  )
                } else listOf()
              )
              return
            }
            if (resolved.isExternal && clause.enclosingModule?.isStdLibModule != true) {
              reportError("Cannot extend external class '$className'")
            }
          }
          is PklTypeAlias -> {
            checkClassExtendsClause(clause, holder, resolved.body)
          }
          is PklModule -> {
            checkSupermodule(resolved)
          }
        }
      }
      is PklModuleType -> {
        checkSupermodule(clause.enclosingModule ?: return)
      }
      else -> {
        reportError("Cannot extend type '${supertype.text}'")
      }
    }
    val parent = clause.parentOfTypes(PklClass::class) ?: return
    checkParentClassDef(clause, parent, holder.currentProject.pklBaseModule, holder, context)
  }

  /**
   * Check that extending an abstract class defines values for types that are missing default
   * values.
   */
  private fun checkParentClassDef(
    element: PsiElement,
    def: PklTypeDefOrModule,
    base: PklBaseModule,
    holder: AnnotationHolder,
    context: PklProject?
  ) {
    if (def.isAbstract) return
    // skip annotation if parent is not abstract nor open
    if (def.parentTypeDef(context)?.isAbstractOrOpen == false) return
    val parentProperties = def.effectiveParentProperties(context) ?: return
    val definedProperties =
      when (def) {
        is PklClass -> def.properties.associateBy { it.name }
        else -> {
          def as PklModule
          def.properties.associateBy { it.name }
        }
      }
    val missingProperties =
      parentProperties.filter { (propName, property) ->
        val type = property.type.toType(base, mapOf(), context)
        property.isFixedOrConst &&
          property.expr == null &&
          definedProperties[propName] == null &&
          !type.hasDefault(base, context)
      }
    if (missingProperties.isEmpty()) return
    val entityName = if (def is PklModule) "module" else "class"
    val firstMissingProperty = missingProperties.first().key
    val classOrModuleName = def.name.orEmpty()
    holder
      .newAnnotation(
        HighlightSeverity.WARNING,
        // Copy Java/Kotlin's error message and only notify about the first missing property.
        "$entityName $classOrModuleName is not abstract and does not define a default value for property '${firstMissingProperty}'"
      )
      .apply {
        range(element.textRange)
        // language=html
        tooltip(
          """
            ${entityName.escapeXml()} ${classOrModuleName.escapeXml()} is not abstract and does not define a default value for property <code>${firstMissingProperty.escapeXml()}.</code>
          """
            .trimIndent()
        )
        withFix(
          PklDefinePropertiesValuesQuickFix(
            def,
            missingProperties.values.map { it.createSmartPointer() }
          )
        )
        def.modifierList?.let { modifierList ->
          withFix(
            PklAddModifierQuickFix(
              "Make '${classOrModuleName}' abstract",
              modifierList,
              PklElementTypes.ABSTRACT
            )
          )
        }
      }
      .create()
  }
}
