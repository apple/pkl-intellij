/**
 * Copyright © 2026 Apple Inc. and the Pkl project authors. All rights reserved.
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
package org.pkl.intellij.settings

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.execution.ParametersListUtil
import javax.swing.JComponent
import kotlin.collections.ifEmpty
import org.pkl.intellij.settings.PklSettingsComponent.Companion.DEFAULT_WORKING_DIR
import org.pkl.messaging.externalreader.ExternalReader

data class ReaderEntry(val scheme: String, val reader: ExternalReader)

class PklAddReaderDialog(project: Project, initial: ReaderEntry?) : DialogWrapper(project) {
  companion object {
    private val schemeRegex = Regex("[A-Za-z][A-Za-z0-9+.-]*")
  }

  private val schemeField = JBTextField(initial?.scheme ?: "")
  private val executableField = JBTextField(initial?.reader?.executable() ?: "")
  private val argumentsField =
    JBTextField(ParametersListUtil.join(initial?.reader?.arguments() ?: emptyList()))
  private val workingDirField =
    JBTextField(if (initial == null) DEFAULT_WORKING_DIR else initial.reader.workingDir() ?: "")

  init {
    title = if (initial == null) "Add External Reader" else "Edit External Reader"
    init()
    setSize(400, 300)
  }

  val entry: ReaderEntry
    get() =
      ReaderEntry(
        schemeField.text.trim(),
        ExternalReader(
          executableField.text.trim(),
          ParametersListUtil.parse(argumentsField.text).ifEmpty { null },
          workingDirField.text.trim().ifEmpty { null },
        ),
      )

  override fun createCenterPanel(): JComponent = panel {
    row("Scheme:") {
      cell(schemeField)
        .align(AlignX.FILL)
        .comment(
          "The URI scheme handled by this reader, without a trailing colon (e.g. <code>myscheme</code>)."
        )
    }
    row("Executable:") {
      cell(executableField)
        .align(AlignX.FILL)
        .comment("The command used to start the external reader process.")
    }
    row("Arguments:") {
      cell(argumentsField)
        .align(AlignX.FILL)
        .comment(
          "Command-line arguments passed to the executable. Quote arguments containing spaces."
        )
    }
    row("Working directory:") {
      cell(workingDirField)
        .align(AlignX.FILL)
        .comment(
          "The directory the process runs in. <code>$DEFAULT_WORKING_DIR</code> refers to the project root."
        )
    }
  }

  override fun getPreferredFocusedComponent(): JComponent = schemeField

  override fun doValidate(): ValidationInfo? =
    when {
      schemeField.text.isBlank() -> ValidationInfo("Scheme is required", schemeField)
      schemeField.text.trim().endsWith(":") ->
        ValidationInfo("Scheme must not end with \":\"", schemeField)
      !schemeRegex.matches(schemeField.text.trim()) ->
        ValidationInfo(
          "Scheme must start with a letter, followed by letters, digits, \"+\", \"-\" or \".\"",
          schemeField,
        )
      executableField.text.isBlank() -> ValidationInfo("Executable is required", executableField)
      else -> null
    }
}
