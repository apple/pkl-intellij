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

import com.intellij.lang.ASTNode
import com.intellij.openapi.progress.ProgressIndicatorProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Iconable
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.TokenType
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.*
import com.intellij.ui.LayeredIcon
import javax.swing.Icon
import org.pkl.intellij.PklIcons
import org.pkl.intellij.PklImportOptimizer.ImportInfo
import org.pkl.intellij.PklLanguage
import org.pkl.intellij.packages.dto.PklProject
import org.pkl.intellij.packages.pklPackageService
import org.pkl.intellij.psi.PklElementTypes.IMPORT_GLOB
import org.pkl.intellij.type.*
import org.pkl.intellij.util.appendNumericSuffix
import org.pkl.intellij.util.getContextualCachedValue
import org.pkl.intellij.util.inferImportPropertyName
import org.pkl.intellij.util.unexpectedType

inline fun ASTNode?.eachChild(consumer: (ASTNode) -> Unit) {
  var child = this?.firstChildNode
  while (child != null) {
    consumer(child)
    child = child.treeNext
  }
}

fun ASTNode.findPrevByType(type: IElementType): ASTNode? {
  var result: ASTNode? = treePrev
  while (result != null) {
    if (result.elementType == type) return result
    result = result.treePrev
  }
  return null
}

val PsiElement.enclosingModule: PklModule?
  get() = containingFile as PklModule?

val PsiElement.isInPklBaseModule: Boolean
  get() = enclosingModule?.declaredName?.textMatches("pkl.base") == true

val PsiElement.isInPklStdLibModule: Boolean
  get() = enclosingModule?.declaredName?.text?.startsWith("pkl.") == true

val PsiElement.isInPklProject: Boolean
  get() = enclosingModule?.pklProject != null

val PsiElement.isInPackage: Boolean
  get() = containingFile.virtualFile?.let { project.pklPackageService.isInPackage(it) } ?: false

val PsiElement.elementType: IElementType?
  get() = PsiUtilCore.getElementType(this)

/**
 * Copy of [PsiManager.areElementsEquivalent] that doesn't require access to [PsiManager]/[Project].
 */
fun areElementsEquivalent(element1: PsiElement?, element2: PsiElement?): Boolean {
  // We hope this method is being called often enough to cancel daemon processes smoothly
  ProgressIndicatorProvider.checkCanceled()

  if (element1 === element2) return true
  if (element1 == null || element2 == null) {
    return false
  }
  return (element1 == element2) ||
    element1.isEquivalentTo(element2) ||
    element2.isEquivalentTo(element1)
}

// zero cost abstraction that allows easy switching between implementations (not sure which is best)
inline fun PsiElement?.eachChild(consumer: (PsiElement) -> Unit) {
  var child = this?.firstChild
  while (child != null) {
    consumer(child)
    child = child.nextSibling
  }
}

inline fun PsiElement?.eachChildBack(consumer: (PsiElement) -> Unit) {
  var child = this?.lastChild
  while (child != null) {
    consumer(child)
    child = child.prevSibling
  }
}

fun PsiElement.firstChildTokenType(): PklTokenType? {
  eachChild { child ->
    if (child is LeafPsiElement) {
      val elementType = child.elementType
      if (elementType is PklTokenType) return elementType
    }
  }
  return null
}

fun PsiElement.firstChildToken(): LeafPsiElement? {
  eachChild { child ->
    if (child is LeafPsiElement) {
      val elementType = child.elementType
      if (elementType is PklTokenType) return child
    }
  }
  return null
}

fun PsiElement.firstChildTokenOfType(type: IElementType): LeafPsiElement? {
  assert(type is PklTokenType)
  eachChild { child -> if (child is LeafPsiElement && child.elementType === type) return child }
  return null
}

fun PsiElement.firstChildTokenOfEitherType(
  type1: IElementType,
  type2: IElementType
): LeafPsiElement? {
  assert(type1 is PklTokenType)
  assert(type2 is PklTokenType)
  eachChild { child ->
    if (child is LeafPsiElement && (child.elementType === type1 || child.elementType == type2))
      return child
  }
  return null
}

