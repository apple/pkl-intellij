/**
 * Copyright © 2024-2026 Apple Inc. and the Pkl project authors. All rights reserved.
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

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.Messages
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.CollectionListModel
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.dsl.builder.*
import com.intellij.ui.table.TableView
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.execution.ParametersListUtil
import com.intellij.util.messages.Topic
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import java.awt.event.MouseEvent
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import javax.swing.ToolTipManager
import org.pkl.formatter.GrammarVersion
import org.pkl.intellij.action.PklDownloadPklCliAction
import org.pkl.intellij.externalreader.ReaderKind
import org.pkl.intellij.externalreader.pklExternalReaderService
import org.pkl.intellij.packages.localFs
import org.pkl.messaging.externalreader.ExternalReader

fun interface PklSettingsChangedListener {
  fun settingsChanged()
}

class PklSettingsComponent(private val project: Project) {
  companion object {
    const val DEFAULT_WORKING_DIR = $$"$PROJECT_DIR$"

    val PKL_SETTINGS_CHANGED_TOPIC: Topic<PklSettingsChangedListener> =
      Topic.create("PklSettingsChanged", PklSettingsChangedListener::class.java)
  }

  private val excludedPathsTableModel = createExcludedPathsTableModel()

  private fun createExcludedPathsTableModel(): ListTableModel<String> {
    return ListTableModel<String>(
      arrayOf(
        object : ColumnInfo<String, String>("Path") {
          override fun valueOf(item: String): String = item

          override fun isCellEditable(item: String): Boolean = true

          override fun setValue(item: String, value: String) {
            val items = excludedPathsTableModel.items
            val index = items.indexOf(item)
            if (index >= 0 && value.isNotBlank()) {
              items[index] = value.trim()
            }
          }
        }
      ),
      mutableListOf(),
    )
  }

  private fun Row.readersList(
    kind: ReaderKind,
    getReaders: () -> Map<String, ExternalReader>,
    setReaders: (Map<String, ExternalReader>) -> Unit,
  ) {
    val model = CollectionListModel<ReaderEntry>()
    // entries with a problem (found by starting the reader), and what the problem is
    val problems: MutableMap<ReaderEntry, String> = ConcurrentHashMap()
    val warningIcon = AllIcons.General.Warning
    val list =
      object : JBList<ReaderEntry>(model) {
        // Shows the problem when hovering over the icon of an entry that has one.
        override fun getToolTipText(event: MouseEvent): String? {
          val index = locationToIndex(event.point)
          if (index < 0) return null
          val bounds = getCellBounds(index, index) ?: return null
          if (!bounds.contains(event.point)) return null
          val isOverIcon = event.x < bounds.x + warningIcon.iconWidth + JBUI.scale(8)
          return if (isOverIcon) problems[model.getElementAt(index)] else null
        }
      }
    ToolTipManager.sharedInstance().registerComponent(list)
    list.cellRenderer = SimpleListCellRenderer.create { label, entry, _ ->
      val args = ParametersListUtil.join(entry.reader.arguments() ?: emptyList())
      label.text = "${entry.scheme}: ${entry.reader.executable()} $args".trim()
      label.icon = if (entry in problems) warningIcon else null
    }

    // Starts the reader to find out if it works and supports the scheme; this takes a moment, so it
    // happens in the background after the entry was added or changed.
    fun checkEntry(entry: ReaderEntry) {
      problems.remove(entry)
      AppExecutorUtil.getAppExecutorService().execute {
        val problem =
          project.pklExternalReaderService
            .checkReader(entry.reader, entry.scheme, kind)
            .problem(entry.scheme)
        if (problem != null) {
          ApplicationManager.getApplication()
            .invokeLater(
              {
                problems[entry] = problem
                list.repaint()
              },
              ModalityState.stateForComponent(list),
            )
        }
      }
    }

    fun currentReaders(): Map<String, ExternalReader> =
      model.items.associate { it.scheme to it.reader }

    val decorator =
      ToolbarDecorator.createDecorator(list)
        .setAddAction {
          val dialog = PklAddReaderDialog(project, null)
          if (dialog.showAndGet()) {
            model.add(dialog.entry)
            checkEntry(dialog.entry)
          }
        }
        .setEditAction {
          val index = list.selectedIndex
          if (index >= 0) {
            val dialog = PklAddReaderDialog(project, model.getElementAt(index))
            if (dialog.showAndGet()) {
              model.setElementAt(dialog.entry, index)
              checkEntry(dialog.entry)
            }
          }
        }
        .setRemoveAction { list.selectedIndices.sortedDescending().forEach { model.remove(it) } }
        .disableUpDownActions()

    cell(decorator.createPanel())
      .align(AlignX.FILL)
      .onReset {
        model.replaceAll(getReaders().map { (scheme, reader) -> ReaderEntry(scheme, reader) })
      }
      .onApply {
        setReaders(currentReaders())
        project.messageBus.syncPublisher(PKL_SETTINGS_CHANGED_TOPIC).settingsChanged()
      }
      .onIsModified { currentReaders() != getReaders() }
  }

  fun createPanel(): DialogPanel {
    return panel {
      row("<html>Path to <code>pkl</code></html>") {
        val textField =
          textFieldWithBrowseButton(
              FileChooserDescriptorFactory.createSingleFileDescriptor()
                .withTitle("Executable Path")
                .withFileFilter { Files.isExecutable(it.toNioPath()) },
              project,
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
              "Error Downloading Pkl CLI",
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
                  null -> options[0] // Unselected
                  GrammarVersion.V1 -> options[1]
                  GrammarVersion.V2 -> options[2]
                }
              },
              { value ->
                val version =
                  when (value) {
                    options[0] -> null // Unselected
                    options[1] -> GrammarVersion.V1
                    options[2] -> GrammarVersion.V2
                    else -> null // Default to unselected
                  }
                project.pklSettings.state.formatterGrammarVersion = version
              },
            )
            .onApply {
              project.messageBus.syncPublisher(PKL_SETTINGS_CHANGED_TOPIC).settingsChanged()
            }
        }
        .rowComment("The grammar version to use when formatting Pkl code.")

      row("Excluded project paths") {
          val tableView = TableView(excludedPathsTableModel)
          val decorator =
            ToolbarDecorator.createDecorator(tableView)
              .setAddAction {
                val descriptor =
                  FileChooserDescriptorFactory.createSingleFolderDescriptor()
                    .withTitle("Select Directory to Exclude")
                    .withDescription("Choose a directory to exclude from Pkl project sync")
                    .withFileFilter { it.isDirectory }

                val projectRoot = project.basePath?.let { localFs.findFileByPath(it) }
                val fileChooser = FileChooser.chooseFile(descriptor, project, projectRoot)

                fileChooser?.let { selectedFile ->
                  val path = selectedFile.path
                  val basePath = project.basePath

                  if (basePath == null || !path.startsWith(basePath)) {
                    Messages.showErrorDialog(
                      project,
                      "Selected path must be within the project root.",
                      "Invalid Path",
                    )
                    return@setAddAction
                  }

                  // Convert to relative path
                  val relativePath = path.removePrefix(basePath).removePrefix("/")

                  if (!excludedPathsTableModel.items.contains(relativePath)) {
                    excludedPathsTableModel.addRow(relativePath)
                  }
                }
              }
              .setRemoveAction {
                val selectedRows = tableView.selectedRows
                if (selectedRows.isNotEmpty()) {
                  // Remove in reverse order to maintain indices
                  selectedRows.sortedDescending().forEach { row ->
                    excludedPathsTableModel.removeRow(row)
                  }
                }
              }
              .disableUpDownActions()

          cell(decorator.createPanel())
            .align(AlignX.FILL)
            .onReset {
              excludedPathsTableModel.items =
                project.pklSettings.state.excludedProjectPaths.toMutableList()
            }
            .onApply {
              project.pklSettings.state.excludedProjectPaths =
                excludedPathsTableModel.items.toMutableSet()
              project.messageBus.syncPublisher(PKL_SETTINGS_CHANGED_TOPIC).settingsChanged()
            }
            .onIsModified {
              excludedPathsTableModel.items.toSet() !=
                project.pklSettings.state.excludedProjectPaths
            }
        }
        .rowComment("Directory paths to exclude from the \"Sync Projects\" action.")

      row("External module readers") {
          readersList(
            ReaderKind.MODULE,
            { project.pklSettings.state.externalModuleReaders.toExternalReaders() },
            { project.pklSettings.state.externalModuleReaders = it.toReaderStates() },
          )
        }
        .rowComment(
          "External readers for resolving modules. Each entry maps a URI scheme to an external reader process."
        )
    }
  }
}
