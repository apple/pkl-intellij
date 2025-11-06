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
package org.pkl.intellij.completion

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.template.TemplateBuilderFactory
import com.intellij.codeInsight.template.TemplateBuilderImpl
import com.intellij.openapi.application.ApplicationManager
import com.intellij.patterns.PlatformPatterns.psiElement
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.TokenType
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.util.parentOfType
import com.intellij.psi.util.parentOfTypes
import com.intellij.util.ProcessingContext
import org.pkl.intellij.completion.UnqualifiedAccessCompletionProvider.Group.AMEND
import org.pkl.intellij.completion.UnqualifiedAccessCompletionProvider.Group.ASSIGN
import org.pkl.intellij.packages.dto.PklProject
import org.pkl.intellij.psi.*
import org.pkl.intellij.resolve.ResolveVisitors
import org.pkl.intellij.resolve.Resolvers
import org.pkl.intellij.resolve.withoutShadowedElements
import org.pkl.intellij.type.Type
import org.pkl.intellij.type.computeThisType
import org.pkl.intellij.type.inferExprTypeFromContext
import org.pkl.intellij.type.toType
import org.pkl.intellij.util.decapitalized
import org.pkl.intellij.util.unexpectedType

class UnqualifiedAccessCompletionProvider : PklCompletionProvider() {
  // we want `foo {}` to come before `foo =` in completion lists.
  // when completing listing elements, we additionally want both
  // `default {}` and `default =` to come before (the less useful) expression completions.
  // (expression completions aren't wrapped with [PrioritizedLookupElement] at all.)
  enum class Group(val grouping: Int) {
    AMEND(2),
    ASSIGN(1)
  }

  private fun LookupElementBuilder.withGroup(group: Group): LookupElement =
    PrioritizedLookupElement.withGrouping(this, group.grouping)

  private fun LookupElementBuilder.withPriority(priority: Double): LookupElement =
    PrioritizedLookupElement.withPriority(this, priority)

  override fun addCompletions(
    parameters: CompletionParameters,
    context: ProcessingContext,
    result: CompletionResultSet
  ) {

    val position = parameters.position
    val originalFile = parameters.originalFile
    val base = originalFile.project.pklBaseModule
    val projectContext = (originalFile as? PklModule)?.pklProject
    val thisType = position.computeThisType(base, mapOf(), projectContext)
    if (thisType == Type.Unknown) return

    addInferredExprTypeCompletions(position, base, result, projectContext)

    // When completing the name of an object property definition, the AST at [position]
    // is Identifier->PklUnqualifiedAccessName->PklUnqualifiedAccessExpr->PklObjectElement,
    // not Identifier->PklPropertyName->PklObjectProperty.
    // In other words, the property name is "mistaken" for a variable expression.
    if (
      psiElement()
        .withParents(
          PklUnqualifiedAccessName::class.java,
          PklUnqualifiedAccessExpr::class.java,
          PklObjectElement::class.java
        )
        .accepts(position) || psiElement().withParent(PklPropertyName::class.java).accepts(position)
    ) {

      val alreadyDefinedProperties = collectPropertyNames(position)
      if (
        addDefinitionCompletions(
          position,
          thisType,
          alreadyDefinedProperties,
          base,
          result,
          projectContext
        )
      ) {
        return
      }
    }

    val visitor = ResolveVisitors.lookupElements(base).withoutShadowedElements()

    val allowClasses = shouldCompleteClassesOrTypeAliases(position, base, projectContext)
    Resolvers.resolveUnqualifiedAccess(
      position,
      thisType,
      isProperty = true,
      allowClasses,
      base,
      thisType.bindings,
      visitor,
      projectContext
    )
    Resolvers.resolveUnqualifiedAccess(
      position,
      thisType,
      isProperty = false,
      allowClasses,
      base,
      thisType.bindings,
      visitor,
      projectContext
    )

    result.addAllElements(visitor.result)

    result.addAllElements(EXPRESSION_LEVEL_KEYWORD_LOOKUP_ELEMENTS)
  }