fun PsiElement.firstChildOfType(type: IElementType): PsiElement? {
  eachChild { child -> if (child.elementType == type) return child }
  return null
}

@Suppress("unused")
fun PsiElement.lastChildOfType(type: IElementType): PsiElement? {
  eachChildBack { child -> if (child.elementType == type) return child }
  return null
}

fun PsiElement.lastChildMatching(predicate: (PsiElement) -> Boolean): PsiElement? {
  eachChildBack { child -> if (predicate(child)) return child }
  return null
}

fun PsiElement.singleChildOfType(type: IElementType): PsiElement? {
  val first = firstChild ?: return null
  val last = lastChild
  return when {
    first !== last -> null
    first.elementType != type -> null
    else -> first
  }
}

inline fun <reified T> PsiElement.firstChildOfClass(): T? {
  eachChild { child -> if (child is T) return child }
  return null
}

inline fun <reified T> PsiElement.secondChildOfClass(): T? {
  var first = true
  eachChild { child ->
    if (child is T) {
      if (first) first = false else return child
    }
  }
  return null
}

inline fun <reified T> PsiElement.lastChildOfClass(): T? {
  return lastChildMatching { it is T } as? T
}

/**
 * Note: Unlike some implementations of [PsiElement.getChildren], the sequence returned by this
 * method includes leaf elements (other than whitespace elements).
 */
val PsiElement.childSeq: Sequence<PsiElement>
  get() {
    return generateSequence(firstChild) { prev ->
      var next = prev.nextSibling
      while (next != null) {
        if (next !is PsiWhiteSpace) return@generateSequence next
        next = next.nextSibling
      }
      null
    }
  }

inline fun <reified T : PsiElement> PsiElement.childrenOfClass(): Sequence<T> {
  return generateSequence(firstChildOfClass<T>()) { prev ->
    var next = prev.nextSibling
    while (next != null) {
      if (next is T) return@generateSequence next
      next = next.nextSibling
    }
    null
  }
}

fun PsiElement.skipWhitespace(): PsiElement? {
  var leaf: PsiElement? = this
  while (leaf is PsiWhiteSpace) {
    leaf = leaf.nextSibling
  }
  return leaf
}

fun PsiElement.skipWhitespaceBack(): PsiElement? {
  var leaf: PsiElement? = this
  while (leaf is PsiWhiteSpace) {
    leaf = leaf.prevSibling
  }
  return leaf
}

fun PsiElement.ensureFollowedByNewlines(minCount: Int) {
  val whitespace = nextSibling as? PsiWhiteSpace
  val newlineCount = whitespace?.text?.count { it == '\n' } ?: 0
  if (newlineCount < minCount) {
    parent?.addAfter(PklPsiFactory.createToken("\n".repeat(minCount - newlineCount), project), this)
  }
}

inline fun <reified T : PsiElement> PsiElement.nonStrictParentOfType(): T? {
  return when (this) {
    is T -> this
    else -> parentOfType()
  }
}

val PklReadExpr.isNullable: Boolean
  get() = firstChildTokenType() == PklElementTypes.READ_OR_NULL

val PklReadExpr.isGlob: Boolean
  get() = firstChildTokenType() == PklElementTypes.READ_GLOB

val PklObjectSpread.isNullable: Boolean
  get() = firstChildTokenType() == PklElementTypes.QSPREAD

val PklImport.memberName: String?
  get() = identifier?.text ?: moduleUri?.escapedContent?.let { inferImportPropertyName(it) }

val PklMethod.isOverridable: Boolean
  get() =
    when {
      isLocal -> false
      isAbstract -> true
      this is PklObjectMethod -> false
      this is PklClassMethod -> owner?.isAbstractOrOpen ?: false
      else -> unexpectedType(this)
    }

