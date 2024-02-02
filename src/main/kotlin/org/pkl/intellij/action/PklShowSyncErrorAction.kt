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
import com.intellij.openapi.ui.Messages
import org.pkl.intellij.packages.pklProjectService
import org.pkl.intellij.toolchain.PklEvalException

class PklShowSyncErrorAction : AnAction() {
  private fun renderErrorMessage(err: Throwable): String? {
    return if (err is PklEvalException)
      """
          An error occured while calling into Pkl.

          <p>Command:</p>
          <p><code>${err.command}</code></p>

          <p>Error:</p>
          <p>
            <pre>
              <code>${err.message}</code>
            </pre>
          </p>
        """
        .trimIndent()
    else err.message
  }

  override fun actionPerformed(e: AnActionEvent) {
    val file = e.dataContext.getData(CommonDataKeys.VIRTUAL_FILE)!!
    val error = e.project?.pklProjectService?.getError(file) ?: return
    Messages.showErrorDialog(e.project, renderErrorMessage(error), "Pkl Sync Error")
  }
}
