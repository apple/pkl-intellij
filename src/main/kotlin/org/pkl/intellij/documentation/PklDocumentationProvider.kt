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
package org.pkl.intellij.documentation

import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.psi.PsiDocCommentBase
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.isAncestor
import com.intellij.psi.util.parentOfTypes
import java.util.function.Consumer
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser
import org.pkl.intellij.documentation.DocumentationTypeNameRenderer.renderModuleName
import org.pkl.intellij.documentation.DocumentationTypeNameRenderer.renderTypeName
import org.pkl.intellij.psi.*
import org.pkl.intellij.resolve.ResolveVisitors
import org.pkl.intellij.resolve.Resolvers
import org.pkl.intellij.type.*
import org.pkl.intellij.util.escapeXml

class PklDocumentationProvider : AbstractDocumentationProvider() {
  override fun getQuickNavigateInfo(element: PsiElement, originalElement: PsiElement): String? =
    buildString {
      if (!renderSignature(element, originalElement)) return null
    }

  override fun generateDoc(element: PsiElement, originalElement: PsiElement?): String? {
    if (element is PklProperty && !element.isDefinition) {
      val target = element.propertyName.reference?.resolve() ?: return null
      return generateDoc(target, originalElement)
    }

    return buildString {
      append(DocumentationMarkup.DEFINITION_START)
      if (!renderSignature(element, originalElement)) return null
      append(DocumentationMarkup.DEFINITION_END)

      val docComment = (element as? PklDocCommentOwner)?.effectiveDocComment()
      if (docComment != null) {
        append(DocumentationMarkup.CONTENT_START)
        append(renderDocComment(docComment))
        append(DocumentationMarkup.CONTENT_END)
      }
    }
  }

  override fun generateRenderedDoc(element: PsiDocCommentBase): String {
    return renderDocComment((element as PklDocComment))
  }

  /**
   * Would be great to show `effectiveDocComment()` in reader mode, but it doesn't seem possible:
   * - Kotlin inherits doc comments like Pkl but Kotlin plugin doesn't show them in reader mode
   * - there is [findDocComment], but it's poorly documented, I couldn't find any overriding
   *   implementation (e.g., in intellij-community or intellij-rust), and my implementation attempt
   *   didn't solve the problem at hand
   */
  override fun collectDocComments(file: PsiFile, sink: Consumer<in PsiDocCommentBase>) {
    if (file !is PklModule) return

    file.docComment?.let(sink::accept)
    for (moduleMember in file.members) {
      moduleMember.docComment?.let(sink::accept)
      if (moduleMember is PklClass) {
        for (classMember in moduleMember.members) {
          classMember.docComment?.let(sink::accept)
        }
      }
    }
  }

  override fun getDocumentationElementForLink(
    psiManager: PsiManager,
    link: String,
    context: PsiElement
  ): PsiElement? =
    when {
      link.contains('.') -> resolveQualifiedLink(psiManager, link, context)
      else -> resolveUnqualifiedLink(psiManager, link, context)
    }

  private fun renderDocComment(docComment: PklDocComment): String {
    val text =
      docComment.childSeq
        .filter { it.elementType == PklElementTypes.DOC_COMMENT_LINE }
        .map { it.text.substring(if (it.text.length > 3 && it.text[3].isWhitespace()) 4 else 3) }
        .joinToString("\n")

    val tree = MarkdownParser(PkldocFlavorDescriptor).buildMarkdownTreeFromString(text)
    return HtmlGenerator(text, tree, PkldocFlavorDescriptor).generateHtml()
  }

  private fun resolveQualifiedLink(
    psiManager: PsiManager,
    link: String,
    context: PsiElement
  ): PsiElement? {
    val parts = link.split('.')
    val base by lazy { psiManager.project.pklBaseModule }

    when (parts.size) {
      2 -> {
        // Class.property, Class.method()
        // module.Class, module.TypeAlias, module.property, module.method()
        val classOrModuleName = parts[0]
        val rawMemberName = parts[1]
        val isMethod = rawMemberName.endsWith("()")
        val memberName = if (isMethod) rawMemberName.dropLast(2) else rawMemberName
        val visitor = ResolveVisitors.firstElementNamed(classOrModuleName, base)

        val resolveResult =
          context.enclosingModule?.imports?.find { it.memberName == classOrModuleName }?.resolve()
            as? SimpleModuleResolutionResult
        resolveResult?.resolved?.cache?.let { cache ->
          return if (isMethod) cache.methods[memberName]
          else cache.typeDefsAndProperties[memberName]
        }

        val clazz =
          Resolvers.resolveUnqualifiedTypeName(context, base, mapOf(), visitor) as? PklClass
            ?: return null
        return if (isMethod) {
          clazz.methods.find { it.name == memberName }
        } else {
          clazz.properties.find { it.name == memberName }
        }
      }
      3 -> {
        // module.Class.property, module.Class.method()
        val moduleName = parts[0]
        val className = parts[1]
        val rawMemberName = parts[2]
        val isMethod = rawMemberName.endsWith("()")
        val memberName = if (isMethod) rawMemberName.dropLast(2) else rawMemberName
        val module =
          context.enclosingModule?.imports?.find { it.memberName == moduleName }?.resolve()
            as? SimpleModuleResolutionResult
            ?: return null
        val clazz = module.resolved?.cache?.types?.get(className) as? PklClass ?: return null
        return if (isMethod) {
          clazz.methods.find { it.name == memberName }
        } else {
          clazz.properties.find { it.name == memberName }
        }
      }
      else -> return null // invalid link
    }
  }