fun PklMethod.isVarArgs(base: PklBaseModule): Boolean {
  val varArgsType = base.varArgsType ?: return false
  val lastParam = parameterList?.elements?.lastOrNull() ?: return false
  val lastParamType =
    // optimization: varargs is only available in stdlib, no need to provide context.
    lastParam.type.toType(base, mapOf(), null).toClassType(base, null)
  return lastParamType != null && lastParamType.classEquals(varArgsType)
}

val PklModuleMember.owner: PklTypeDefOrModule?
  get() = parentOfTypes(PklClass::class, PklModule::class)

val PklClass.supertype: PklType?
  get() = extendsClause?.type

inline fun PklClass.eachSuperclassOrModule(
  context: PklProject?,
  consumer: (PklTypeDefOrModule) -> Unit
) {
  var clazz = superclass(context)
  var supermostClass = this

  while (clazz != null) {
    consumer(clazz)
    supermostClass = clazz
    clazz = clazz.superclass(context)
  }

  var module = supermostClass.supermodule(context)
  while (module != null) {
    consumer(module)
    module = module.supermodule(context)
    if (module == null) {
      val base = project.pklBaseModule
      consumer(base.moduleType.psi)
      consumer(base.anyType.psi)
    }
  }
}

fun PklClass.superclass(context: PklProject?): PklClass? {
  return when (val st = supertype) {
    is PklDeclaredType -> st.typeName.resolve(context) as? PklClass?
    is PklModuleType -> null // see PklClass.supermodule
    null ->
      when {
        isPklBaseAnyClass -> null
        else -> project.pklBaseModule.typedType.psi
      }
    else -> unexpectedType(st)
  }
}

// Non-null when [this] extends a module (class).
// Ideally, [PklClass.superclass] would cover this case,
// but we don't have a common abstraction for PklClass and PklModule(Class),
// and it seems challenging to introduce one.
fun PklClass.supermodule(context: PklProject?): PklModule? {
  return when (val st = supertype) {
    is PklDeclaredType -> st.typeName.resolve(context) as? PklModule?
    is PklModuleType -> enclosingModule
    else -> null
  }
}

fun PklClass.isSubclassOf(other: PklClass, context: PklProject?): Boolean {
  // optimization
  if (this === other) return true

  // optimization
  if (!other.isAbstractOrOpen) return areElementsEquivalent(this, other)

  var clazz: PklClass? = this
  while (clazz != null) {
    if (areElementsEquivalent(clazz, other)) return true
    if (clazz.supermodule(context) != null) {
      return project.pklBaseModule.moduleType.psi.isSubclassOf(other, context)
    }
    clazz = clazz.superclass(context)
  }
  return false
}

fun PklClass.isStrictSubclassOf(other: PklClass, context: PklProject?): Boolean {
  superclass(context)?.let {
    return it.isSubclassOf(other, context)
  }
  supermodule(context)?.let { project.pklBaseModule.moduleType.psi.isSubclassOf(other, context) }
  return false
}

fun PklClass.isSubclassOf(other: PklModule, context: PklProject?): Boolean {
  // optimization
  if (!other.isAbstractOrOpen) return false

  var clazz = this
  var superclass = clazz.superclass(context)
  while (superclass != null) {
    clazz = superclass
    superclass = superclass.superclass(context)
  }
  var module = clazz.supermodule(context)
  while (module != null) {
    if (areElementsEquivalent(module, other)) return true
    module = module.supermodule(context)
  }
  return false
}

/** Assumes `!this.isSubclassOf(other)`. */
fun PklClass.hasCommonSubclassWith(other: PklClass, context: PklProject?): Boolean =
  other.isSubclassOf(this, context)

val PklClass.isPklBaseAnyClass: Boolean
  get() {
    return name == "Any" && areElementsEquivalent(this, project.pklBaseModule.anyType.psi)
  }

// returns PklTypeDefOrModule or PklTypeParameter
fun PklTypeName.resolve(context: PklProject?): PklElement? = simpleName.resolve(context)

// returns PklTypeOrModule or PklTypeParameter
fun PklSimpleTypeName.resolve(context: PklProject?): PklElement? =
  pklReference?.resolveContextual(context) as? PklElement

