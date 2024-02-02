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
import org.pkl.intellij.psi.*

class PklDuplicatePropertyAnnotator : PklAnnotator() {
  override fun doAnnotate(element: PsiElement, holder: AnnotationHolder) {
    when (element) {
      is PklProperty -> {
        val propertyName = element.name
        var propertyContainer = element.parent
        var isGenerated = false
        while (propertyContainer is PklMemberGenerator) {
          propertyContainer = propertyContainer.parent
          isGenerated = true
        }
        propertyContainer?.eachChild { child ->
          when (child) {
            is PklObjectMember -> {
              // don't flag two generated members with same name because they might not overlap
              // TODO: support non-legacy for/when-generators w/ object body
              val memberToCompare = if (isGenerated) child else child.nonGeneratorMember
              if (
                memberToCompare !== element &&
                  memberToCompare is PklProperty &&
                  memberToCompare.name == propertyName
              ) {
                holder
                  .newAnnotation(HighlightSeverity.ERROR, "Duplicate property definition")
                  .range(element.nameIdentifier.textRange)
                  .create()
                return
              }
            }
            is PklClassProperty -> {
              if (child !== element && child.name == propertyName) {
                holder
                  .newAnnotation(HighlightSeverity.ERROR, "Duplicate property definition")
                  .range(element.nameIdentifier.textRange)
                  .create()
                return
              }
            }
          }
        }
      }
    }
  }
}
