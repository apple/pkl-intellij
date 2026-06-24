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
package org.pkl.intellij.notification

import com.intellij.ide.scratch.ScratchUtil
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.ui.EditorNotificationProvider
import com.intellij.ui.EditorNotifications
import java.util.function.Function
import javax.swing.JComponent
import org.pkl.intellij.packages.*
import org.pkl.intellij.packages.dto.PklProject
import org.pkl.intellij.psi.PklModule
import org.pkl.intellij.toolchain.pklCli

class PklSyncProjectNotificationProvider(project: Project) : EditorNotificationProvider {
  init {
    project.messageBus.connect().apply {
      subscribe(
        PklProjectService.PKL_PROJECTS_TOPIC,
        object : PklProjectListener {
          override fun pklProjectDependenciesUpdated(
            service: PklProjectService,
            pklProject: PklProject
          ) {
            EditorNotifications.getInstance(project).updateAllNotifications()
          }

          override fun pklProjectsUpdated(
            service: PklProjectService,
            projects: Map<String, PklProject>
          ) {
            EditorNotifications.getInstance(project).updateAllNotifications()
          }
        }
      )
    }
  }

  private val noComponent: Function<in FileEditor, out JComponent?> = Function { null }

  override fun collectNotificationData(
    project: Project,
    file: VirtualFile
  ): Function<in FileEditor, out JComponent?> {
    if (ScratchUtil.isScratch(file)) return noComponent
    if (!project.pklCli.isAvailable()) return noComponent
    val psiFile = PsiManager.getInstance(project).findFile(file)
    if (psiFile !is PklModule) return noComponent
    // isInPklProject performs a VFS directory walk — must run off EDT here, not in the Function
    if (!psiFile.isInPklProject) return noComponent
    val hasPklProject = psiFile.pklProject != null
    val isOutOfDate = project.pklProjectService.isOutOfDate()
    val error = if (!hasPklProject) project.pklProjectService.getError(file) else null
    return Function { _ ->
      when {
        !hasPklProject ->
          PklEditorNotificationPanel().apply {
            if (error != null) {
              text = "Project Sync error"
              createActionLabel("View details", "Pkl.ShowSyncError")
              createActionLabel("Retry", "Pkl.SyncPklProjects")
            } else {
              text = "Sync Pkl project"
              createActionLabel("Sync", "Pkl.SyncPklProjects")
            }
          }
        isOutOfDate ->
          PklEditorNotificationPanel().apply {
            text = "Sync Pkl project"
            createActionLabel("Sync", "Pkl.SyncPklProjects")
          }
        else -> null
      }
    }
  }
}
