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
package org.pkl.intellij.resolve

import com.intellij.testFramework.fixtures.*
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import org.assertj.core.api.Assertions.assertThat
import org.pkl.intellij.packages.PklProjectService.Companion.PKL_PROJECTS_SYNC_TOPIC
import org.pkl.intellij.packages.PklProjectSyncListener
import org.pkl.intellij.packages.pklProjectService
import org.pkl.intellij.psi.PklClassProperty
import org.pkl.intellij.psi.PklModule
import org.pkl.intellij.psi.PklModuleUriReference
import org.pkl.intellij.psi.enclosingModule
import org.pkl.intellij.settings.PklSettingsService
import org.pkl.intellij.settings.pklSettings

class LocalProjectResolveTest : BasePlatformTestCase() {
  private fun syncProjects() {
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
    myFixture.project.pklProjectService.syncProjects(Path.of(myFixture.tempDirPath))
    fut.get()
  }

  override fun setUp() {
    super.setUp()
    System.getProperty("pklExecutable")?.let { prop ->
      myFixture.project.pklSettings.loadState(PklSettingsService.State(pklPath = prop))
    }
    // copy all files in resources/completion/projects into the ephemeral test project
    myFixture.copyDirectoryToProject("", "")
    syncProjects()
  }

  override fun getTestDataPath(): String = "src/test/resources/resolve/projects"

  // force use of real temp dirs instead of in-memory virtual files because we need to resolve them
  // using the pkl CLI.
  override fun createTempDirTestFixture(): TempDirTestFixture = TempDirTestFixtureImpl()

  fun `test that imports resolve to package dependencies`() {
    myFixture.configureByFile("project1/moduleCompletion1.pkl")
    val reference = myFixture.getReferenceAtCaretPosition()
    assertThat(reference).isInstanceOf(PklModuleUriReference::class.java)
    val resolved = reference!!.resolve()
    assertThat(resolved).isInstanceOf(PklModule::class.java)
    resolved as PklModule
    assertThat(resolved.name).isEqualTo("AppEnvCluster.pkl")
  }

  fun `test that transitive dependencies resolve to project dependencies`() {
    myFixture.configureByFile("project1/moduleCompletion2.pkl")
    val reference = myFixture.getReferenceAtCaretPosition()
    assertThat(reference).isNotNull
    val resolved = reference!!.resolve()
    assertThat(resolved).isInstanceOf(PklClassProperty::class.java)
    resolved as PklClassProperty
    assertThat(resolved.nameIdentifier.text).isEqualTo("name")
    assertThat(resolved.enclosingModule?.declaredName?.text)
      .isEqualTo("k8s.apimachinery.pkg.apis.meta.v1.ObjectMeta")
    val pkg = resolved.enclosingModule!!.`package`
    assertThat(pkg?.packageUri?.version?.toString()).isEqualTo("1.1.0")
  }

  fun `test that local project dependencies resolve to local files`() {
    myFixture.configureByFile("project2/moduleCompletion3.pkl")
    val reference = myFixture.getReferenceAtCaretPosition()
    assertThat(reference).isNotNull
    val resolved = reference!!.resolve()
    assertThat(resolved).isInstanceOf(PklModule::class.java)
    resolved as PklModule
    assertThat(resolved.virtualFile.url).endsWith("project1/moduleCompletion1.pkl")
  }

  fun `test that local project transitive dependencies resolve to enclosing project's dependencies`() {
    myFixture.configureByFile("project2/moduleCompletion4.pkl")
    val reference = myFixture.getReferenceAtCaretPosition()
    assertThat(reference).isNotNull
    val resolved = reference!!.resolve()
    assertThat(resolved).isInstanceOf(PklClassProperty::class.java)
    resolved as PklClassProperty
    assertThat(resolved.nameIdentifier.text).isEqualTo("name")
    assertThat(resolved.enclosingModule?.declaredName?.text)
      .isEqualTo("k8s.apimachinery.pkg.apis.meta.v1.ObjectMeta")
    val pkg = resolved.enclosingModule!!.`package`
    assertThat(pkg?.packageUri?.version?.toString()).isEqualTo("1.1.0")
  }
}
