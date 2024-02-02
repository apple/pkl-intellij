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
package org.pkl.intellij.notification

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationProvider
import com.intellij.ui.EditorNotifications
import java.util.function.Function
import javax.swing.JComponent
import org.pkl.intellij.PklFileType
import org.pkl.intellij.settings.PklSettingsChangedListener
import org.pkl.intellij.settings.PklSettingsComponent.Companion.PKL_SETTINGS_CHANGED_TOPIC
import org.pkl.intellij.toolchain.pklCli

class PklToolchainNotificationProvider(project: Project) : EditorNotificationProvider {
  init {
    project.messageBus.connect().apply {
      subscribe(
        PKL_SETTINGS_CHANGED_TOPIC,
        PklSettingsChangedListener {
          EditorNotifications.getInstance(project).updateAllNotifications()
        }
      )
    }
  }

  override fun collectNotificationData(
    project: Project,
    file: VirtualFile
  ): Function<in FileEditor, out JComponent?> {
    return Function { _ ->
      if (file.fileType != PklFileType) return@Function null
      if (project.pklCli.isAvailable()) return@Function null
      PklEditorNotificationPanel().apply {
        text = "Pkl CLI not found"
        createActionLabel("Configure", "Pkl.OpenSettingsPanelAction")
      }
    }
  }
}
