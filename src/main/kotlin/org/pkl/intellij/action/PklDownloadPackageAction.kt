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
import com.intellij.collaboration.async.CompletableFutureUtil.handleOnEdt
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiFile
import org.pkl.intellij.packages.dto.PackageUri
import org.pkl.intellij.packages.pklPackageService

// Not registered in plugin.xml because we only use this as an intention.
class PklDownloadPackageAction(private val packageUri: PackageUri) : IntentionAction {
  override fun startInWriteAction(): Boolean = false

  override fun getText(): String = "Download $packageUri"

  override fun getFamilyName(): String = "Download Pkl package"

  override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean = true

  override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
    project.pklPackageService.downloadPackage(packageUri).handleOnEdt(null) { _, err ->
      if (err != null) {
        Messages.showErrorDialog(
          project,
          """
              Failed to download package:
              
              <code>${err.message}</code>
            """
            .trimIndent(),
          "Pkl Download Failed"
        )
      }
    }
  }
}