fun PklPropertyName.resolve(context: PklProject?): PklElement? =
  pklReference?.resolveContextual(context) as? PklElement

val PklModuleUri.escapedContent: String?
  get() = stringConstant.content.escapedText()

fun PklModuleUri.resolve(context: PklProject?): PklModule? =
  escapedContent?.let { text ->
    PklModuleUriReference.resolve(text, text, containingFile, enclosingModule, project, context)
      as? PklModule?
  }

fun resolveModuleUriGlob(element: PklModuleUri, context: PklProject?): List<PklModule> =
  element.escapedContent
    ?.let { text ->
      val psiManager = PsiManager.getInstance(element.project)
      CachedValuesManager.getManager(element.project).getCachedValue(element) {
        CachedValueProvider.Result.create(
          PklModuleUriReference.resolveGlob(text, text, element, context),
          psiManager.modificationTracker.forLanguage(PklLanguage),
        )
      }
    }
    ?.filterIsInstance<PklModule>()
    ?: emptyList()

fun PklModuleUri.resolveGlob(context: PklProject?): List<PklModule> =
  resolveModuleUriGlob(this, context)

/**
 * Finds or inserts (in sort order) an import for [uri] and returns its member name. Returns `null`
 * if this operation could not be performed (e.g., due to invalid code).
 */
fun PklImportList.findOrInsertImport(uri: String): String? {
  val defaultImportName = inferImportPropertyName(uri) ?: return null
  var effectiveImportName: String = defaultImportName

  val oldImports = elements
  for (oldImport in oldImports) {
    if (oldImport.moduleUri?.escapedContent == uri) return oldImport.memberName

    if (oldImport.memberName == effectiveImportName) {
      effectiveImportName = appendNumericSuffix(effectiveImportName)
    }
  }

  val newImport =
    if (effectiveImportName == defaultImportName) {
      PklPsiFactory.createImport(uri, project)
    } else {
      PklPsiFactory.createAliasedImport(uri, effectiveImportName, project)
    }

  val newElementInfo = ImportInfo.create(newImport)
  var newElementAdded = false
  for (oldImport in oldImports) {
    if (newElementInfo < ImportInfo.create(oldImport)) {
      addBefore(newImport, oldImport)
      addBefore(PklPsiFactory.createToken("\n", project), oldImport)
      newElementAdded = true
      break
    }
  }

  if (!newElementAdded) {
    add(PklPsiFactory.createToken("\n", project))
    add(newImport)
    ensureFollowedByNewlines(2)
  }

  return effectiveImportName
}

val PklImportBase.isGlob
  get() = firstChildTokenType() == IMPORT_GLOB

sealed class ModuleResolutionResult {
  abstract fun computeResolvedImportType(
    base: PklBaseModule,
    bindings: TypeParameterBindings,
    preserveUnboundedVars: Boolean,
    context: PklProject?
  ): Type
}

class SimpleModuleResolutionResult(val resolved: PklModule?) : ModuleResolutionResult() {
  override fun computeResolvedImportType(
    base: PklBaseModule,
    bindings: TypeParameterBindings,
    preserveUnboundedVars: Boolean,
    context: PklProject?
  ): Type {
    return resolved.computeResolvedImportType(
      base,
      bindings,
      context,
      preserveUnboundedVars,
    )
  }
}

class GlobModuleResolutionResult(val resolved: List<PklModule>) : ModuleResolutionResult() {
  override fun computeResolvedImportType(
    base: PklBaseModule,
    bindings: TypeParameterBindings,
    preserveUnboundedVars: Boolean,
    context: PklProject?
  ): Type {
    if (resolved.isEmpty())
      return base.mappingType.withTypeArguments(base.stringType, base.moduleType)
    val allTypes =
      resolved.map {
        it.computeResolvedImportType(
          base,
          bindings,
          context,
          preserveUnboundedVars,
        ) as Type.Module
      }
    val firstType = allTypes.first()
    val unifiedType =
      allTypes.drop(1).fold<Type.Module, Type>(firstType) { acc, type ->
        val currentModule = acc as? Type.Module ?: return@fold acc
        inferCommonType(base, currentModule, type, context)
      }
    return base.mappingType.withTypeArguments(base.stringType, unifiedType)
  }

