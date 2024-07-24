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
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.elementType
import com.intellij.psi.util.parentOfType
import com.intellij.psi.util.parentOfTypes
import org.pkl.intellij.intention.PklAddDefaultValueQuickFix
import org.pkl.intellij.intention.PklAddModifierQuickFix
import org.pkl.intellij.intention.PklReplaceWithSpreadQuickFix
import org.pkl.intellij.packages.dto.PklProject
import org.pkl.intellij.psi.*
import org.pkl.intellij.psi.PklElementTypes.*
import org.pkl.intellij.resolve.ResolveVisitors
import org.pkl.intellij.resolve.Resolvers
import org.pkl.intellij.type.*
import org.pkl.intellij.type.Type.*
import org.pkl.intellij.util.*

// TODO: verify that Type.constraints are propagated correctly, e.g., in `amended()` calls
class PklMemberAnnotator : PklAnnotator() {

  companion object {
    private val MODULE_MODIFIERS = TokenSet.create(ABSTRACT, OPEN)
    private val AMENDING_MODULE_MODIFIERS = TokenSet.create()
    private val CLASS_MODIFIERS = TokenSet.create(ABSTRACT, OPEN, EXTERNAL, LOCAL)
    private val TYPE_ALIAS_MODIFIERS = TokenSet.create(EXTERNAL, LOCAL)
    private val CLASS_METHOD_MODIFIERS = TokenSet.create(ABSTRACT, EXTERNAL, LOCAL, CONST)
    private val CLASS_PROPERTY_MODIFIERS =
      TokenSet.create(ABSTRACT, EXTERNAL, HIDDEN, LOCAL, FIXED, CONST)
    private val OBJECT_METHOD_MODIFIERS = TokenSet.create(LOCAL)
    private val OBJECT_PROPERTY_MODIFIERS = TokenSet.create(LOCAL)
  }

