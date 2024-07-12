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

import org.pkl.intellij.PklVersion
import org.pkl.intellij.packages.dto.PklProject
import org.pkl.intellij.resolve.ResolveVisitor
import org.pkl.intellij.resolve.visitIfNotNull

/** Caches non-local, i.e., externally accessible, members of a module. */
class ModuleMemberCache
private constructor(
  val module: PklModule,
  val types: Map<String, PklTypeDef>,
  val methods: Map<String, PklClassMethod>,
  /** Property definitions */
  val properties: Map<String, PklClassProperty>,
  /**
   * The leaf-most module property.
   *
   * A child that overrides a parent without a type annotation is a "leaf property".
   */
  val leafProperties: Map<String, PklClassProperty>,
  val typeDefsAndProperties: Map<String, PklTypeDefOrProperty>,
  val dependencies: List<Any>
) {

  val minPklVersion: PklVersion? by lazy { module.minPklVersion }

  fun visitTypes(visitor: ResolveVisitor<*>, context: PklProject?): Boolean {
    return doVisit(types, visitor, context)
  }

  fun visitMethods(visitor: ResolveVisitor<*>, context: PklProject?): Boolean {
    return doVisit(methods, visitor, context)
  }

  fun visitProperties(visitor: ResolveVisitor<*>, context: PklProject?): Boolean {
    return doVisit(properties, visitor, context)
  }

  fun visitPropertiesOrMethods(
    isProperty: Boolean,
    visitor: ResolveVisitor<*>,
    context: PklProject?
  ): Boolean {
    return if (isProperty) doVisit(properties, visitor, context)
    else doVisit(methods, visitor, context)
  }

  fun visitTypeDefsAndProperties(visitor: ResolveVisitor<*>, context: PklProject?): Boolean {
    return doVisit(typeDefsAndProperties, visitor, context)
  }

  fun visitTypeDefsAndPropertiesOrMethods(
    isProperty: Boolean,
    visitor: ResolveVisitor<*>,
    context: PklProject?,
  ): Boolean {
    return if (isProperty) doVisit(typeDefsAndProperties, visitor, context)
    else doVisit(methods, visitor, context)
  }

  private fun doVisit(
    members: Map<String, PklElement>,
    visitor: ResolveVisitor<*>,
    context: PklProject?
  ): Boolean {
    val exactName = visitor.exactName
    if (exactName != null) {
      return (visitor.visitIfNotNull(exactName, members[exactName], mapOf(), context))
    }

    for ((name, member) in members) {
      if (!visitor.visit(name, member, mapOf(), context)) return false
    }

    return true
  }

  companion object {
    fun create(module: PklModule, context: PklProject?): ModuleMemberCache {
      val supercache = module.supermodule(context)?.cache(context)

      if (module.extendsAmendsClause.isAmend) {
        return when (supercache) {
          null -> {
            // has unresolvable amends clause ->
            // has same cached members as pkl.base#Module (but additional dependency)
            val pklBaseModuleClassCache = module.project.pklBaseModule.moduleType.psi.cache(context)
            ModuleMemberCache(
              module,
              mapOf(),
              pklBaseModuleClassCache.methods,
              pklBaseModuleClassCache.properties,
              pklBaseModuleClassCache.properties,
              pklBaseModuleClassCache.leafProperties,
              // try to be clever and depend on amends clause
              // instead of entire module (which can't define non-local members)
              pklBaseModuleClassCache.dependencies + module.extendsAmendsClause!!
            )
          }
          // has resolvable amends clause ->
          // has same cached members as supermodule (but additional dependency)
          else ->
            ModuleMemberCache(
              module,
              supercache.types,
              supercache.methods,
              supercache.properties,
              supercache.leafProperties,
              supercache.typeDefsAndProperties,
              // try to be clever and depend on amends clause
              // instead of entire module (which can't define non-local members)
              supercache.dependencies + module.extendsAmendsClause!!
            )
        }
      }

      val types = mutableMapOf<String, PklTypeDef>()
      val methods = mutableMapOf<String, PklClassMethod>()
      val properties = mutableMapOf<String, PklClassProperty>()
      val leafProperties = mutableMapOf<String, PklClassProperty>()
      val typesAndProperties = mutableMapOf<String, PklTypeDefOrProperty>()
      val dependencies = mutableListOf<Any>(module)

      if (supercache != null) {
        // has resolvable extends clause
        types.putAll(supercache.types)
        methods.putAll(supercache.methods)
        properties.putAll(supercache.properties)
        leafProperties.putAll(supercache.leafProperties)
        typesAndProperties.putAll(supercache.typeDefsAndProperties)
        dependencies.addAll(supercache.dependencies)
      } else if (!module.isPklBaseModule) {
        // has no amends/extends clause or unresolvable extends clause ->
        // extends class pkl.base#Module
        val pklBaseModuleClassCache = module.project.pklBaseModule.moduleType.psi.cache(null)
        methods.putAll(pklBaseModuleClassCache.methods)
        properties.putAll(pklBaseModuleClassCache.properties)
        leafProperties.putAll(pklBaseModuleClassCache.leafProperties)
        typesAndProperties.putAll(pklBaseModuleClassCache.properties)
        dependencies.addAll(pklBaseModuleClassCache.dependencies)
      }

      for (member in module.members) {
        if (member.isLocal) continue

        when (member) {
          is PklClass -> {
            val name = member.name
            if (name != null) {
              types[name] = member
              typesAndProperties[name] = member
            }
          }
          is PklTypeAlias -> {
            val name = member.name
            if (name != null) {
              types[name] = member
              typesAndProperties[name] = member
            }
          }
          is PklClassMethod -> {
            val name = member.name
            if (name != null) {
              methods[name] = member
            }
          }
          is PklClassProperty -> {
            val name = member.name
            // record [member] if it (re)defines a property;
            // don't record [member] if it amends/overrides a property defined in a supermodule.
            if (member.type != null || properties[name] == null) {
              properties[name] = member
              typesAndProperties[name] = member
            }
            leafProperties[name] = member
          }
        }
      }

      return ModuleMemberCache(
        module,
        types,
        methods,
        properties,
        leafProperties,
        typesAndProperties,
        dependencies
      )
    }
  }
}