  private fun inferCommonType(
    base: PklBaseModule,
    modA: Type.Module,
    modB: Type.Module,
    context: PklProject?
  ): Type {
    return when {
      modA.isSubtypeOf(modB, base, context) -> modB
      modB.isSubtypeOf(modA, base, context) -> modA
      else -> {
        val superModA = modA.supermodule(context) ?: return base.moduleType
        val superModB = modB.supermodule(context) ?: return base.moduleType
        inferCommonType(base, superModA, superModB, context)
      }
    }
  }
}

fun PklImportBase.resolve(context: PklProject?): ModuleResolutionResult =
  if (isGlob) GlobModuleResolutionResult(moduleUri?.resolveGlob(context) ?: emptyList())
  else SimpleModuleResolutionResult(moduleUri?.resolve(context))

fun PklImportBase.resolveModules(context: PklProject?): List<PklModule> =
  resolve(context).let { result ->
    when (result) {
      is SimpleModuleResolutionResult -> result.resolved?.let(::listOf) ?: emptyList()
      else -> {
        result as GlobModuleResolutionResult
        result.resolved
      }
    }
  }

fun PklModuleName.resolve(context: PklProject?): PklModule? =
  (reference as PklModuleNameReference?)?.resolveContextual(context)

val PklModuleExtendsAmendsClause.keyword: LeafPsiElement?
  get() = firstChildTokenOfEitherType(PklElementTypes.EXTENDS, PklElementTypes.AMENDS)

val PklModuleExtendsAmendsClause?.isAmend: Boolean
  get() = if (this == null) false else keyword?.elementType == PklElementTypes.AMENDS

val PklModuleExtendsAmendsClause?.isExtend: Boolean
  get() = if (this == null) false else keyword?.elementType == PklElementTypes.EXTENDS

fun PklType.unknownToNull(): PklType? = if (this is PklUnknownType) null else this

val PklType?.hasConstraints: Boolean
  get() =
    when (this) {
      null -> false
      is PklConstrainedType -> true
      is PklTypeAlias -> body.hasConstraints
      is PklUnionType -> leftType.hasConstraints || rightType.hasConstraints
      else -> false
    }

fun PklTypeAlias.isRecursive(context: PklProject?): Boolean =
  getContextualCachedValue(context) {
    val project = project
    val psiManager = PsiManager.getInstance(project)
    CachedValueProvider.Result.create(
      isRecursive(mutableSetOf(), context),
      psiManager.modificationTracker.forLanguage(PklLanguage)
    )
  }

private fun PklTypeAlias.isRecursive(
  seen: MutableSet<PklTypeAlias>,
  context: PklProject?
): Boolean = !seen.add(this) || body.isRecursive(seen, context)

private fun PklType?.isRecursive(seen: MutableSet<PklTypeAlias>, context: PklProject?): Boolean =
  when (this) {
    is PklDeclaredType -> {
      val resolved = typeName.simpleName.resolve(context)
      resolved is PklTypeAlias && resolved.isRecursive(seen, context)
    }
    is PklNullableType -> type.isRecursive(seen, context)
    is PklDefaultType -> type.isRecursive(seen, context)
    is PklUnionType -> leftType.isRecursive(seen, context) || rightType.isRecursive(seen, context)
    is PklConstrainedType -> type.isRecursive(seen, context)
    is PklParenthesizedType -> type.isRecursive(seen, context)
    else -> false
  }

val PklNonNullAssertionExpr.operator: LeafPsiElement
  get() = firstChildToken()!!

interface TypeNameRenderer {
  fun render(name: PklTypeName, appendable: Appendable)

  fun render(type: Type.Class, appendable: Appendable)

  fun render(type: Type.Alias, appendable: Appendable)

  fun render(type: Type.Module, appendable: Appendable)
}

