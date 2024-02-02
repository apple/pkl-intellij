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

import com.intellij.ide.IdeBundle
import com.intellij.ide.IdeView
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.LangDataKeys.IDE_VIEW
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiFile
import org.pkl.intellij.PklFileType

class PklCreateProjectAction :
  DumbAwareAction(PKL_PROJECT_FILE, "Create PklProject file", PklFileType.icon) {

  companion object {
    const val PKL_PROJECT_FILE = "PklProject"
  }

  override fun isDumbAware(): Boolean = true

  private fun hasPklProject(view: IdeView): Boolean {
    return view.directories.any { it.findFile("PklProject") != null }
  }

  override fun update(e: AnActionEvent) {
    val view = IDE_VIEW.getData(e.dataContext) ?: return
    val isAvailable = !hasPklProject(view)
    e.presentation.isEnabledAndVisible = isAvailable
  }

  override fun actionPerformed(e: AnActionEvent) {
    val view = IDE_VIEW.getData(e.dataContext) ?: return
    var firstCreatedFile: PsiFile? = null
    if (hasPklProject(view)) {
      Messages.showErrorDialog(
        CommonDataKeys.PROJECT.getData(e.dataContext),
        "Directory already contains a PklProject file",
        IdeBundle.message("title.cannot.create.file")
      )
      return
    }
    runWriteAction {
      for (directory in view.directories) {
        val element = directory.createFile(PKL_PROJECT_FILE)
        element.virtualFile.setBinaryContent(
          """
          amends "pkl:Project"
          
        """.trimIndent().toByteArray()
        )
        if (firstCreatedFile == null) {
          firstCreatedFile = element
        }
      }
      firstCreatedFile?.let { view.selectElement(it) }
    }
  }
}