  private fun shouldCompleteClassesOrTypeAliases(
    position: PsiElement,
    base: PklBaseModule,
    context: PklProject?
  ): Boolean {
    fun isClassOrTypeAlias(type: Type): Boolean =
      when (type) {
        is Type.Class -> type.classEquals(base.classType) || type.classEquals(base.typeAliasType)
        is Type.Alias -> isClassOrTypeAlias(type.unaliased(base, context))
        is Type.Union -> isClassOrTypeAlias(type.leftType) || isClassOrTypeAlias(type.rightType)
        else -> false
      }
    val expr = position.parentOfType<PklExpr>() ?: return false
    val type = expr.inferExprTypeFromContext(base, mapOf(), context)
    return isClassOrTypeAlias(type)
  }

  private fun addDefinitionCompletions(
    position: PsiElement,
    thisType: Type,
    alreadyDefinedProperties: Set<String>,
    base: PklBaseModule,
    result: CompletionResultSet,
    context: PklProject?
  ): Boolean {

    return when {
      thisType == Type.Unknown -> false
      thisType is Type.Union ->
        addDefinitionCompletions(
          position,
          thisType.leftType,
          alreadyDefinedProperties,
          base,
          result,
          context
        ) &&
          addDefinitionCompletions(
            position,
            thisType.rightType,
            alreadyDefinedProperties,
            base,
            result,
            context
          )
      thisType.isSubtypeOf(base.typedType, base, context) -> {
        val enclosingDef =
          position.parentOfTypes(
            PklModule::class,
            PklClass::class,
            // stop classes
            PklExpr::class,
            PklObjectBody::class
          )
        val isClassDef =
          when (enclosingDef) {
            is PklModule -> !enclosingDef.extendsAmendsClause.isAmend
            is PklClass -> true
            else -> false
          }
        addTypedCompletions(alreadyDefinedProperties, isClassDef, thisType, base, result, context)
        result.addAllElements(DEFINITION_LEVEL_KEYWORD_LOOKUP_ELEMENTS)

        when (position) {
          is PklExpr -> {}
          is PklObjectBody -> {}
          else -> {
            when (enclosingDef) {
              is PklModule -> {
                result.addAllElements(MODULE_LEVEL_KEYWORD_LOOKUP_ELEMENTS)
              }
              is PklClass -> {
                result.addAllElements(CLASS_LEVEL_KEYWORD_LOOKUP_ELEMENTS)
              }
              else -> {}
            }
          }
        }

        true // typed objects cannot have elements
      }
      else -> {
        val thisClassType = thisType.toClassType(base, context) ?: return false
        when {
          thisClassType.classEquals(base.mappingType) -> {
            addMappingCompletions(alreadyDefinedProperties, thisClassType, base, result, context)
            result.addAllElements(DEFINITION_LEVEL_KEYWORD_LOOKUP_ELEMENTS)
            true // mappings cannot have elements
          }
          thisClassType.classEquals(base.listingType) -> {
            addListingCompletions(alreadyDefinedProperties, thisClassType, base, result, context)
            result.addAllElements(DEFINITION_LEVEL_KEYWORD_LOOKUP_ELEMENTS)
            false
          }
          thisClassType.classEquals(base.dynamicType) -> {
            result.addAllElements(DEFINITION_LEVEL_KEYWORD_LOOKUP_ELEMENTS)
            false
          }
          else -> false
        }
      }
    }
  }

