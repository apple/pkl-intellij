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
import org.pkl.intellij.PklVersion
import org.pkl.intellij.psi.*
import org.pkl.intellij.util.Feature
import org.pkl.intellij.util.escapeXml

class PklUnsupportedFeatureAnnotator : PklAnnotator() {

  private fun getToolTip(feature: Feature, detectedVersion: PklVersion): String {
    val message = feature.message ?: "Unsupported feature: ${feature.featureName}."
    // language=html
    return """
      $message
      <table>
        <tr><td style="text-align: right">Required Pkl version:</td><td><code>${feature.requiredVersion.toString().escapeXml()}</code></td></tr>
        <tr><td style="text-align: right">Detected Pkl version:</td><td><code>${detectedVersion.toString().escapeXml()}</code></td></tr>
      </table>
    """
      .trimIndent()
  }

  override fun doAnnotate(element: PsiElement, holder: AnnotationHolder) {
    val containingModule = element.containingFile as? PklModule ?: return
    val feature: Feature = Feature.features.find { it.predicate(element) } ?: return
    if (feature.isSupported(containingModule)) return
    val tooltip = getToolTip(feature, containingModule.effectivePklVersion)
    val message = feature.message ?: "Unsupported feature: ${feature.featureName}"
    createAnnotation(
      HighlightSeverity.WARNING,
      element.textRange,
      message +
        " Required Pkl version: ${feature.requiredVersion}. Detected Pkl version: ${containingModule.effectivePklVersion}.",
      tooltip,
      holder,
      PklProblemGroups.unsupportedFeature,
      element
    )
  }
}
