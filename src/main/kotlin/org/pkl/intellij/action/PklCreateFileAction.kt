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
package org.pkl.intellij.action

import com.intellij.ide.IdeBundle
import com.intellij.ide.actions.CreateFileFromTemplateAction
import com.intellij.ide.actions.CreateFileFromTemplateDialog.Builder
import com.intellij.ide.fileTemplates.FileTemplate
import com.intellij.ide.fileTemplates.FileTemplateManager
import com.intellij.ide.fileTemplates.FileTemplateUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import com.intellij.util.IncorrectOperationException
import org.pkl.intellij.PklFileType
import org.pkl.intellij.PklIcons
import org.pkl.intellij.psi.pklBaseModule

class PklCreateFileAction :
  CreateFileFromTemplateAction("Pkl File", "Create new Pkl file", PklFileType.icon) {

  companion object {
    private val LOG = Logger.getInstance(PklCreateFileAction::class.java)
  }

  override fun buildDialog(project: Project, directory: PsiDirectory, builder: Builder) {
    builder
      .setTitle("Pkl File")
      .addKind("Pkl file", PklIcons.FILE, "Pkl File")
      .addKind("Pkl template", PklIcons.CLASS, "Pkl Template")
  }

  override fun getActionName(
    directory: PsiDirectory,
    newName: String,
    templateName: String
  ): String = "Pkl File"

  override fun createFileFromTemplate(
    name: String,
    template: FileTemplate,
    dir: PsiDirectory
  ): PsiFile? =
    try {
      val project = dir.project
      val parts = name.split("/")
      val fileName = parts.last()
      val targetDir =
        parts.dropLast(1).fold(dir) { current, segment ->
          current.findSubdirectory(segment) ?: current.createSubdirectory(segment)
        }
      val templateManager = FileTemplateManager.getInstance(project)
      val properties = templateManager.defaultProperties
      properties["PKL_VERSION"] = project.pklBaseModule.pklVersion.toString()
      FileTemplateUtil.createFromTemplate(template, fileName, properties, targetDir) as? PsiFile
    } catch (e: IncorrectOperationException) {
      ApplicationManager.getApplication().invokeLater {
        Messages.showErrorDialog(
          dir.project,
          e.message,
          IdeBundle.message("title.cannot.create.file")
        )
      }
      null
    } catch (e: Exception) {
      LOG.error("Error creating new Pkl file", e)
      null
    }
}
