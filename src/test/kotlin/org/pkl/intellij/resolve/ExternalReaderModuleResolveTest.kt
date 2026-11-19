/**
 * Copyright © 2026 Apple Inc. and the Pkl project authors. All rights reserved.
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

import com.intellij.testFramework.PlatformTestUtil
import java.net.URI
import java.nio.file.Path
import kotlin.io.path.exists
import org.assertj.core.api.Assertions.assertThat
import org.pkl.intellij.PklTestCase
import org.pkl.intellij.externalreader.pklExternalReaderService
import org.pkl.intellij.psi.PklClassProperty
import org.pkl.intellij.psi.PklModule
import org.pkl.intellij.psi.PklModuleUriReference
import org.pkl.intellij.psi.enclosingModule
import org.pkl.intellij.settings.ExternalReaderState
import org.pkl.intellij.settings.pklSettings

class ExternalReaderModuleResolveTest : PklTestCase() {
  companion object {
    private const val TIMEOUT_SECONDS = 60

    private val javaExecutable: String =
      Path.of(System.getProperty("java.home"), "bin", "java").toString()

    /** Written by the `buildExternalReaderFixture` Gradle task. */
    private val fixtureArgfile: Path =
      Path.of("build/externalReaderFixture/java.argfile").toAbsolutePath()

    /** The arguments to start the fixture reader with, serving [scheme]. */
    private fun fixtureArguments(scheme: String): List<String> = listOf("@$fixtureArgfile", scheme)
  }

  override fun setUp() {
    if (!fixtureArgfile.exists()) {
      throw IllegalStateException(
        "External reader fixture not found. Run `./gradlew buildExternalReaderFixture`."
      )
    }
    super.setUp()
  }

  // region helpers

  private fun configureSettingsReader(scheme: String = "fixture", servedScheme: String = scheme) {
    project.pklSettings.state.externalModuleReaders[scheme] =
      ExternalReaderState(javaExecutable, fixtureArguments(servedScheme).toMutableList(), null)
  }

  private fun configureProjectReader(scheme: String = "fixture") {
    myFixture.addFileToProject(
      "PklProject",
      """
      amends "pkl:Project"

      evaluatorSettings {
        externalModuleReaders {
          ["$scheme"] {
            executable = ${pklString(javaExecutable)}
            arguments { ${fixtureArguments(scheme).joinToString("; ") { pklString(it) }} }
          }
        }
      }
      """
        .trimIndent(),
    )
    syncProjects()
  }

  private fun pklString(value: String) =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

  /** Waits until [compute] returns a value, dispatching events in the meantime. */
  private fun <T : Any> await(what: String, compute: () -> T?): T {
    var result: T? = null
    PlatformTestUtil.waitWithEventsDispatching(
      "Timed out waiting for $what",
      {
        result = compute()
        result != null
      },
      TIMEOUT_SECONDS,
    )
    return result!!
  }

  /** Waits until the module at [uri] was fetched into the cache, as seen from the opened file. */
  private fun awaitFetched(uri: String) {
    val context = (myFixture.file as PklModule).pklProject
    await("$uri to be fetched") {
      project.pklExternalReaderService.resolve(URI(uri), context)
    }
  }

  private fun resolveAtCaret(): PklModule {
    val reference = myFixture.getReferenceAtCaretPosition()
    assertThat(reference).isInstanceOf(PklModuleUriReference::class.java)
    val resolved = reference!!.resolve()
    assertThat(resolved).isInstanceOf(PklModule::class.java)
    return resolved as PklModule
  }

  private fun originalUriOf(module: PklModule): String? =
    project.pklExternalReaderService.originalUri(module.virtualFile)?.toString()

  // endregion

  // region readers from the settings

  fun `test settings - resolves import`() {
    configureSettingsReader()
    myFixture.configureByText("main.pkl", """import "fixture:/animals/d<caret>og.pkl"""")
    awaitFetched("fixture:/animals/dog.pkl")

    val resolved = resolveAtCaret()
    assertThat(resolved.name).isEqualTo("dog.pkl")
    assertThat(resolved.declaredName?.text).isEqualTo("fixture.animals.dog")
    assertThat(originalUriOf(resolved)).isEqualTo("fixture:/animals/dog.pkl")
  }

  fun `test settings - resolves member of imported module`() {
    configureSettingsReader()
    myFixture.configureByText(
      "main.pkl",
      """
      import "fixture:/animals/dog.pkl"

      sound = dog.so<caret>und
      """
        .trimIndent(),
    )
    awaitFetched("fixture:/animals/dog.pkl")

    val resolved = myFixture.getReferenceAtCaretPosition()!!.resolve()
    assertThat(resolved).isInstanceOf(PklClassProperty::class.java)
    resolved as PklClassProperty
    assertThat(resolved.nameIdentifier.text).isEqualTo("sound")
    assertThat(resolved.enclosingModule?.declaredName?.text).isEqualTo("fixture.animals.dog")
  }

  fun `test settings - resolves relative import from a module of the reader`() {
    configureSettingsReader()
    myFixture.configureByText("main.pkl", """import "fixture:/animals/wild/w<caret>olf.pkl"""")
    awaitFetched("fixture:/animals/wild/wolf.pkl")

    // wolf.pkl is `extends "../dog.pkl"`; that isn't cached yet, and has to be fetched
    val wolf = resolveAtCaret()
    val extendsReference = wolf.extendsAmendsClause!!.moduleUri!!.references.last()
    val dog = await("../dog.pkl to resolve") { extendsReference.resolve() }

    assertThat(dog).isInstanceOf(PklModule::class.java)
    assertThat(originalUriOf(dog as PklModule)).isEqualTo("fixture:/animals/dog.pkl")
  }

  fun `test settings - resolves triple-dot import from a module of the reader`() {
    configureSettingsReader()
    myFixture.configureByText("main.pkl", """import "fixture:/animals/wild/f<caret>ox.pkl"""")
    awaitFetched("fixture:/animals/wild/fox.pkl")

    // fox.pkl is `extends ".../dog.pkl"`; dog.pkl is in the parent directory, and isn't cached yet
    val fox = resolveAtCaret()
    val extendsReference = fox.extendsAmendsClause!!.moduleUri!!.references.last()
    val dog = await(".../dog.pkl to resolve") { extendsReference.resolve() }

    assertThat(dog).isInstanceOf(PklModule::class.java)
    assertThat(originalUriOf(dog as PklModule)).isEqualTo("fixture:/animals/dog.pkl")
  }

  fun `test settings - resolves glob import to all matching modules`() {
    configureSettingsReader()
    myFixture.configureByText(
      "main.pkl",
      """import* "fixture:/animals/*<caret>.pkl" as allAnimals""",
    )

    val reference = myFixture.getReferenceAtCaretPosition() as PklModuleUriReference
    // directories are fetched level by level, so the result fills in over time
    val resolved =
      await("glob to resolve to two modules") {
        reference.multiResolve(false).takeIf { it.size == 2 }
      }

    assertThat(resolved.map { (it.element as PklModule).name })
      .containsExactlyInAnyOrder("dog.pkl", "cat.pkl")
  }

  fun `test settings - module that the reader doesn't have stays unresolved`() {
    configureSettingsReader()
    myFixture.configureByText("main.pkl", """import "fixture:/animals/no<caret>pe.pkl"""")

    val service = project.pklExternalReaderService
    val before = service.modificationTracker.modificationCount
    val reference = myFixture.getReferenceAtCaretPosition()!!
    assertThat(reference.resolve()).isNull()
    // the fetch fails, and the cache is updated once about that
    await("the failed fetch to finish") {
      service.modificationTracker.modificationCount.takeIf { it > before }
    }
    assertThat(reference.resolve()).isNull()
  }

  fun `test settings - reader for a scheme that it doesn't serve resolves nothing`() {
    // the process answers for `other`, not for `fixture`
    configureSettingsReader(scheme = "fixture", servedScheme = "other")
    myFixture.configureByText("main.pkl", """import "fixture:/animals/d<caret>og.pkl"""")

    val service = project.pklExternalReaderService
    val before = service.modificationTracker.modificationCount
    assertThat(myFixture.getReferenceAtCaretPosition()!!.resolve()).isNull()
    await("the failed fetch to finish") {
      service.modificationTracker.modificationCount.takeIf { it > before }
    }
    assertThat(myFixture.getReferenceAtCaretPosition()!!.resolve()).isNull()
  }

  // endregion

  // region readers from a PklProject

  fun `test project - resolves import`() {
    configureProjectReader()
    myFixture.configureByText("main.pkl", """import "fixture:/animals/d<caret>og.pkl"""")
    awaitFetched("fixture:/animals/dog.pkl")

    val resolved = resolveAtCaret()
    assertThat(resolved.declaredName?.text).isEqualTo("fixture.animals.dog")
    assertThat(originalUriOf(resolved)).isEqualTo("fixture:/animals/dog.pkl")
  }

  fun `test project - resolves relative import from a module of the reader`() {
    configureProjectReader()
    myFixture.configureByText("main.pkl", """import "fixture:/animals/wild/w<caret>olf.pkl"""")
    awaitFetched("fixture:/animals/wild/wolf.pkl")

    // The cache isn't part of the project, so this needs the reader that wolf.pkl was read by
    // (which is only declared by the project).
    val wolf = resolveAtCaret()
    val extendsReference = wolf.extendsAmendsClause!!.moduleUri!!.references.last()
    val dog = await("../dog.pkl to resolve") { extendsReference.resolve() }

    assertThat(dog).isInstanceOf(PklModule::class.java)
    assertThat(originalUriOf(dog as PklModule)).isEqualTo("fixture:/animals/dog.pkl")
  }

  fun `test project - resolves glob import to all matching modules`() {
    configureProjectReader()
    myFixture.configureByText(
      "main.pkl",
      """import* "fixture:/animals/*<caret>.pkl" as allAnimals""",
    )

    val reference = myFixture.getReferenceAtCaretPosition() as PklModuleUriReference
    val resolved =
      await("glob to resolve to two modules") {
        reference.multiResolve(false).takeIf { it.size == 2 }
      }

    assertThat(resolved.map { (it.element as PklModule).name })
      .containsExactlyInAnyOrder("dog.pkl", "cat.pkl")
  }

  fun `test project - reader takes precedence over the settings`() {
    // The settings' reader doesn't serve `fixture`, so resolving only works through the project's.
    configureSettingsReader(scheme = "fixture", servedScheme = "other")
    configureProjectReader()
    myFixture.configureByText("main.pkl", """import "fixture:/animals/d<caret>og.pkl"""")
    awaitFetched("fixture:/animals/dog.pkl")

    assertThat(originalUriOf(resolveAtCaret())).isEqualTo("fixture:/animals/dog.pkl")
  }

  // endregion
}