  private fun resolveUnqualifiedLink(
    psiManager: PsiManager,
    link: String,
    context: PsiElement
  ): PsiElement? {
    val isProperty = !link.endsWith("()")
    val memberName = if (isProperty) link else link.dropLast(2)
    val base = psiManager.project.pklBaseModule
    val visitor = ResolveVisitors.firstElementNamed(memberName, base)
    return Resolvers.resolveUnqualifiedAccess(context, null, isProperty, base, mapOf(), visitor)
    // search for type in supermodules
    ?: if (isProperty) Resolvers.resolveUnqualifiedTypeName(context, base, mapOf(), visitor)
      else null
  }

  private fun Appendable.renderSignature(
    element: PsiElement,
    originalElement: PsiElement?
  ): Boolean {
    when (element) {
      is PklModule -> {
        renderModifiers(element.modifierList)
        append("module ")
        bold { escapeXml { append(element.displayName) } }

        val clause = element.extendsAmendsClause
        if (clause != null) {
          val supermodule = clause.moduleUri?.resolve() ?: return true
          append(if (clause.isAmend) " amends " else " extends ")
          bold { escapeXml { renderModuleName(supermodule, supermodule.displayName) } }
        }
      }
      is PklImportBase -> {
        // for normal imports, we resolve the element to the underlying `PklModule`, so the first
        // match is hit.
        assert(element.isGlob)
        val moduleUri = element.moduleUri?.escapedContent ?: return true
        append("import* \"")
        escapeXml { append(moduleUri) }
        append("\"")
        val base = element.project.pklBaseModule
        val definitionType = element.resolve().computeResolvedImportType(base, mapOf(), false)
        escapeXml { renderTypeAnnotation(definitionType, DocumentationTypeNameRenderer) }
      }
      is PklModuleDeclaration -> {
        val module = element.enclosingModule
        return module != null && renderSignature(module, originalElement)
      }
      is PklClass -> {
        if (!element.isLocal) {
          renderOwner(element)
        }
        renderModifiers(element.modifierList)
        append("class ")
        bold { escapeXml { append(element.name ?: "<class>") } }
        escapeXml { renderTypeParameterList(element.typeParameterList) }

        element.supertype?.let { supertype ->
          append(" extends ")
          bold { escapeXml { renderType(supertype, mapOf(), DocumentationTypeNameRenderer) } }
        }
      }
      is PklTypeAlias -> {
        renderModifiers(element.modifierList)
        if (!element.isLocal) {
          renderOwner(element)
        }
        append("typealias ")
        bold { escapeXml { append(element.name ?: "<class>") } }
        escapeXml { renderTypeParameterList(element.typeParameterList) }
      }
      is PklMethod -> {
        if (element is PklClassMethod && !element.isLocal) {
          renderOwner(element)
        }
        renderModifiers(element.modifierList)
        append("function ")
        bold { escapeXml { append(element.name ?: "<method>") } }
        escapeXml {
          renderTypeParameterList(element.typeParameterList)
          renderParameterList(element.parameterList, mapOf(), DocumentationTypeNameRenderer)
          renderTypeAnnotation(element.returnType, mapOf(), DocumentationTypeNameRenderer)
        }
      }
      is PklProperty -> {
        if (element is PklClassProperty && !element.isLocal) {
          renderOwner(element)
        }
        renderModifiers(element.modifierList)
        renderTypeAnnotation(element, element.name, element.type, originalElement)
      }
      is PklTypedIdentifier -> {
        if (element.parent is PklParameterList) {
          append("parameter ")
        }
        renderTypeAnnotation(element, element.name, element.type, originalElement)
      }
      else -> return false
    }
    return true
  }

  private fun Appendable.renderTypeAnnotation(
    element: PklElement,
    name: String?,
    type: PklType?,
    originalElement: PsiElement?
  ) {
    if (name == null) return
    val base = element.project.pklBaseModule
    bold { escapeXml { append(name) } }
    escapeXml {
      when {
        // support flow typing when showing documentation for nodes that navigate to ther nodes
        // (e.g. they aren't param declarations or class properties)
        originalElement?.isAncestor(element) == false -> {
          val visitor =
            ResolveVisitors.typeOfFirstElementNamed(
              name,
              null,
              element.project.pklBaseModule,
              isNullSafeAccess = false,
              preserveUnboundTypeVars = false
            )
          val computedType =
            Resolvers.resolveUnqualifiedAccess(
              originalElement,
              null,
              true,
              element.project.pklBaseModule,
              mapOf(),
              visitor
            )
          renderTypeAnnotation(computedType, DocumentationTypeNameRenderer)
        }
        // otherwise, if a type annotation exists, render it
        type != null -> renderTypeAnnotation(type, mapOf(), DocumentationTypeNameRenderer)
        // otherwise, render the inferred type
        else -> {
          val computedType = element.computeResolvedImportType(base, mapOf())
          renderTypeAnnotation(computedType, DocumentationTypeNameRenderer)
        }
      }
    }
  }

  private fun Appendable.renderOwner(element: PsiElement) {
    when (val owner = element.parentOfTypes(PklModule::class, PklClass::class)) {
      is PklModule -> {
        renderModuleName(owner, owner.displayName)
        append('\n')
      }
      is PklClass -> {
        escapeXml {
          renderTypeName(owner.enclosingModule, owner.name, owner.displayName)
          renderTypeParameterList(owner.typeParameterList)
          append('\n')
        }
      }
    }
  }

  private inline fun Appendable.bold(action: Appendable.() -> Unit): Appendable {
    append("<b>")
    action(this)
    append("</b>")
    return this
  }
}