object DefaultTypeNameRenderer : TypeNameRenderer {
  override fun render(name: PklTypeName, appendable: Appendable) {
    appendable.append(name.simpleName.identifier.text)
  }

  override fun render(type: Type.Class, appendable: Appendable) {
    appendable.append(type.psi.name ?: "<type>")
  }

  override fun render(type: Type.Alias, appendable: Appendable) {
    appendable.append(type.psi.name ?: "<type>")
  }

  override fun render(type: Type.Module, appendable: Appendable) {
    appendable.append(type.referenceName)
  }
}

fun Appendable.renderType(
  type: PklType?,
  bindings: TypeParameterBindings,
  nameRenderer: TypeNameRenderer = DefaultTypeNameRenderer
): Appendable {
  when (type) {
    null -> append("unknown")
    is PklDeclaredType -> {
      val name = type.typeName.simpleName.identifier.text
      for ((key, value) in bindings) {
        if (key.name == name) return renderType(value)
      }
      nameRenderer.render(type.typeName, this)
      val typeArgumentList = type.typeArgumentList
      if (typeArgumentList != null && typeArgumentList.elements.any { it !is PklUnknownType }) {
        append('<')
        var first = true
        for (arg in typeArgumentList.elements) {
          if (first) first = false else append(", ")
          renderType(arg, bindings, nameRenderer)
        }
        append('>')
      }
    }
    is PklNullableType -> {
      val addParens = type is PklUnionType || type is PklFunctionType
      if (addParens) append('(')
      renderType(type.type, bindings, nameRenderer)
      if (addParens) append(')')
      append('?')
    }
    is PklConstrainedType -> renderType(type.type, bindings, nameRenderer)
    is PklParenthesizedType -> {
      append('(')
      renderType(type.type, bindings, nameRenderer)
      append(')')
    }
    is PklFunctionType -> {
      append('(')
      var first = true
      for (t in type.functionTypeParameterList.elements) {
        if (first) first = false else append(", ")
        renderType(t, bindings, nameRenderer)
      }
      append(") -> ")
      renderType(type.type, bindings, nameRenderer)
    }
    is PklUnionType -> {
      renderType(type.leftType, bindings, nameRenderer)
      append("|")
      renderType(type.rightType, bindings, nameRenderer)
    }
    is PklDefaultType -> {
      append('*')
      renderType(type.type, bindings, nameRenderer)
    }
    is PklStringLiteralType -> append(type.stringConstant.text)
    is PklNothingType -> append("nothing")
    is PklModuleType -> append("module")
    is PklUnknownType -> append("unknown")
    else -> throw AssertionError("Unknown type: ${type::class}")
  }

  return this
}

fun Appendable.renderType(
  type: Type?,
  nameRenderer: TypeNameRenderer = DefaultTypeNameRenderer
): Appendable {
  type?.render(this, nameRenderer)
  return this
}

fun Appendable.renderParameterTypeList(
  parameterList: PklParameterList?,
  bindings: TypeParameterBindings,
  nameRenderer: TypeNameRenderer = DefaultTypeNameRenderer
): Appendable {
  append('(')

  if (parameterList != null) {
    var first = true
    for (parameter in parameterList.elements) {
      if (first) first = false else append(", ")
      val type = parameter.type?.unknownToNull()
      if (type != null) {
        renderType(type, bindings, nameRenderer)
      } else {
        // render parameter name, which is more informative than `unknown` type
        append(parameter.identifier.text)
      }
    }
  }

  append(')')

  return this
}

fun Appendable.renderParameterList(
  parameterList: PklParameterList?,
  bindings: TypeParameterBindings,
  nameRenderer: TypeNameRenderer = DefaultTypeNameRenderer
): Appendable {
  append('(')

  if (parameterList != null) {
    var first = true
    for (parameter in parameterList.elements) {
      if (first) first = false else append(", ")
      renderTypedIdentifier(parameter, bindings, nameRenderer)
    }
  }

  append(')')

  return this
}

