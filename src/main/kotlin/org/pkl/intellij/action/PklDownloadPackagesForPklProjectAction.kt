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
package org.pkl.intellij.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.ui.Messages
import org.pkl.intellij.packages.pklProjectService
import org.pkl.intellij.util.pklCacheDir

class PklDownloadPackagesForPklProjectAction : AnAction() {
  override fun actionPerformed(e: AnActionEvent) {
    val file = e.dataContext.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
    val project = e.project ?: return
    val pklProject = project.pklProjectService.getPklProject(file) ?: return
    val cacheDir =
      pklCacheDir?.toNioPath()
        ?: return runInEdt {
          runInEdt {
            Messages.showErrorDialog(
              "Cannot download packages because the home directory could not be found",
              "Error"
            )
          }
        }
    project.pklProjectService.downloadDependencies(pklProject, cacheDir)
  }
}
