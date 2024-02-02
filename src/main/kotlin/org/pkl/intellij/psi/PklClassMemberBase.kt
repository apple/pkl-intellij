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
import com.intellij.navigation.ItemPresentation
import com.intellij.psi.util.parentOfTypes
import javax.swing.Icon
import org.pkl.intellij.util.unexpectedType

abstract class PklClassMemberBase(node: ASTNode) : PklModuleMemberBase(node), PklClassMember {
  override fun getPresentation(): ItemPresentation {
    return object : ItemPresentation {
      override fun getLocationString(): String? {
        return when (val parent = parentOfTypes(PklModule::class, PklClass::class)) {
          null -> null
          is PklModule -> parent.displayName
          is PklClass -> parent.displayName
          else -> unexpectedType(parent)
        }
      }

      override fun getIcon(unused: Boolean): Icon? = getIcon(0)

      override fun getPresentableText(): String = buildString {
        when (val member = this@PklClassMemberBase) {
          is PklClassMethodBase -> {
            append(member.name ?: "<method>")
            renderParameterTypeList(member.parameterList, mapOf())
            renderTypeAnnotation(member.returnType, mapOf())
          }
          is PklClassPropertyBase -> {
            append(member.name)
            renderTypeAnnotation(member.type, mapOf())
          }
          else -> unexpectedType(member)
        }
      }
    }
  }
}
