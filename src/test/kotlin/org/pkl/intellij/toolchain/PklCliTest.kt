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
package org.pkl.intellij.toolchain

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.util.ProgressIndicatorBase
import com.intellij.testFramework.LoggedErrorProcessor
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.pkl.intellij.PklTestCase
import org.pkl.intellij.packages.dto.PackageUri
import org.pkl.intellij.packages.pklPackageService
import org.pkl.intellij.settings.pklSettings

class PklCliTest : PklTestCase() {
  fun `test downloadPackage fails when pkl executable does not exist`() {
    project.pklSettings.state.pklPath = "/nonexistent/path/to/pkl"
    val uri =
      PackageUri.create("package://pkg.pkl-lang.org/pkl-pantry/k8s.contrib.appEnvCluster@1.0.1")!!
    LoggedErrorProcessor.executeWith<Throwable>(
      object : LoggedErrorProcessor() {
        override fun processError(
          category: String,
          message: String,
          details: Array<out String>,
          t: Throwable?,
        ): Set<Action> = emptySet()
      }
    ) {
      val future = project.pklPackageService.downloadPackage(uri)
      assertThatThrownBy {
          future.get(5, TimeUnit.SECONDS)
        }
        .isInstanceOf(ExecutionException::class.java)
    }
  }

  fun `test executeCommand cancels when indicator is canceled`() {
    val indicator = ProgressIndicatorBase()
    indicator.cancel()
    ProgressManager.getInstance()
      .runProcess(
        {
          assertThatThrownBy {
              project.pklCli.downloadPackage(emptyList())
            }
            .isInstanceOf(ProcessCanceledException::class.java)
        },
        indicator,
      )
  }
}
