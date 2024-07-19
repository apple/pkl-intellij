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
package org.pkl.intellij

import com.intellij.openapi.Disposable
import com.intellij.testFramework.builders.ModuleFixtureBuilder
import com.intellij.testFramework.fixtures.CodeInsightFixtureTestCase
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.copyToRecursively
import kotlin.io.path.createDirectories
import org.pkl.intellij.packages.PklProjectService.Companion.PKL_PROJECTS_SYNC_TOPIC
import org.pkl.intellij.packages.PklProjectSyncListener
import org.pkl.intellij.packages.pklProjectService
import org.pkl.intellij.settings.pklSettings

@OptIn(ExperimentalPathApi::class)
abstract class PklTestCase : CodeInsightFixtureTestCase<ModuleFixtureBuilder<*>>() {

  open val fixtureDir: Path? = null

  override fun setUp() {
    super.setUp()
    val projectDir = myFixture.findFileInTempDir(".")
    // copy all files in the fixtures dir into the ephemeral test project
    fixtureDir?.copyToRecursively(
      projectDir.toNioPath().apply { parent.createDirectories() },
      followLinks = false
    )
    System.getProperty("pklExecutable")?.let { project.pklSettings.state.pklPath = it }
  }

  override fun getTestRootDisposable(): Disposable {
    return myFixture?.testRootDisposable ?: super.getTestRootDisposable()
  }

  protected fun syncProjects() {
    val fut = CompletableFuture<Any>()
    myFixture.project.messageBus
      .connect()
      .subscribe(
        PKL_PROJECTS_SYNC_TOPIC,
        object : PklProjectSyncListener {
          override fun pklProjectSyncStarted() {
            // no-op
          }

          override fun pklProjectSyncFinished() {
            fut.complete(null)
          }
        }
      )
    myFixture.project.pklProjectService.syncProjects()
    fut.get()
  }
}