  override fun doAnnotate(element: PsiElement, holder: AnnotationHolder) {
    val module = holder.currentModule ?: return
    val project = module.project
    val base = project.pklBaseModule
    val context = element.enclosingModule?.pklProject

    val memberType: Type by lazy {
      element.computeResolvedImportType(
        base,
        mapOf(),
        context,
        preserveUnboundTypeVars = false,
        canInferExprBody = false
      )
    }

    element.accept(
      object : PklVisitor<Unit>() {
        override fun visitObjectProperty(element: PklObjectProperty) {
          checkModifiers(element, "object properties", OBJECT_PROPERTY_MODIFIERS, module, holder)
          checkUnresolvedProperty(element, memberType, base, holder, context)
          checkIsAmendable(element, memberType, base, holder)
          checkIsAssignable(element, base, holder, context)
        }

        override fun visitObjectElement(element: PklObjectElement) {
          val thisType = element.computeThisType(base, mapOf(), context)
          val thisClassType = thisType.toClassType(base, context)
          if (
            thisClassType != null &&
              !thisClassType.classEquals(base.listingType) &&
              !thisClassType.classEquals(base.dynamicType)
          ) {
            val typeText = thisType.render()
            createAnnotation(
              HighlightSeverity.ERROR,
              element.textRange,
              "Object of type $typeText cannot have elements",
              "Object of type <code>${typeText.escapeXml()}</code> cannot have elements",
              holder
            )
          }
        }

        override fun visitObjectEntry(element: PklObjectEntry) {
          val thisType = element.computeThisType(base, mapOf(), context)
          val thisClassType = thisType.toClassType(base, context)
          if (
            thisClassType != null &&
              // listing can *syntactically* have an entry (override by index)
              !thisClassType.classEquals(base.listingType) &&
              !thisClassType.classEquals(base.mappingType) &&
              !thisClassType.classEquals(base.dynamicType)
          ) {
            val typeText = thisType.render()
            createAnnotation(
              HighlightSeverity.ERROR,
              element.textRange,
              "Object of type $typeText cannot have entries",
              "Object of type <code>${typeText.escapeXml()}</code> cannot have entries",
              holder
            )
          }
          checkIsAmendable(element, memberType, base, holder)
        }

        override fun visitForGenerator(o: PklForGenerator) {
          checkObjectSpread(base, o, holder, context)
        }

        override fun visitMemberPredicate(element: PklMemberPredicate) {
          checkIsAmendable(element, memberType, base, holder)
        }

        override fun visitObjectMethod(element: PklObjectMethod) {
          checkModifiers(element, "object methods", OBJECT_METHOD_MODIFIERS, module, holder)
        }

        override fun visitClassProperty(element: PklClassProperty) {
          checkModifiers(element, "properties", CLASS_PROPERTY_MODIFIERS, module, holder)
          checkUnresolvedProperty(element, memberType, base, holder, context)
          checkIsAmendable(element, memberType, base, holder)
          checkFixedOrConstModifier(element, holder, base, context)
        }

        override fun visitClassMethod(element: PklClassMethod) {
          checkModifiers(element, "methods", CLASS_METHOD_MODIFIERS, module, holder)
          checkTypeParameters(element, module, holder)
        }

        override fun visitClass(element: PklClass) {
          checkModifiers(element, "classes", CLASS_MODIFIERS, module, holder)
          checkTypeParameters(element, module, holder)
        }

        override fun visitTypeAlias(element: PklTypeAlias) {
          checkModifiers(element, "type aliases", TYPE_ALIAS_MODIFIERS, module, holder)
          val body = element.body
          if (body != null && element.isRecursive(context)) {
            holder
              .newAnnotation(HighlightSeverity.ERROR, "Recursive type alias")
              .range(body)
              .create()
          }
        }

        override fun visitModuleDeclaration(element: PklModuleDeclaration) {
          val clause = element.extendsAmendsClause
          when {
            clause.isAmend ->
              checkModifiers(element, "amending modules", AMENDING_MODULE_MODIFIERS, module, holder)
            else -> checkModifiers(element, "modules", MODULE_MODIFIERS, module, holder)
          }
        }
      }
    )
  }

  private fun checkFixedOrConstWithoutDefaultValue(
    element: PklClassProperty,
    holder: AnnotationHolder,
    enclosingEntity: PklTypeDefOrModule,
    base: PklBaseModule,
    context: PklProject?
  ) {
    if (
      !element.isFixedOrConst ||
        enclosingEntity.isAbstract ||
        element.expr != null ||
        element.isExternal
    )
      return
    val type = element.type?.toType(base, mapOf(), context) ?: return
    if (type.hasDefault(base, context)) return
    val severity =
      if (enclosingEntity.isOpen) HighlightSeverity.WARNING else HighlightSeverity.ERROR
    val enclosingEntityName = if (enclosingEntity is PklModule) "module" else "class"
    val modifier = if (element.isFixed) "fixed" else "const"
    // language=html
    val details = buildString {
      append("Property <code>${element.name.escapeXml()}</code> ")
      append(if (enclosingEntity.isOpen) "should " else "must ")
      append(
        """define a value because:
        <ul>
            <li>It is $modifier, so it cannot be assigned or amended when defining an object of this class.</li>
            <li>Type <code>${type.render().escapeXml()}</code> does not have a default value.</li>
      """
          .trimIndent()
      )
      if (!enclosingEntity.isOpen) {
        append(
          "<li>$enclosingEntityName <code>${enclosingEntity.displayName.escapeXml()}</code> cannot be extended, because it is neither <code>abstract</code> nor <code>open</code>.</li>"
        )
      }
      append("</ul></p>")
    }
    buildAnnotation(
        severity,
        element.propertyName.textRange,
        "Missing value for property '${element.name}'",
        // language=html
        """
          <p>Missing value.</p>
          $details
        """
          .trimIndent(),
        holder,
        PklProblemGroups.missingDefaultValue,
        element
      )
      ?.apply {
        if (holder.currentFile.canModify()) {
          withFix(PklAddDefaultValueQuickFix(element))
        }
      }
      ?.create()
  }

