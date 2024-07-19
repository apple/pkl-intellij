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
import com.intellij.psi.util.parentOfType
import com.intellij.psi.util.parentOfTypes
import javax.swing.Icon
import org.pkl.intellij.PklIcons
import org.pkl.intellij.packages.dto.PklProject
import org.pkl.intellij.type.Type
import org.pkl.intellij.type.TypeParameterBindings
import org.pkl.intellij.type.computeResolvedImportType

abstract class PklClassPropertyBase(node: ASTNode) : PklClassMemberBase(node), PklClassProperty {
  override fun getName(): String = nameIdentifier.text

  override val anchor: PsiElement
    get() = nameIdentifier

  override fun getNameIdentifier(): PsiElement = propertyName.identifier

  override fun getIcon(flags: Int): Icon = PklIcons.PROPERTY.decorate(this, flags)

  override fun getLookupElementType(
    base: PklBaseModule,
    bindings: TypeParameterBindings,
    context: PklProject?
  ): Type = computeResolvedImportType(base, bindings, context)

  fun setExpr(expr: PklExpr): PklClassPropertyBase {
    assert(this.expr == null)
    node.addLeaf(PklElementTypes.ASSIGN, " =", null)
    node.addChild(expr.node)
    return this
  }

  override fun isDefinition(context: PklProject?): Boolean {
    return when {
      type != null -> true
      isLocal -> true
      isHidden -> true
      else -> {
        when (val owner = parentOfTypes(PklModule::class, PklClass::class)) {
          is PklModule -> {
            if (owner.extendsAmendsClause.isAmend) false
            else
              when (val supermodule = owner.supermodule(context)) {
                null ->
                  !project.pklBaseModule.moduleType.psi.cache(context).properties.containsKey(name)
                else -> !supermodule.cache(context).properties.containsKey(name)
              }
          }
          is PklClass -> {
            owner.superclass(context)?.let { superclass ->
              return !superclass.cache(context).properties.containsKey(name)
            }
            owner.supermodule(context)?.let { supermodule ->
              return !supermodule.cache(context).properties.containsKey(name)
            }
            true
          }
          else -> false
        }
      }
    }
  }

  override fun effectiveDocComment(context: PklProject?): PklDocComment? {
    docComment?.let {
      return it
    }
    val myName = name
    val clazz = parentOfType<PklClass>() ?: return null
    clazz.eachSuperclassOrModule(context) { typeDef ->
      when (typeDef) {
        is PklClass ->
          typeDef.cache(context).properties[myName]?.docComment?.let {
            return it
          }
        is PklModule ->
          typeDef.cache(context).properties[myName]?.docComment?.let {
            return it
          }
      }
    }
    return null
  }
}
