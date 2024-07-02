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
import org.pkl.intellij.psi.PklTypeName
import org.pkl.intellij.psi.enclosingModule
import org.pkl.intellij.psi.resolve

class PklTypeNameAnnotator : PklAnnotator() {
  override fun doAnnotate(element: PsiElement, holder: AnnotationHolder) {
    when (element) {
      is PklTypeName -> {
        val moduleName = element.moduleName
        val context = element.enclosingModule?.pklProject
        if (moduleName != null) {
          val resolvedModule = moduleName.resolve(context)
          if (resolvedModule == null) {
            holder
              .newAnnotation(
                HighlightSeverity.ERROR,
                "Unresolved reference: ${moduleName.identifier.text}"
              )
              .range(moduleName)
              .create()
            return
          }
        }
        val typeName = element.simpleName
        val resolvedType = typeName.resolve(context)
        if (resolvedType == null) {
          holder
            .newAnnotation(
              HighlightSeverity.ERROR,
              "Unresolved reference: ${typeName.identifier.text}"
            )
            .range(typeName)
            .create()
          return
        }
      }
    }
  }
}