  private fun addTypedCompletions(
    alreadyDefinedProperties: Set<String>,
    isClassOrModule: Boolean,
    thisType: Type,
    base: PklBaseModule,
    result: CompletionResultSet,
    context: PklProject?
  ) {

    val properties =
      when (thisType) {
        is Type.Class -> thisType.psi.cache(context).properties
        is Type.Module -> thisType.psi.cache(context).properties
        else -> unexpectedType(thisType)
      }

    for ((propertyName, property) in properties) {
      if (propertyName in alreadyDefinedProperties) continue
      if (property.isFixedOrConst && !isClassOrModule) continue

      val propertyType = property.type.toType(base, thisType.bindings, context)
      val amendedPropertyType = propertyType.amended(base, context)
      if (amendedPropertyType != Type.Nothing && amendedPropertyType != Type.Unknown) {
        val amendingPropertyType = propertyType.amending(base, context)
        result.addElement(createPropertyAmendElement(propertyName, amendingPropertyType, property))
      }
      result.addElement(createPropertyAssignElement(propertyName, propertyType, property))
    }
  }

  private fun addMappingCompletions(
    alreadyDefinedProperties: Set<String>,
    thisType: Type.Class,
    base: PklBaseModule,
    result: CompletionResultSet,
    context: PklProject?
  ) {

    val keyType = thisType.typeArguments[0]
    val valueType = thisType.typeArguments[1]

    val amendedValueType = valueType.amended(base, context)
    if (amendedValueType != Type.Nothing && amendedValueType != Type.Unknown) {
      val amendingValueType = valueType.amending(base, context)
      if ("default" !in alreadyDefinedProperties) {
        result.addElement(
          createPropertyAmendElement("default", amendingValueType, base.mappingDefaultProperty)
        )
      }
      result.addElement(createEntryAmendElement(keyType, amendingValueType, base))
    }
    if ("default" !in alreadyDefinedProperties) {
      result.addElement(
        createPropertyAssignElement("default", valueType, base.mappingDefaultProperty)
      )
    }
    result.addElement(createEntryAssignElement(keyType, valueType, base))
  }

  private fun addListingCompletions(
    alreadyDefinedProperties: Set<String>,
    thisType: Type.Class,
    base: PklBaseModule,
    result: CompletionResultSet,
    context: PklProject?
  ) {

    val elementType = thisType.typeArguments[0]

    val amendedElementType = elementType.amended(base, context)
    if (amendedElementType != Type.Nothing && amendedElementType != Type.Unknown) {
      val amendingElementType = elementType.amending(base, context)
      if ("default" !in alreadyDefinedProperties) {
        result.addElement(
          createPropertyAmendElement("default", amendingElementType, base.listingDefaultProperty)
        )
      }
      result.addElement(createPropertyAmendElement("new", amendingElementType, null))
    }
    if ("default" !in alreadyDefinedProperties) {
      result.addElement(
        createPropertyAssignElement("default", elementType, base.listingDefaultProperty)
      )
    }
  }

  private fun doAddInferredExprTypeCompletions(
    // example: `(Key) -> Value` (used to infer parameter name suggestions `key` and `value`)
    genericType: Type,
    // example: `(String) -> Int` (used as code completion element's display type)
    actualType: () -> Type,
    base: PklBaseModule,
    result: CompletionResultSet,
    context: PklProject?
  ) {
    val unaliasedGenericType = genericType.unaliased(base, context)

    when {
      // e.g., `(Key) -> Value` or `Function1<Key, Value>`
      unaliasedGenericType is Type.Class && unaliasedGenericType.isFunctionType -> {
        val parameterTypes = unaliasedGenericType.typeArguments.dropLast(1)
        val parameterNames = getLambdaParameterNames(parameterTypes, base)
        result.addElement(createFunctionLiteralElement(actualType.invoke(), parameterNames))
      }
      // e.g., `((Key) -> Value)|((Int, Key) -> Value)`
      unaliasedGenericType is Type.Union -> {
        val unaliasedActualType by lazy {
          actualType.invoke().unaliased(base, context) as Type.Union
        }
        doAddInferredExprTypeCompletions(
          unaliasedGenericType.leftType,
          { unaliasedActualType.leftType },
          base,
          result,
          context,
        )
        doAddInferredExprTypeCompletions(
          unaliasedGenericType.rightType,
          { unaliasedActualType.rightType },
          base,
          result,
          context
        )
      }
      else -> return
    }
  }

