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
@file:Suppress("PROVIDED_RUNTIME_TOO_LOW")

package org.pkl.intellij.packages.dto

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.pkl.intellij.packages.Dependency
import org.pkl.intellij.packages.LocalProjectDependency
import org.pkl.intellij.packages.PackageDependency
import org.pkl.intellij.util.dropRoot

data class PklProject(val metadata: DerivedProjectMetadata, val projectDeps: ProjectDeps?) {

  companion object {
    private val json = Json { ignoreUnknownKeys = true }

    fun parseMetadata(input: String): List<DerivedProjectMetadata> {
      return json.decodeFromString(input)
    }

    private fun parseDeps(input: String): ProjectDeps {
      return json.decodeFromString(input)
    }

    fun loadProjectDeps(file: VirtualFile): ProjectDeps {
      val contents = file.inputStream.readAllBytes().toString(StandardCharsets.UTF_8)
      return parseDeps(contents)
    }

    @Serializable
    data class DerivedProjectMetadata(
      val projectFileUri: String,
      val declaredDependencies: Map<String, PackageUri>,
      val evaluatorSettings: EvaluatorSettings
    )

    @Serializable data class EvaluatorSettings(val moduleCacheDir: String? = null)

    @Serializable
    data class ProjectDeps(
      val schemaVersion: Int,
      val resolvedDependencies: Map<String, ResolvedDependency>
    ) {
      /** Given a package URI, return the resolved dependency for it. */
      fun getResolvedDependency(packageUri: PackageUri): ResolvedDependency? {
        val packageUriStr = packageUri.toString()
        return resolvedDependencies.entries.find { packageUriStr.startsWith(it.key) }?.value
      }
    }

    @Serializable
    sealed class ResolvedDependency {
      abstract val type: DependencyType
      abstract val uri: PackageUri
    }

    @Serializable
    @SerialName("local")
    data class LocalDependency(override val uri: PackageUri, val path: String) :
      ResolvedDependency() {
      override val type: DependencyType = DependencyType.LOCAL
    }

    @Serializable
    @SerialName("remote")
    data class RemoteDependency(override val uri: PackageUri, val checksums: Checksums?) :
      ResolvedDependency() {
      override val type: DependencyType = DependencyType.REMOTE
    }

    @Serializable
    enum class DependencyType(val strValue: String) {
      @SerialName("local") LOCAL("local"),
      @SerialName("remote") REMOTE("remote")
    }
  }

  val projectFile: Path by lazy { Path.of(URI(metadata.projectFileUri)) }

  private val projectDir: Path by lazy { projectFile.parent }

  val projectDirVirtualFile: VirtualFile? by lazy { localFs.findFileByNioFile(projectDir) }

  val projectPackageCacheDirPath: Path? by lazy {
    VfsUtil.getUserHomeDir()
      ?.toNioPath()
      ?.resolve(".pkl/editor-support/projectpackage/${projectDir.dropRoot()}")
  }

  fun projectPackagesCacheDir(): VirtualFile? {
    return projectPackageCacheDirPath?.let(localFs::findFileByNioFile)
  }

  private val localFs: LocalFileSystem = LocalFileSystem.getInstance()

  /** The dependencies declared within the PklProject file */
  val myDependencies: Map<String, Dependency>
    get() {
      return metadata.declaredDependencies.entries.fold(mapOf()) { acc, (name, packageUri) ->
        val dep = projectDeps?.getResolvedDependency(packageUri)?.toDependency ?: return@fold acc
        acc.plus(name to dep)
      }
    }

  private val self = this

  private val ResolvedDependency.toDependency: Dependency?
    get() =
      when (this) {
        is LocalDependency ->
          projectDirVirtualFile?.findFileByRelativePath(path)?.let { LocalProjectDependency(it) }
        else -> PackageDependency(uri, self)
      }
}