fun Appendable.renderTypedIdentifier(
  typedIdentifier: PklTypedIdentifier,
  bindings: TypeParameterBindings,
  nameRenderer: TypeNameRenderer = DefaultTypeNameRenderer
): Appendable {
  append(typedIdentifier.identifier.text)
  renderTypeAnnotation(typedIdentifier.type, bindings, nameRenderer)
  return this
}

fun Appendable.renderTypeAnnotation(
  type: Type,
  nameRenderer: TypeNameRenderer = DefaultTypeNameRenderer,
): Appendable {
  append(": ")
  type.render(this, nameRenderer)
  return this
}

fun Appendable.renderTypeAnnotation(
  type: PklType?,
  bindings: TypeParameterBindings,
  nameRenderer: TypeNameRenderer = DefaultTypeNameRenderer
): Appendable {
  append(": ")
  renderType(type, bindings, nameRenderer)
  return this
}

fun Appendable.renderModifiers(modifiers: PklModifierList?): Appendable {
  if (modifiers == null) return this

  for (modifier in modifiers.elements) {
    append(modifier.text)
    append(' ')
  }

  return this
}

fun Appendable.renderTypeParameterList(typeParameters: PklTypeParameterList?): Appendable {
  if (typeParameters == null) return this

  append('<')
  var first = true
  for (param in typeParameters.elements) {
    if (first) first = false else append(", ")
    append(param.identifier.text)
  }
  append('>')

  return this
}

fun PklStringContent.escapedText(): String? = getEscapedText()

fun PklStringConstantContent.escapedText(): String? = getEscapedText()

private fun PklElement.getEscapedText(): String? = buildString {
  eachChild { child ->
    when (child.elementType) {
      PklElementTypes.STRING_CHARS,
      TokenType.WHITE_SPACE -> append(child.text)
      PklElementTypes.CHAR_ESCAPE -> {
        val text = child.text
        when (text[text.lastIndex]) {
          'n' -> append('\n')
          'r' -> append('\r')
          't' -> append('\t')
          '\\' -> append('\\')
          '"' -> append('"')
          else -> throw AssertionError("Unknown char escape: $text")
        }
      }
      PklElementTypes.UNICODE_ESCAPE -> {
        val text = child.text
        val index = text.indexOf('{') + 1
        if (index != -1) {
          val hexString = text.substring(index, text.length - 1)
          try {
            append(Character.toChars(Integer.parseInt(hexString, 16)))
          } catch (ignored: NumberFormatException) {} catch (ignored: IllegalArgumentException) {}
        }
      }
      else ->
        // interpolated or invalid string -> bail out
        return null
    }
  }
}

// could add [AllIcons.Nodes.FinalMark] for modules and classes,
// but it's not a platform icon and doesn't seem to be widely used
fun Icon.decorate(modifierListOwner: PklModifierListOwner, flags: Int): Icon {
  var icon = this
  if (flags.and(Iconable.ICON_FLAG_VISIBILITY) != 0) {
    icon =
      if (modifierListOwner.isLocal) {
        LayeredIcon(icon, PklIcons.PRIVATE)
      } else {
        LayeredIcon(icon, PklIcons.PUBLIC)
      }
  }
  return icon
}

val PklObjectMember.nonGeneratorMember: PklObjectMember?
  get() =
    when (this) {
      is PklMemberGenerator -> objectMember?.nonGeneratorMember
      else -> this
    }

val PklProperty.isAmendsDeclaration: Boolean
  get() = objectBodyList.isNotEmpty()

fun PklTypeDefOrModule.parentTypeDef(context: PklProject?): PklTypeDefOrModule? {
  return when (this) {
    is PklClass -> superclass(context) ?: supermodule(context)
    is PklModule -> supermodule(context)
    else -> null
  }
}

fun PklTypeDefOrModule.effectiveParentProperties(
  context: PklProject?
): Map<String, PklClassProperty>? {

  return when (val def = parentTypeDef(context)) {
    is PklClass -> def.cache(context).leafProperties
    is PklModule -> def.cache(context).leafProperties
    else -> null
  }
}

val PsiElement.pklReference: PklReference?
  get() = reference as? PklReference
