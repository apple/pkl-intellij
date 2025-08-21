/**
 * Copyright © 2025 Apple Inc. and the Pkl project authors. All rights reserved.
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

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.runBackgroundableTask
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.io.createDirectories
import com.intellij.util.text.SemVer
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.CompletableFuture
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import kotlin.io.copyTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.pkl.intellij.util.copyToWithIndicator
import org.pkl.intellij.util.handleOnEdt

class PklDownloadPklCliAction(
  private val onStart: () -> Unit,
  private val onEnd: (String?, Throwable?) -> Unit
) :
  DumbAwareAction(
    "Download Pkl Executable",
    "Downloads Pkl executable",
    AllIcons.Actions.Download
  ) {
  companion object {
    @Serializable private data class GithubRelease(val name: String)

    private val json: Json = Json { ignoreUnknownKeys = true }
    private val httpClient: HttpClient = HttpClient {}
  }

  override fun actionPerformed(e: AnActionEvent) {
    if (e.project == null) return
    val dialog = DownloadPklDialog(e.project!!)
    if (dialog.showAndGet()) {
      val version = dialog.version
      val targetPath = Path.of(dialog.destinationPath)
      val url = suggestedPklUrl(version)

      downloadPklExecutable(targetPath, url)
    }
  }

  private fun downloadPklExecutable(targetPath: Path, url: String) {
    val future = CompletableFuture<String>()

    onStart()
    future.handleOnEdt { result, error -> onEnd(result, error) }

    runBackgroundableTask("Downloading Pkl executable") { progressIndicator ->
      try {
        targetPath.parent.createDirectories()
        val conn = URL(url).openConnection()
        val totalLength = conn.contentLengthLong
        val fileOutputStream = Files.newOutputStream(targetPath)
        conn.inputStream.use { input ->
          fileOutputStream.use { out ->
            // -1 means we don't know what the content length is; skip showing progress.
            if (totalLength == -1L) {
              input.copyTo(out)
            } else {
              input.copyToWithIndicator(out, progressIndicator, totalLength)
            }
          }
        }
        makeExecutableIfNeeded(targetPath)
        future.complete(targetPath.toString())
      } catch (e: Exception) {
        future.completeExceptionally(e)
      }
    }
  }

  private fun makeExecutableIfNeeded(path: Path) {
    if (System.getProperty("os.name").lowercase().contains("win")) return

    try {
      val perms =
        Files.getPosixFilePermissions(path).toMutableSet().apply {
          add(PosixFilePermission.OWNER_EXECUTE)
          add(PosixFilePermission.GROUP_EXECUTE)
          add(PosixFilePermission.OTHERS_EXECUTE)
        }
      Files.setPosixFilePermissions(path, perms)
    } catch (_: UnsupportedOperationException) {
      // ignore: filesystem does not support POSIX permissions
    }
  }

  private fun suggestedPklUrl(version: String): String {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()

    val osPart =
      when {
        os.contains("win") -> "windows"
        os.contains("mac") || os.contains("darwin") -> "macos"
        else -> "linux"
      }

    val archPart =
      when {
        arch.contains("aarch64") || arch.contains("arm64") -> "aarch64"
        else -> "x86_64"
      }

    return "https://github.com/apple/pkl/releases/download/$version/pkl-$osPart-$archPart"
  }

  private class DownloadPklDialog(project: Project) : DialogWrapper(project) {
    lateinit var version: String
    lateinit var destinationPath: String

    init {
      title = "Download Pkl Executable"
      init()
    }

    override fun createCenterPanel(): JComponent = panel {
      val initialDestination = suggestedDownloadPath()
      destinationPath = initialDestination

      lateinit var combo: Cell<ComboBox<String>>
      lateinit var textField: Cell<JBTextField>

      row("Pkl version:".wrapInCode()) {
        combo =
          comboBox(emptyList<String>()).onChanged {
            version = it.selectedItem as String
            destinationPath = "$initialDestination-$version"
            textField.component.text = destinationPath
          }
      }

      row("Destination path:".wrapInCode()) { textField = textField().bindText(::destinationPath) }

      try {
        val versions: List<String> = fetchAvailableVersions()
        if (versions.isNotEmpty()) {
          combo.component.model = DefaultComboBoxModel(versions.toTypedArray())
          combo.component.selectedIndex = 0
          version = versions[0]
          destinationPath = "$initialDestination-$version"
          textField.component.text = destinationPath
        }
      } catch (e: Throwable) {
        setErrorText("Failed to fetch Pkl versions information: $e")
      }
    }

    private fun fetchAvailableVersions(): List<String> =
      runBlocking(Dispatchers.IO) {
        val url = "https://api.github.com/repos/apple/pkl/releases"
        val releasesStr = httpClient.get(url).body<String>()
        val releases: List<GithubRelease> = json.decodeFromString(releasesStr)
        val minorVersions = releases.map { SemVer.parseFromText(it.name)!! }.groupBy { it.minor }
        // Just present the latest patch version for each release
        buildList {
          for ((_, patchVersions) in minorVersions) {
            add(patchVersions.max().toString())
          }
        }
      }

    private fun defaultExecutableName(): String =
      if (System.getProperty("os.name").lowercase().contains("win")) "pkl.exe" else "pkl"

    private fun suggestedDownloadPath(): String =
      Paths.get(
          System.getProperty("user.home"),
          ".pkl",
          "editor-support",
          "bin",
          defaultExecutableName()
        )
        .toString()
  }
}

private fun String.wrapInCode(): String = "<html><code>$this</code></html>"
