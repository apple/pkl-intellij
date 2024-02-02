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
package org.pkl.intellij.psi

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.OrderEnumerator
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import org.pkl.intellij.PklVersion

// Simplification: Choose a project wide stdlib,
// even if different IntelliJ modules have different stdlib versions on their class paths.
val Project.pklStdLib: PklStdLib
  get() =
    CachedValuesManager.getManager(this).getCachedValue(this) {
      CachedValueProvider.Result.create(
        PklStdLib.create(this),
        ProjectRootManager.getInstance(this)
      )
    }

class PklStdLib(val modules: List<PklStdLibModule>) {
  private val modulesByName: Map<String, PklStdLibModule> = modules.associateBy { it.name }
  private val modulesByShortName: Map<String, PklStdLibModule> =
    modules.associateBy { it.shortName }

  val baseModule: PklStdLibModule = modulesByShortName.getValue("base")

  val projectModule: PklStdLibModule = modulesByShortName.getValue("Project")

  fun getModuleByName(shortName: String): PklStdLibModule? = modulesByName[shortName]

  fun getModuleByShortName(shortName: String): PklStdLibModule? = modulesByShortName[shortName]

  companion object {
    fun create(project: Project): PklStdLib {
      val psiManager = PsiManager.getInstance(project)
      val pointerManager = SmartPointerManager.getInstance(project)

      val baseModuleFile = findBaseModuleFile(project)

      val moduleFiles =
        baseModuleFile.parent
          ?.children
          // when working on Pkl project itself
          ?.filter { it.extension == "pkl" && it.name != "package-info.pkl" }
          ?: throw AssertionError("Failed to locate Pkl standard library modules.")

      val modules =
        moduleFiles.mapNotNull { virtualFile ->
          val psiFile =
            psiManager.findFile(virtualFile)
              ?: throw AssertionError(
                "Failed to parse Pkl standard library module ${virtualFile.path}."
              )
          // `.pkl` files might not be associated with the Pkl language.
          if (psiFile !is PklModule) return@mapNotNull null
          val psiPointer = pointerManager.createSmartPsiElementPointer<PklModule>(psiFile, psiFile)
          PklStdLibModule(virtualFile, psiPointer)
        }

      return PklStdLib(modules)
    }

    private fun findBaseModuleFile(project: Project): VirtualFile {
      if (project.name == "pkl") {
        return project.guessProjectDir()?.findFileByRelativePath("stdlib/base.pkl")
          ?: throw AssertionError("Cannot find file `stdlib/base.pkl` in pkl/pkl project.")
      }

      val roots =
        OrderEnumerator.orderEntries(project).withoutSdk().withoutModuleSourceEntries().classesRoots

      // post-rename: pkl-core, pkl-config-java-all
      val fileName1 = "org/pkl/core/stdlib/base.pkl"
      // post-rename: pkl-stdlib (should come last)
      val fileName2 = "org/pkl/stdlib/base.pkl"

      for (root in roots) {
        val file = root.findFileByRelativePath(fileName1) ?: root.findFileByRelativePath(fileName2)
        if (file != null) return file
      }

      // no stdlib found under roots -> use stdlib bundled with plugin

      // This alternative implementation doesn't solve the restart problem described below.
      // val classLoader =
      // PluginManagerCore.getPlugin(PluginId.getId("org.pkl-lang"))!!.pluginClassLoader
      // val url = ResourceUtil.getResource(classLoader, "org/pkl/stdlib", "base.pkl")
      val url =
        this::class.java.getResource("/org/pkl/stdlib/base.pkl")
          ?: throw AssertionError("Cannot find class path resource `/org/pkl/stdlib/base.pkl`.")

      // Returns `null` after plugin install until IDE is restarted.
      // We work around this with `<idea-plugin require-restart="true">`.
      // For now, requiring a restart is acceptable because updating
      // (rather than installing) the plugin results in a restart in any case.
      // That's because since 2021.1, having a Tools->Pkl menu breaks dynamic plugin unloading
      // (https://youtrack.jetbrains.com/issue/IDEA-263300).
      // see https://youtrack.jetbrains.com/issue/IDEA-284729 for a proposed solution that isn't
      // ideal for us
      return VfsUtil.findFileByURL(url)
        ?: throw AssertionError("Cannot load class path resource `$url` as virtual file.")
    }
  }

  // lazy to avoid cycle
  val version: PklVersion by lazy {
    // recent stdlib modules define `minPklVersion`
    val minPklVersion = baseModule.psi.minPklVersion
    if (minPklVersion != null) return@lazy minPklVersion

    val moduleFile = baseModule.file
    // pkl-core-1.2.3.jar, pkl-config-java-all-1.2.3.jar, pkl-stdlib-1.2.3.zip, etc.
    val archiveFile = VfsUtilCore.getRootFile(moduleFile)
    val archiveName = archiveFile.nameWithoutExtension
    val index = archiveName.lastIndexOf('-')
    if (index == -1) {
      throw AssertionError("Cannot find version suffix in file name `${archiveFile.name}`.")
    }
    val versionString = archiveName.substring(index + 1)
    PklVersion.parse(versionString)
      ?: throw AssertionError(
        "Cannot parse version suffix `$versionString` of file name `${archiveFile.name}`."
      )
  }
}

class PklStdLibModule(
  val file: VirtualFile,
  private val pointer: SmartPsiElementPointer<PklModule>
) {
  val shortName: String = file.nameWithoutExtension

  val name: String = "pkl.$shortName"

  val uri: String = "pkl:$shortName"

  val psi
    get() =
      pointer.element
        ?: throw AssertionError("Failed to access PSI of Pkl standard library module `$name`.")
}