  private fun checkFixedOrConstModifier(
    element: PklClassProperty,
    holder: AnnotationHolder,
    base: PklBaseModule,
    context: PklProject?
  ) {
    val enclosingDef = element.parentOfTypes(PklClass::class, PklModule::class) ?: return
    checkFixedOrConstWithoutDefaultValue(element, holder, enclosingDef, base, context)
    val isAmendingModule = enclosingDef is PklModule && enclosingDef.extendsAmendsClause.isAmend
    if (isAmendingModule) {
      checkIsAssignable(element, base, holder, context)
      return
    }
    val parentProperty =
      enclosingDef.effectiveParentProperties(context)?.get(element.name) ?: return
    if (parentProperty.isFixed == element.isFixed && parentProperty.isConst == element.isConst)
      return
    if (parentProperty.isFixedOrConst) {
      val modifier = if (parentProperty.isFixed) FIXED else CONST
      buildAnnotation(
          HighlightSeverity.ERROR,
          element.propertyName.textRange,
          "Missing modifier '$modifier'",
          // language=html
          """
            <p>Missing modifier <code>$modifier</code>.</p>
            <p>Property ${element.name.escapeXml()} must be declared $modifier because it overrides a parent property that is declared $modifier.</p>
          """
            .trimIndent(),
          holder
        )
        ?.apply {
          if (holder.currentFile.canModify()) {
            withFix(
              PklAddModifierQuickFix(
                "Make '${element.name}' $modifier",
                element.modifierList,
                modifier
              )
            )
          }
        }
        ?.create()
    } else {
      val fixedOrConstModifier =
        element.modifierList.childSeq.find { it.elementType == FIXED || it.elementType == CONST }
          ?: return
      val modifierType = fixedOrConstModifier.elementType!!
      buildAnnotation(
          HighlightSeverity.ERROR,
          fixedOrConstModifier.textRange,
          "Cannot apply modifier '$modifierType'",
          // language=html
          """
          <p>Cannot apply modifier <code>$modifierType</code></p>
          <p>Property <code>${element.name.escapeXml()}</code> cannot be declared $modifierType because it overrides a parent property that is not declared $modifierType.</p>
          """
            .trimIndent(),
          holder
        )
        ?.apply {
          if (parentProperty.parentOfType<PsiFile>()?.canModify() == true) {
            withFix(
              PklAddModifierQuickFix(
                "Make parent property $modifierType",
                parentProperty.modifierList,
                modifierType
              )
            )
          }
        }
        ?.create()
    }
  }

  private fun checkTypeParameters(
    owner: PklTypeParameterListOwner,
    module: PklModule,
    holder: AnnotationHolder
  ) {

    val typeParameterList = owner.typeParameterList
    if (typeParameterList != null && !module.isStdLibModule) {
      holder
        .newAnnotation(
          HighlightSeverity.ERROR,
          "Type parameters are only allowed in standard library"
        )
        .range(typeParameterList)
        .create()
    }
  }

  /** Checks if a fixed/const property is assigned to (or amended via an amends declaration). */
  private fun checkIsAssignable(
    element: PklProperty,
    base: PklBaseModule,
    holder: AnnotationHolder,
    context: PklProject?
  ) {
    val enclosingParentType = element.computeThisType(base, mapOf(), context)
    val properties =
      when (val underlyingType = enclosingParentType.unaliased(base, context)) {
        is Class -> underlyingType.psi.cache(context).properties
        is Module -> underlyingType.psi.cache(context).properties
        else -> return
      }
    val property = properties[element.name]
    if (property?.isFixedOrConst == true) {
      val action = if (element.isAmendsDeclaration) "amend" else "assign to"
      val modifier = if (property.isFixed) "fixed" else "const"
      createAnnotation(
        HighlightSeverity.ERROR,
        element.propertyName.textRange,
        "Cannot $action $modifier property '${property.name}'",
        "Cannot $action $modifier property <code>${property.name.escapeXml()}</code>",
        holder
      )
    }
  }