  private fun addInferredExprTypeCompletions(
    position: PsiElement,
    base: PklBaseModule,
    result: CompletionResultSet,
    context: PklProject?
  ) {
    val expr =
      position.parentOfTypes(PklUnqualifiedAccessExpr::class, PklPropertyName::class)
        as? PklUnqualifiedAccessExpr
        ?: return

    doAddInferredExprTypeCompletions(
      expr.inferExprTypeFromContext(
        base,
        mapOf(),
        context,
        false,
      ),
      {
        expr.inferExprTypeFromContext(
          base,
          mapOf(),
          context,
          true,
        )
      },
      base,
      result,
      context
    )
  }

  private fun getLambdaParameterNames(
    parameterTypes: List<Type>,
    base: PklBaseModule
  ): List<String> {
    assert(parameterTypes.size <= 5)

    val result = mutableListOf<String>()
    var nextIntParam = 'i'
    val nameCounts = mutableMapOf<String, Int>()

    fun addName(name: String) {
      val count = nameCounts[name]
      when {
        count == null -> {
          nameCounts[name] = -result.size // store (inverse of) index of first occurrence
          result.add(name)
        }
        count <= 0 -> { // name has one occurrence at index -count
          nameCounts[name] = 2
          result[-count] = "${name}1" // rename first occurrence
          result.add("${name}2")
        }
        else -> {
          nameCounts[name] = count + 1
          result.add("${name}${count + 1}")
        }
      }
    }

    for (paramType in parameterTypes) {
      val paramName =
        when (paramType) {
          is Type.Class ->
            if (paramType == base.intType) {
              // won't run out of these because lambda has at most 5 parameters
              (nextIntParam++).toString()
            } else {
              paramType.psi.name?.decapitalized() ?: "param"
            }
          is Type.Module -> paramType.referenceName.decapitalized()
          is Type.Alias -> paramType.psi.name?.decapitalized() ?: "param"
          is Type.Variable -> paramType.psi.name?.decapitalized() ?: "param"
          else -> "param"
        }
      addName(paramName)
    }

    return result
  }

  private val PklProperty?.modifiers: String
    get() {
      if (this == null || !isFixed) return ""
      val self = this
      // if the parent is fixed/const, the amending element must also be declared fixed/const too.
      // although not necessary, add the hidden modifier too so that it's clear to users.
      return buildString {
        val elems = self.modifierList.elements
        for (modifier in elems) {
          append("${modifier.text} ")
        }
      }
    }

