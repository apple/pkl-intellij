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

import com.intellij.ide.scratch.ScratchUtil
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationProvider
import com.intellij.ui.EditorNotifications
import java.util.function.Function
import javax.swing.JComponent
import org.pkl.intellij.packages.*
import org.pkl.intellij.packages.PklProjectService.Companion.PKL_PROJECT_FILENAME
import org.pkl.intellij.packages.dto.PklProject
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

  override fun collectNotificationData(
    project: Project,
    file: VirtualFile
  ): Function<in FileEditor, out JComponent?> {
    return Function { _ ->
      if (file.name != PKL_PROJECT_FILENAME) return@Function null
      if (ScratchUtil.isScratch(file)) return@Function null
      if (!project.pklCli.isAvailable()) return@Function null
      val pklProject =
        project.pklProjectService.getPklProject(file)
          ?: return@Function PklEditorNotificationPanel().apply {
            val error = project.pklProjectService.getError(file)
            if (error != null) {
              text = "Project Sync error"

              createActionLabel("View details", "Pkl.ShowSyncError")
              createActionLabel("Retry", "Pkl.SyncPklProjects")
            } else {
              text = "Sync Pkl project"

              createActionLabel("Sync", "Pkl.SyncPklProjects")
            }
          }
      val packageService = project.pklPackageService
      val missingDependencies =
        pklProject.myDependencies.values.filterIsInstance<PackageDependency>().filter {
          packageService.getLibraryRoots(it) == null
        }
      if (missingDependencies.isNotEmpty()) {
        thisLogger()
          .info(
            "Missing dependencies in project: ${missingDependencies.joinToString(", ") { it.packageUri.toString() }}"
          )
        return@Function PklEditorNotificationPanel().apply {
          text = "Missing sources for packages"
          createActionLabel("Download", "Pkl.DownloadPackagesForPklProject")
        }
      }
      null
    }
  }
}
