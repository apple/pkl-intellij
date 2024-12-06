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

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiFile
import org.pkl.intellij.packages.PackageDependency
import org.pkl.intellij.packages.pklPackageService
import org.pkl.intellij.psi.enclosingModule
import org.pkl.intellij.toolchain.pklCli
import org.pkl.intellij.util.CompletableFutureUtil.handleOnEdt

class PklDownloadDependencySourcesAction : IntentionAction {
  override fun startInWriteAction(): Boolean = false

  override fun getText(): String = "Download dependency sources"

  override fun getFamilyName(): String = "Download Pkl packages"

  override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean =
    project.pklCli.isAvailable()

  override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
    val pklModule = file?.enclosingModule ?: return
    val packageService = project.pklPackageService
    val packagesToDownload =
      pklModule
        .dependencies(pklModule.pklProject)
        ?.values
        ?.filterIsInstance<PackageDependency>()
        ?.filter { dep ->
          packageService.getLibraryRoots(PackageDependency(dep.packageUri, null, dep.checksums)) ==
            null
        }
        ?.map { it.packageUri.copy(checksums = it.checksums) }
        ?: return
    if (packagesToDownload.isEmpty()) return
    packageService.downloadPackage(packagesToDownload).handleOnEdt(null) { _, err ->
      if (err != null) {
        Messages.showErrorDialog(
          project,
          """
                Failed to download packages:
                
                <code>${err.message}</code>
              """
            .trimIndent(),
          "Pkl Download Failed"
        )
      }
    }
  }
}