  private fun checkModifiers(
    owner: PklModifierListOwner,
    descriptionPlural: String,
    applicableModifiers: TokenSet,
    module: PklModule,
    holder: AnnotationHolder
  ) {

    val modifiers = owner.modifierList?.elements ?: return

    var localModifier: PsiElement? = null
    var abstractModifier: PsiElement? = null
    var openModifier: PsiElement? = null
    var hiddenModifier: PsiElement? = null
    var fixedModifier: PsiElement? = null

    for (modifier in modifiers) {
      when (modifier.elementType) {
        LOCAL -> localModifier = modifier
        ABSTRACT -> abstractModifier = modifier
        OPEN -> openModifier = modifier
        HIDDEN -> hiddenModifier = modifier
        FIXED -> fixedModifier = modifier
      }
    }

    if (localModifier == null) {
      fun missingLocalModifier(owner: PklModifierListOwner, name: String?, anchor: PsiElement) {
        val annotation =
          holder.newAnnotation(HighlightSeverity.ERROR, "Missing modifier 'local'").range(anchor)
        if (holder.currentFile.canModify()) {
          val modifierList = owner.modifierList!! // owner !is PklModuleDeclaration
          annotation.withFix(
            PklAddModifierQuickFix("Make '${name.orEmpty()}' local", modifierList, LOCAL)
          )
        }
        return annotation.create()
      }
      when (owner) {
        is PklObjectMethod -> {
          missingLocalModifier(owner, owner.name, owner.anchor)
          return
        }
        is PklClassProperty -> {
          if (
            owner.parent is PklModuleMemberList &&
              module.extendsAmendsClause.isAmend &&
              (hiddenModifier != null || owner.type != null)
          ) {
            missingLocalModifier(owner, owner.name, owner.anchor)
            return
          }
        }
        is PklModuleMember -> {
          if (owner.parent is PklModuleMemberList && module.extendsAmendsClause.isAmend) {
            missingLocalModifier(owner, owner.name, owner.anchor)
            return
          }
        }
      }
    }

    if (abstractModifier != null && openModifier != null) {
      holder
        .newAnnotation(HighlightSeverity.ERROR, "Modifier 'abstract' conflicts with 'open'")
        .range(abstractModifier)
        .create()
      holder
        .newAnnotation(HighlightSeverity.ERROR, "Modifier 'open' conflicts with 'abstract'")
        .range(openModifier)
        .create()
    }

    modifiers@ for (modifier in modifiers) {
      val elementType = modifier.elementType ?: continue

      for (prevModifier in modifiers) {
        if (prevModifier == modifier) break
        if (prevModifier.elementType == elementType) {
          holder
            .newAnnotation(HighlightSeverity.ERROR, "Duplicate modifier '${modifier.text}'")
            .range(modifier)
            .create()
          continue@modifiers
        }
      }

      if (localModifier != null && (modifier == hiddenModifier || modifier == fixedModifier)) {
        holder
          .newAnnotation(
            HighlightSeverity.ERROR,
            "Modifier '${modifier.text}' is not applicable to local members"
          )
          .range(modifier)
          .create()
      } else if (elementType !in applicableModifiers) {
        holder
          .newAnnotation(
            HighlightSeverity.ERROR,
            "Modifier '${modifier.text}' is not applicable to $descriptionPlural"
          )
          .range(modifier)
          .create()
      } else if (elementType == EXTERNAL && !module.isStdLibModule) {
        holder
          .newAnnotation(
            HighlightSeverity.ERROR,
            "Modifier 'external' is only allowed in standard library"
          )
          .range(modifier)
          .create()
      }
    }
  }

