/**
 * Copyright © 2024-2025 Apple Inc. and the Pkl project authors. All rights reserved.
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
package org.pkl.intellij.formatter

import com.intellij.formatting.service.AsyncDocumentFormattingService
import com.intellij.formatting.service.AsyncFormattingRequest
import com.intellij.formatting.service.FormattingService
import com.intellij.psi.PsiFile
import org.pkl.formatter.Formatter
import org.pkl.formatter.GrammarVersion
import org.pkl.intellij.PklFileType
import org.pkl.intellij.settings.pklSettings

/** Asynchronous formatting service that delegates to the pkl-formatter library. */
class PklFormattingService : AsyncDocumentFormattingService() {
  override fun getFeatures(): Set<FormattingService.Feature> = emptySet()

  override fun canFormat(file: PsiFile): Boolean = file.fileType is PklFileType

  override fun createFormattingTask(request: AsyncFormattingRequest): FormattingTask {
    val file = request.context.containingFile
    val project = file.project

    // Get compatibility version from settings
    val grammarVersion =
      when (project.pklSettings.state.formatterCompatibilityVersion) {
        1 -> GrammarVersion.V1
        else -> GrammarVersion.V2 // null (auto) or 2 defaults to V2
      }

    return object : FormattingTask {
      override fun run() {
        try {
          val text = request.documentText
          val formattedText = Formatter().format(text, grammarVersion)
          request.onTextReady(formattedText)
        } catch (e: Exception) {
          request.onError("Formatting error", e.message ?: "Unknown error")
        }
      }

      override fun cancel(): Boolean = true

      override fun isRunUnderProgress(): Boolean = true
    }
  }

  override fun getNotificationGroupId(): String = "Pkl"

  override fun getName(): String = "Pkl Formatter"
}