  private fun createPropertyAmendElement(
    propertyName: String,
    propertyType: Type,
    propertyPsi: PklProperty?
  ): LookupElement {
    return LookupElementBuilder.create("${propertyPsi.modifiers}$propertyName {\n\n}")
      .withPresentableText("${propertyPsi.modifiers}$propertyName {…}")
      .bold()
      .withPsiElement(propertyPsi)
      .withIcon(propertyPsi?.getIcon(0))
      .withTypeText(propertyType.render(), true)
      .withInsertHandler { context, _ ->
        // automatically created/inserted based on text passed to `create()`
        // recomputed because adjustLineIndent() may invalidate the element
        fun propertyElement(): PklElement {
          return when (
            val element = context.file.findElementAt(context.startOffset)!!.skipWhitespace()!!
          ) {
            is PklObjectElement -> element.expr as PklObjectBodyOwner // <ws> new { ... }
            else -> {
              // Element can be contained in BodyOwner or BodyListOwner
              // (or both, in which case, pick the closest matching one)
              val bodyListOwner = element.nonStrictParentOfType<PklObjectBodyListOwner>()
              val bodyOwner =
                element.nonStrictParentOfType<PklObjectBodyOwner>() ?: return bodyListOwner!!
              return if ((bodyListOwner?.textRange?.endOffset ?: 0) > bodyOwner.textRange.endOffset)
                bodyOwner
              else bodyListOwner!!
            }
          }
        }

        val codeStyleManager = CodeStyleManager.getInstance(context.project)

        // necessary for closing brace to be indented
        codeStyleManager.adjustLineIndent(context.file, propertyElement().textRange)

        fun lastNewLine() =
          propertyElement().let { oneOrMoreBodyOwner ->
            val predicate = { el: PsiElement ->
              el.elementType == TokenType.WHITE_SPACE && el.textContains('\n')
            }
            when (oneOrMoreBodyOwner) {
              is PklObjectBodyOwner ->
                oneOrMoreBodyOwner.objectBody!!.lastChildMatching(predicate)!!
              is PklObjectBodyListOwner ->
                oneOrMoreBodyOwner.objectBodyList!!.reversed().firstNotNullOf {
                  it.lastChildMatching(predicate)
                }
              else -> null
            }
          }!!

        // necessary for empty line between braces to be indented
        val newOffset = codeStyleManager.adjustLineIndent(context.file, lastNewLine().textOffset)
        context.editor.caretModel.moveToOffset(newOffset + 1) // not sure why `+ 1` is needed
      }
      .withGroup(AMEND)
  }

  private fun createPropertyAssignElement(
    propertyName: String,
    propertyType: Type,
    propertyPsi: PklProperty
  ): LookupElement {
    return LookupElementBuilder.create("${propertyPsi.modifiers}$propertyName = ")
      .bold()
      .withPsiElement(propertyPsi)
      .withIcon(propertyPsi.getIcon(0))
      .withTypeText(propertyType.render(), true)
      .withGroup(ASSIGN)
  }

  private fun createEntryAmendElement(
    keyType: Type,
    valueType: Type,
    base: PklBaseModule
  ): LookupElement {
    val templateBuilderFactory =
      ApplicationManager.getApplication().getService(TemplateBuilderFactory::class.java)
    val defaultKey = createDefaultKey(keyType, base)

    return LookupElementBuilder.create("[$defaultKey] {\n\n}")
      .withPresentableText("[$defaultKey] {…}")
      .bold()
      .withTypeText(valueType.render(), true)
      .withInsertHandler { context, _ ->
        // automatically created/inserted based on text passed to `create()`
        // recomputed because adjustLineIndent() may invalidate the element
        fun entryElement(): PklObjectEntry {
          val element = context.file.findElementAt(context.startOffset)!!
          return element.skipWhitespace()!!.nonStrictParentOfType()!!
        }

        val codeStyleManager = CodeStyleManager.getInstance(context.project)

        // necessary for closing brace to be indented
        codeStyleManager.adjustLineIndent(context.file, entryElement().textRange)

        // recomputed because adjustLineIndent() may invalidate the element
        fun lastNewLine() =
          entryElement().objectBodyList.last().lastChildMatching {
            it.elementType == TokenType.WHITE_SPACE && it.textContains('\n')
          }!!

        // necessary for empty line between braces to be indented
        codeStyleManager.adjustLineIndent(context.file, lastNewLine().textOffset)

        val documentManager = PsiDocumentManager.getInstance(context.project)
        documentManager.doPostponedOperationsAndUnblockDocument(context.document)

        val templateBuilder =
          templateBuilderFactory.createTemplateBuilder(entryElement()) as TemplateBuilderImpl
        templateBuilder.replaceElement(entryElement().keyExpr!!, defaultKey)
        templateBuilder.setEndVariableBefore(lastNewLine())
        templateBuilder.run(context.editor, true)
      }
      .withGroup(AMEND)
  }

