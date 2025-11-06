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
package org.pkl.intellij.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.Messages
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.*
import com.intellij.util.messages.Topic
import java.nio.file.Files
import org.pkl.formatter.GrammarVersion
import org.pkl.intellij.action.PklDownloadPklCliAction

fun interface PklSettingsChangedListener {
  fun settingsChanged()
}

class PklSettingsComponent(private val project: Project) {
  companion object {
    val PKL_SETTINGS_CHANGED_TOPIC: Topic<PklSettingsChangedListener> =
      Topic.create("PklSettingsChanged", PklSettingsChangedListener::class.java)
  }

  fun createPanel(): DialogPanel {
    return panel {
      row("<html>Path to <code>pkl</code></html>") {
        val textField =
          textFieldWithBrowseButton(
              FileChooserDescriptorFactory.createSingleFileDescriptor()
                .withTitle("Executable Path")
                .withFileFilter { Files.isExecutable(it.toNioPath()) },
              project
            )
            .bindText(project.pklSettings.state::pklPath)
            .onApply {
              project.messageBus.syncPublisher(PKL_SETTINGS_CHANGED_TOPIC).settingsChanged()
            }

        val spinningLabel = JBLabel(AnimatedIcon.Default()).also { it.isVisible = false }

        val onStart = { spinningLabel.isVisible = true }

        val onEnd = { result: String?, error: Throwable? ->
          spinningLabel.isVisible = false
          if (error != null) {
            Messages.showErrorDialog(project, error.message, "Error Downloading Pkl CLI")
          } else if (result == null) {
            Messages.showErrorDialog(
              project,
              "Error downloading pkl CLI",
              "Error Downloading Pkl CLI"
            )
          } else {
            textField.component.text = result
            project.pklSettings.state.pklPath = result
          }
        }
        actionButton(PklDownloadPklCliAction(onStart, onEnd))
        cell(spinningLabel)
      }
      row("Formatter grammar version") {
          val options = listOf("<Unselected> (Latest)", "1: Pkl 0.25 - 0.29", "2: Pkl >=0.30")
          comboBox(options)
            .bindItem(
              {
                val version = project.pklSettings.state.formatterGrammarVersion
                when (version) {
                  null -> options[0] // Auto
                  GrammarVersion.V1 -> options[1]
                  GrammarVersion.V2 -> options[2]
                }
              },
              { value ->
                val version =
                  when (value) {
                    options[0] -> null // Auto
                    options[1] -> GrammarVersion.V1
                    options[2] -> GrammarVersion.V2
                    else -> null // Default to Auto
                  }
                project.pklSettings.state.formatterGrammarVersion = version
              }
            )
            .onApply {
              project.messageBus.syncPublisher(PKL_SETTINGS_CHANGED_TOPIC).settingsChanged()
            }
        }
        .rowComment("Select the Pkl version to use for code formatting")
    }
  }
}
