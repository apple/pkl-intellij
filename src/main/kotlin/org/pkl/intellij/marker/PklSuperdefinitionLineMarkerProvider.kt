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
package org.pkl.intellij.marker

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.psi.PsiElement
import com.intellij.psi.util.parentOfTypes
import org.pkl.intellij.PklIcons
import org.pkl.intellij.psi.*

class PklSuperdefinitionLineMarkerProvider : RelatedItemLineMarkerProvider() {
  override fun collectNavigationMarkers(
    element: PsiElement,
    result: MutableCollection<in RelatedItemLineMarkerInfo<*>>
  ) {

    // line markers should be added for leaf elements only
    // (see docs for LineMarkerProvider.getLineMarkerInfo)
    if (element.elementType != PklElementTypes.IDENTIFIER) return

    when (val parent = element.parent) {
      PklElementTypes.QUALIFIED_IDENTIFIER ->
        when (element.parent) {
          is PklModule -> {
            // TODO: use index to point to modules amending/extending/importing this module
          }
        }
      is PklClass -> {
        // TODO: use index to point to all subclasses
      }
      is PklClassMethod -> {
        // TODO: use index to point to methods implementing or overriding this method
        when (val owner = parent.owner) {
          is PklClass -> {
            owner.superclass?.let { superclass ->
              val supermethod = superclass.cache.methods[element.text] ?: return
              markSupermethod(element, supermethod, superclass, result)
              return
            }
            owner.supermodule?.let { supermodule ->
              val supermethod = supermodule.cache.methods[element.text] ?: return
              markSupermethod(element, supermethod, supermodule, result)
            }
          }
          is PklModule -> {
            // only show markers for module classes
            if (owner.extendsAmendsClause.isAmend) return

            val supermodule = owner.supermodule ?: return
            val supermethod = supermodule.cache.methods[element.text] ?: return
            markSupermethod(element, supermethod, supermodule, result)
          }
          else -> return
        }
      }
      is PklPropertyName -> {
        when (
          val enclosingDef =
            parent.parentOfTypes(
              PklClass::class,
              PklModule::class,
              // stop class
              PklObjectProperty::class
            )
        ) {
          is PklClass -> {
            enclosingDef.superclass?.let { superclass ->
              val superproperty = superclass.cache.properties[element.text] ?: return
              markSuperproperty(element, superproperty, superclass, result)
            }
            enclosingDef.supermodule?.let { supermodule ->
              val superproperty = supermodule.cache.properties[element.text] ?: return
              markSuperproperty(element, superproperty, supermodule, result)
            }
          }
          is PklModule -> {
            // only show markers for module classes
            if (enclosingDef.extendsAmendsClause.isAmend) return

            val supermodule = enclosingDef.supermodule ?: return
            val superproperty =
              supermodule.cache.typeDefsAndProperties[element.text] as? PklClassProperty? ?: return
            markSuperproperty(element, superproperty, supermodule, result)
          }
          else -> return
        }
      }
    }
  }

  private fun markSupermethod(
    nameIdentifier: PsiElement,
    supermethod: PklClassMethod,
    enclosingDef: PklTypeDefOrModule,
    result: MutableCollection<in RelatedItemLineMarkerInfo<PsiElement>>
  ) {

    val isAbstract = supermethod.isAbstract
    val icon =
      if (isAbstract) PklIcons.IMPLEMENTING_METHOD_MARKER else PklIcons.OVERRIDING_METHOD_MARKER
    val action = if (isAbstract) "Implements" else "Overrides"
    val builder =
      NavigationGutterIconBuilder.create(icon)
        .setTarget(supermethod.nameIdentifier)
        .setTooltipText("$action method in <b>${enclosingDef.name ?: "&lt;type&gt;"}</b>")
    result.add(builder.createLineMarkerInfo(nameIdentifier))
  }

  private fun markSuperproperty(
    nameIdentifier: PsiElement,
    superproperty: PklClassProperty,
    enclosingDef: PklTypeDefOrModule,
    result: MutableCollection<in RelatedItemLineMarkerInfo<PsiElement>>
  ) {

    val isAbstract = superproperty.isAbstract
    val icon =
      if (isAbstract) PklIcons.IMPLEMENTING_METHOD_MARKER else PklIcons.OVERRIDING_METHOD_MARKER
    val action = if (isAbstract) "Implements" else "Overrides"
    val builder =
      NavigationGutterIconBuilder.create(icon)
        .setTarget(superproperty.nameIdentifier)
        .setTooltipText("$action property in <b>${enclosingDef.name ?: "&lt;type&gt;"}</b>")
    result.add(builder.createLineMarkerInfo(nameIdentifier))
  }
}