  private fun createEntryAssignElement(
    keyType: Type,
    valueType: Type,
    base: PklBaseModule
  ): LookupElement {
    val templateBuilderFactory =
      ApplicationManager.getApplication().getService(TemplateBuilderFactory::class.java)
    val defaultKey = createDefaultKey(keyType, base)

    return LookupElementBuilder.create("[$defaultKey] =")
      .bold()
      .withTypeText(valueType.render(), true)
      .withInsertHandler { context, _ ->
        fun entry(): PklObjectEntry {
          val element = context.file.findElementAt(context.startOffset)!!
          return element.skipWhitespace()!!.nonStrictParentOfType()!!
        }

        fun assignToken(): LeafPsiElement {
          return entry().firstChildTokenOfType(PklElementTypes.ASSIGN)!!
        }

        // add a space after `=`
        // this is the most robust (wrt. parse errors) solution I could find
        val space = PklPsiFactory.createToken(" ", context.project)
        entry().addAfter(space, assignToken())

        val documentManager = PsiDocumentManager.getInstance(context.project)
        documentManager.doPostponedOperationsAndUnblockDocument(context.document)

        val templateBuilder =
          templateBuilderFactory.createTemplateBuilder(entry()) as TemplateBuilderImpl
        templateBuilder.replaceElement(entry().keyExpr!!, defaultKey)
        // position caret behind `= ` (note the space)
        // this is the most robust (wrt. parse errors) solution I could find
        templateBuilder.setEndVariableAfter(assignToken().nextSibling)
        templateBuilder.run(context.editor, true)
      }
      .withGroup(ASSIGN)
  }

  private fun createFunctionLiteralElement(
    functionType: Type,
    parameterNames: List<String>
  ): LookupElement {
    val templateBuilderFactory =
      ApplicationManager.getApplication().getService(TemplateBuilderFactory::class.java)

    val text = "(${parameterNames.joinToString(", ")}) ->"
    return LookupElementBuilder.create(text)
      .withPresentableText("$text …")
      .bold()
      .withTypeText(functionType.render(), true)
      .withInsertHandler { context, _ ->
        fun functionLiteral(): PklFunctionLiteral {
          val element = context.file.findElementAt(context.startOffset)!!
          return element.skipWhitespace()!!.nonStrictParentOfType()!!
        }

        fun arrowToken(): LeafPsiElement {
          return functionLiteral().firstChildTokenOfType(PklElementTypes.ARROW)!!
        }

        // add a space after `->`
        // this is the most robust (wrt. parse errors) solution I could find
        val space = PklPsiFactory.createToken(" ", context.project)
        val token = arrowToken()
        token.parent.addAfter(space, token)

        val documentManager = PsiDocumentManager.getInstance(context.project)
        documentManager.doPostponedOperationsAndUnblockDocument(context.document)

        val templateBuilder =
          templateBuilderFactory.createTemplateBuilder(functionLiteral()) as TemplateBuilderImpl
        for ((idx, parameter) in functionLiteral().parameterList.elements.withIndex()) {
          templateBuilder.replaceElement(parameter, parameterNames[idx])
        }
        // position caret behind `-> ` (note the space)
        // this is the most robust (wrt. parse errors) solution I could find
        templateBuilder.setEndVariableAfter(arrowToken().nextSibling)
        templateBuilder.run(context.editor, true)
      }
      .withPriority(1.0) // put this element at the top of the completion list
  }

  private fun collectPropertyNames(context: PsiElement): Set<String> {
    return when (
      val container = context.parentOfTypes(PklModule::class, PklClass::class, PklObjectBody::class)
    ) {
      is PklModule -> container.properties.mapTo(mutableSetOf()) { it.name }
      is PklClass -> container.properties.mapTo(mutableSetOf()) { it.name }
      is PklObjectBody -> container.properties.mapTo(mutableSetOf()) { it.name }
      else -> setOf()
    }
  }

  private fun createDefaultKey(keyType: Type, base: PklBaseModule): String =
    when (keyType) {
      base.stringType -> "\"key\""
      base.intType -> "123"
      base.booleanType -> "true"
      else -> "key"
    }
}
