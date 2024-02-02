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
package org.pkl.intellij.toolchain

import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.ExecutorRegistry
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputType
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.progress.runBackgroundableTask
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import org.pkl.intellij.packages.dto.PackageUri
import org.pkl.intellij.settings.pklSettings

val Project.pklCli
  get() = PklCli(this)

/**
 * Handler for calling into the pkl CLI.
 *
 * All methods must be run in a background thread. The simplest way to do this is to call the CLI
 * within [runBackgroundableTask].
 */
@OptIn(ExperimentalStdlibApi::class)
class PklCli(private val proj: Project) {
  fun isAvailable(): Boolean = pathToPklExecutable() != null

  fun resolveProject(projectDirs: List<Path>): String {
    val normalizedDirs =
      projectDirs.map { it.normalize().toAbsolutePath().toString() }.toTypedArray()
    return executeCommand(listOf("project", "resolve", *normalizedDirs), "pkl project resolve")
  }

  fun downloadPackage(
    packages: List<PackageUri>,
    cacheDir: Path? = null,
    noTrasitive: Boolean = false
  ): String {
    val args = buildList {
      add("download-package")
      if (cacheDir != null) {
        add("--cache-dir")
        add(cacheDir.toAbsolutePath().toString())
      }
      if (noTrasitive) {
        add("--no-transitive")
      }
      addAll(packages.map { it.toStringWithChecksum() })
    }
    return executeCommand(args, "download packages")
  }

  fun eval(
    moduleUris: List<String>,
    title: String?,
    expression: String? = null,
    moduleOutputSeparator: String? = null
  ): String {
    val args = buildList {
      add("eval")
      if (expression != null) {
        add("-x")
        add(expression)
      }
      if (moduleOutputSeparator != null) {
        add("--module-output-separator")
        add(moduleOutputSeparator)
      }
      for (uri in moduleUris) {
        add(uri)
      }
    }
    return executeCommand(args, title)
  }

  private fun executeCommand(args: List<String>, title: String?): String {
    val fut = CompletableFuture<Int>()
    val pklExecutablePath =
      pathToPklExecutable() ?: throw PklEvalException("pkl", "Path to Pkl executable not found")
    val command =
      GeneralCommandLine(pklExecutablePath, *args.toTypedArray())
        .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
    val stdout = StringBuilder()
    val stderr = StringBuilder()
    val disposable = Disposer.newDisposable()
    val profile = PklRunProfile(command, title ?: command.commandLineString)
    val executor = ExecutorRegistry.getInstance().getExecutorById(DefaultRunExecutor.EXECUTOR_ID)!!
    val environment = ExecutionEnvironmentBuilder.create(proj, executor, profile).build()
    proj.messageBus
      .connect(disposable)
      .subscribe(
        ExecutionManager.EXECUTION_TOPIC,
        object : ExecutionListener {
          override fun processStarting(
            executorId: String,
            env: ExecutionEnvironment,
            handler: ProcessHandler
          ) {
            if (env !== environment) return
            handler.addProcessListener(
              object : ProcessAdapter() {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                  when (outputType) {
                    ProcessOutputType.STDOUT -> stdout.append(event.text)
                    ProcessOutputType.STDERR -> stderr.append(event.text)
                  }
                }
              }
            )
          }

          override fun processTerminated(
            executorId: String,
            env: ExecutionEnvironment,
            handler: ProcessHandler,
            exitCode: Int
          ) {
            if (env !== environment) return
            fut.complete(exitCode)
          }
        }
      )
    runInEdt {
      try {
        environment.runner.execute(environment)
      } catch (e: ExecutionException) {
        fut.completeExceptionally(e)
      }
    }
    try {
      val exitCode = fut.get()
      return if (exitCode == 0) stdout.toString()
      else throw PklEvalException(command.commandLineString, stderr.toString())
    } catch (e: java.util.concurrent.ExecutionException) {
      throw e.cause!!
    } finally {
      Disposer.dispose(disposable)
    }
  }

  private val String.ifExecutable: String?
    get() {
      return if (Files.isExecutable(Path.of(this))) this else null
    }

  private fun systemPklPath(): String? =
    // common locations for Pkl CLI
    "/opt/homebrew/bin/pkl".ifExecutable
      ?: "/opt/brew/bin/pkl".ifExecutable ?: "/usr/local/bin/pkl".ifExecutable

  private fun pathToPklExecutable(): String? =
    proj.pklSettings.state.pklPath.ifEmpty { systemPklPath() }
}
