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

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiElement
import org.pkl.intellij.packages.dto.PklProject
import org.pkl.intellij.psi.*
import org.pkl.intellij.type.Type
import org.pkl.intellij.type.computeThisType
import org.pkl.intellij.util.currentModule

class PklMissingPropertiesAnnotator : PklAnnotator() {

  override fun doAnnotate(element: PsiElement, holder: AnnotationHolder) {
    if (element is PklNewExpr) {
      val base = holder.currentModule?.project?.pklBaseModule ?: return
      val context = element.enclosingModule?.pklProject
      val bodyType = element.objectBody?.computeThisType(base, mapOf(), context)
      val elementTypeName: String
      val requiredProperties =
        when (bodyType) {
          is Type.Class -> {
            val className = bodyType.psi.name ?: "<class>"
            elementTypeName =
              bodyType.psi.enclosingModule?.displayName?.let { "${it}.$className" } ?: className
            getAllRequiredProperties(bodyType.psi, base, context)
          }
          is Type.Module -> {
            elementTypeName = bodyType.psi.displayName
            getAllRequiredProperties(bodyType.psi, base, context)
          }
          else -> return
        }
      val definedPropertyKeys =
        element.objectBody?.properties?.map { it.propertyName.identifier.text }?.toSet()
          ?: emptySet()
      val missingProperties = requiredProperties.minus(definedPropertyKeys)
      if (missingProperties.isEmpty()) return
      val missingListMessage = missingProperties.keys.joinToString(", ")
      val missingListTooltip =
        missingProperties
          .map { (k, v) ->
            "<tr><td><strong><code>$k</code></strong></td><td><code>$v</code></td></tr>"
          }
          .joinToString("")
      createAnnotation(
        HighlightSeverity.ERROR,
        element.node.firstChildNode.textRange,
        "Missing required properties for ${elementTypeName}: $missingListMessage",
        "Missing required properties for <strong><code>${elementTypeName}</code></strong><table>$missingListTooltip</table>",
        holder,
        PklProblemGroups.missingRequiredValues,
        element,
      )
    }
  }
}

private fun getAllRequiredProperties(
  pklClass: PklClass,
  base: PklBaseModule,
  context: PklProject?
): Map<String, String> {
  val results = mutableMapOf<String, String>()
  insertRequiredProperties(pklClass.properties, results, base, context)
  var parent = pklClass.superclass(context)
  while (parent != null) {
    insertRequiredProperties(parent.properties, results, base, context)
    parent = parent.superclass(context)
  }
  return results
}

private fun getAllRequiredProperties(
  pklModule: PklModule,
  base: PklBaseModule,
  context: PklProject?
): Map<String, String> {
  val results = mutableMapOf<String, String>()
  insertRequiredProperties(pklModule.properties, results, base, context)
  var parent = pklModule.supermodule(context)
  while (parent != null) {
    insertRequiredProperties(parent.properties, results, base, context)
    parent = parent.supermodule(context)
  }
  return results
}

private fun insertRequiredProperties(
  props: Sequence<PklClassProperty>,
  results: MutableMap<String, String>,
  base: PklBaseModule,
  context: PklProject?
) {
  props
    .filter { it.expr == null }
    .forEach {
      val propertyType = it.getLookupElementType(base, mapOf(), context)
      if (!propertyType.isNullable(base)) {
        results[it.propertyName.identifier.text] = propertyType.render()
      }
    }
}