  private fun checkUnresolvedProperty(
    property: PklProperty,
    propertyType: Type,
    base: PklBaseModule,
    holder: AnnotationHolder,
    context: PklProject?
  ) {

    if (propertyType != Unknown) {
      // could determine property type -> property definition was found
      return
    }

    if (property.isDefinition(context)) return

    // this may be expensive to recompute
    // (was already computed during `element.computeDefinitionType`)
    val thisType = property.computeThisType(base, mapOf(), context)
    if (thisType == Unknown) return

    if (thisType.isSubtypeOf(base.objectType, base, context)) {
      if (thisType.isSubtypeOf(base.dynamicType, base, context)) return

      // should be able to find a definition
      val visitor =
        ResolveVisitors.firstElementNamed(
          property.name,
          base,
        )
      if (Resolvers.resolveQualifiedAccess(thisType, true, base, visitor, context) == null) {
        createAnnotation(
          if (thisType.isUnresolvedMemberFatal(base, context)) HighlightSeverity.ERROR
          else HighlightSeverity.WARNING,
          property.nameIdentifier.textRange,
          "Unresolved property: ${property.name}",
          "Unresolved property: <code>${property.name.escapeXml()}</code>",
          holder,
          PklProblemGroups.unresolvedElement,
          property
        )
      }
    }
  }

  private fun checkObjectSpread(
    base: PklBaseModule,
    o: PklForGenerator,
    holder: AnnotationHolder,
    context: PklProject?
  ) {
    if (isSuppressed(o, PklProblemGroups.replaceForGeneratorWithSpread)) return
    val keyIdentifier = if (o.keyValueVars.size > 1) o.keyValueVars[0] else null
    val elemIdentifier =
      if (o.keyValueVars.size > 1) o.keyValueVars[1] else o.keyValueVars.firstOrNull() ?: return
    if (o.objectBody?.members?.toList()?.size?.let { it > 1 } == true) return
    val thisType =
      o.parent?.computeThisType(base, mapOf(), context)?.toClassType(base, context) ?: return
    val iterableType =
      o.iterableExpr?.computeExprType(base, mapOf(), context)?.toClassType(base, context) ?: return
    // only offer replacement if valid iterable type.
    val expectedIterableType = base.spreadType(thisType)
    if (!iterableType.isSubtypeOf(expectedIterableType, base, context)) return
    when (val member = o.objectBody?.members?.elementAtOrNull(0)) {
      // for (elem in iterable) { elem }
      is PklObjectElement -> {
        val expr = member.expr
        if (expr !is PklUnqualifiedAccessExpr || expr.text != elemIdentifier.text) return
      }
      // for (key, value in iterable) { [key] = value }
      // only equivalent to spread if the target is a Map or a Mapping. `[0] = <expr>` amends
      // element
      // index 0 in the case of Dynamic/Listing, whereas spread will append.
      is PklObjectEntry -> {
        if (!iterableType.classEquals(base.mappingType) && !iterableType.classEquals(base.mapType))
          return
        val keyExpr = member.keyExpr
        val valueExpr = member.valueExpr
        if (keyExpr !is PklUnqualifiedAccessExpr || valueExpr !is PklUnqualifiedAccessExpr) return
        if (keyExpr.text != keyIdentifier?.text || valueExpr.text != elemIdentifier?.text) return
      }
      else -> return
    }
    holder
      .newAnnotation(HighlightSeverity.WARNING, "For generator can be replaced with spread syntax")
      .apply {
        range(o.textRange)
        withFix(PklReplaceWithSpreadQuickFix(o))
        for (fix in
          PklProblemGroups.replaceForGeneratorWithSpread.getSuppressQuickFixes(
            o,
            holder.currentFile
          )) {
          withFix(fix)
        }
        highlightType(ProblemHighlightType.WEAK_WARNING)
        problemGroup(PklProblemGroups.replaceForGeneratorWithSpread)
      }
      .create()
  }
}
