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
import com.intellij.psi.PsiElement
import com.intellij.psi.util.CachedValueProvider
import javax.swing.Icon
import org.pkl.intellij.PklIcons
import org.pkl.intellij.packages.dto.PklProject
import org.pkl.intellij.resolve.ResolveVisitor
import org.pkl.intellij.resolve.visitIfNotNull
import org.pkl.intellij.type.Type
import org.pkl.intellij.type.TypeParameterBindings
import org.pkl.intellij.util.getContextualCachedValue

abstract class PklClassBase(node: ASTNode) : PklTypeDefBase(node), PklClass {
  override val keyword: PsiElement
    get() = firstChildOfType(PklElementTypes.CLASS_KEYWORD)!!

  override fun getIcon(flags: Int): Icon {
    val icon =
      when {
        isAnnotationType -> PklIcons.ANNOTATION_TYPE
        isAbstract -> PklIcons.ABSTRACT_CLASS
        else -> PklIcons.CLASS
      }
    return icon.decorate(this, flags)
  }

  private val isAnnotationType: Boolean
    get() = isStrictSubclassOf(project.pklBaseModule.annotationType.psi, null)

  override fun getLookupElementType(
    base: PklBaseModule,
    bindings: TypeParameterBindings,
    context: PklProject?
  ): Type = base.classType.withTypeArguments(Type.Class(this))

  override val members: Sequence<PklClassMember>
    get() = body?.members ?: emptySequence()

  override val properties: Sequence<PklClassProperty>
    get() = body?.properties ?: emptySequence()

  override val methods: Sequence<PklClassMethod>
    get() = body?.methods ?: emptySequence()
}

fun PklClass.cache(context: PklProject?): ClassMemberCache =
  getContextualCachedValue(context) {
    val cache = ClassMemberCache.create(this, context)
    CachedValueProvider.Result.create(cache, cache.dependencies)
  }

/** Caches non-local, i.e., externally accessible, members of a class. */
class ClassMemberCache(
  val methods: Map<String, PklClassMethod>,
  /** Property definitions */
  val properties: Map<String, PklClassProperty>,
  /**
   * The leaf-most properties, may or may not have a type annotation.
   *
   * A child that overrides a parent without a type annotation is a "leaf property".
   */
  val leafProperties: Map<String, PklClassProperty>,

  // cache invalidation is hard (and I don't know how exactly it works in intellij).
  // let's play it safe and make a class cache depend on the class' enclosing module and its
  // superclass cache's dependencies.
  // this can be revisited if performance issues arise.
  val dependencies: List<Any>
) {

  fun visitPropertiesOrMethods(
    isProperty: Boolean,
    bindings: TypeParameterBindings,
    visitor: ResolveVisitor<*>,
    context: PklProject?
  ): Boolean {
    return if (isProperty) doVisit(properties, bindings, visitor, context)
    else doVisit(methods, bindings, visitor, context)
  }

  private fun doVisit(
    members: Map<String, PklElement>,
    bindings: TypeParameterBindings,
    visitor: ResolveVisitor<*>,
    context: PklProject?
  ): Boolean {

    val exactName = visitor.exactName
    if (exactName != null) {
      return visitor.visitIfNotNull(exactName, members[exactName], bindings, context)
    }

    for ((name, member) in members) {
      if (!visitor.visit(name, member, bindings, context)) return false
    }

    return true
  }

  companion object {
    fun create(clazz: PklClass, context: PklProject?): ClassMemberCache {
      val properties = mutableMapOf<String, PklClassProperty>()
      val leafProperties = mutableMapOf<String, PklClassProperty>()
      val methods = mutableMapOf<String, PklClassMethod>()
      val dependencies = mutableListOf<Any>(clazz)

      clazz.superclass(context)?.let { superclass ->
        val superclassCache = superclass.cache(context)
        properties.putAll(superclassCache.properties)
        leafProperties.putAll(superclassCache.leafProperties)
        methods.putAll(superclassCache.methods)
        dependencies.addAll(superclassCache.dependencies)
      }

      clazz.supermodule(context)?.let { supermodule ->
        val supermoduleCache = supermodule.cache(context)
        properties.putAll(supermoduleCache.properties)
        leafProperties.putAll(supermoduleCache.leafProperties)
        methods.putAll(supermoduleCache.methods)
        dependencies.addAll(supermoduleCache.dependencies)
      }

      clazz.enclosingModule?.let { dependencies.add(it) }

      for (member in clazz.members) {
        when (member) {
          is PklClassMethod -> {
            if (!member.isLocal) {
              member.name?.let { methods[it] = member }
            }
          }
          is PklClassProperty -> {
            if (!member.isLocal) {
              val name = member.name
              // record [member] if it (re)defines a property;
              // don't record [member] if it amends/overrides a property defined in a superclass.
              if (member.type != null || properties[name] == null) {
                properties[name] = member
              }
              leafProperties[name] = member
            }
          }
        }
      }

      return ClassMemberCache(methods, properties, leafProperties, dependencies)
    }
  }
}
