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
package org.pkl.intellij.externalreader

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.PathMacroManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.getProjectCacheFileName
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.io.createParentDirectories
import java.io.IOException
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.io.path.absolutePathString
import org.pkl.intellij.packages.PklProjectListener
import org.pkl.intellij.packages.PklProjectService
import org.pkl.intellij.packages.dto.PklProject
import org.pkl.intellij.packages.localFs
import org.pkl.intellij.packages.pklProjectService
import org.pkl.intellij.settings.PklSettingsChangedListener
import org.pkl.intellij.settings.PklSettingsComponent
import org.pkl.intellij.settings.pklSettings
import org.pkl.intellij.settings.toExternalReaders
import org.pkl.intellij.util.GlobResolver
import org.pkl.intellij.util.decodePath
import org.pkl.messaging.PathElement
import org.pkl.messaging.externalreader.ExternalReader
import org.pkl.messaging.externalreader.ExternalReaderProcess
import org.pkl.messaging.externalreader.ExternalReaderProcessException
import org.pkl.messaging.externalreader.ModuleReaderSpec

val Project.pklExternalReaderService: PklExternalReaderService
  get() = service()

/**
 * Resolves modules from external readers, caching what they return on disk (and in the VFS).
 *
 * External readers can come from the IDE settings, or be declared by a `PklProject` (project-level
 * readers take precedence).
 *
 * Two projects may declare the same scheme but use different executables, so everything that
 * depends on the reader is tracked per reader (see [ReaderState]):
 *
 * * Cache directory layout: `<cacheDir>/<reader id>/<scheme>/<authority>/<path>`.
 * * The reader process is shared between all schemes/projects that have an equal [ExternalReader].
 */
@Service(Service.Level.PROJECT)
class PklExternalReaderService(private val project: Project) : Disposable {
  companion object {
    val cacheDirBase: Path = Path.of(PathManager.getSystemPath()).resolve("pkl-external-readers")

    const val OPAQUE_DIR = "OPAQUE"
    const val NO_AUTHORITY = "NO_AUTHORITY"

    private const val CHECK_TIMEOUT_SECONDS = 10L
  }

  private val cacheDir: Path by lazy {
    val cacheFileName = project.getProjectCacheFileName()
    cacheDirBase.resolve(cacheFileName)
  }

  init {
    // blow away any files that might still exist from the last session
    FileUtil.delete(cacheDir)
    cacheDir.createParentDirectories()
  }

  private data class ReaderKey(val scheme: String, val reader: ExternalReader)

  /** Reader states, by reader configuration and scheme. */
  private val states: MutableMap<ReaderKey, ReaderState> = ConcurrentHashMap()

  /** Reader states, by the first two segments of their cache directory (`<id>/<scheme>`). */
  private val statesByDir: MutableMap<String, ReaderState> = ConcurrentHashMap()

  /**
   * Reader processes, keyed by reader configuration.
   *
   * The same configuration (across schemes and projects) shares the same process.
   */
  private val processes: MutableMap<ExternalReader, ExternalReaderProcess> = ConcurrentHashMap()

  /** What decides which readers exist; if it changes, everything cached from readers is stale. */
  private data class ReaderConfig(
    val ide: Map<String, ExternalReader>,
    val projects:
      Map<
        String,
        Pair<
          Map<String, PklProject.Companion.ExternalReader>?,
          Map<String, PklProject.Companion.ExternalReader>?,
        >,
      >,
  )

  private fun currentReaderConfig(): ReaderConfig =
    ReaderConfig(
      project.pklSettings.state.externalModuleReaders.toExternalReaders(),
      project.pklProjectService.pklProjects.mapValues { (_, pklProject) ->
        val metadata = pklProject.metadata
        metadata.evaluatorSettings.externalModuleReaders to
          metadata.resolvedEvaluatorSettings?.externalModuleReaders
      },
    )

  @Volatile private var lastReaderConfig: ReaderConfig = currentReaderConfig()

  init {
    val connection = project.messageBus.connect(this)
    connection.subscribe(
      PklSettingsComponent.PKL_SETTINGS_CHANGED_TOPIC,
      PklSettingsChangedListener { invalidateIfReadersChanged() },
    )
    connection.subscribe(
      PklProjectService.PKL_PROJECTS_TOPIC,
      object : PklProjectListener {
        override fun pklProjectsUpdated(
          service: PklProjectService,
          projects: Map<String, PklProject>,
        ) {
          invalidateIfReadersChanged()
        }
      },
    )
  }

  /**
   * Invalidates everything if the readers that are configured (in the settings, or by a synced
   * `PklProject`) are different from what they were when last checked. Settings fire their change
   * notification for unrelated changes too, so this avoids needless invalidation.
   */
  private fun invalidateIfReadersChanged() {
    val config = currentReaderConfig()
    if (config == lastReaderConfig) return
    lastReaderConfig = config
    invalidate()
  }

  /**
   * Forgets everything that was learned from external readers: stops their processes, discards
   * their specs and listings, deletes the cached modules, and re-resolves what depended on them.
   *
   * Modules are fetched again on demand. Runs in the background; safe to call from any thread.
   */
  fun invalidate() {
    doAndRefresh {
      val oldProcesses = processes.values.toList()
      processes.clear()
      states.clear()
      statesByDir.clear()
      for (process in oldProcesses) {
        runCatching { process.close() }
      }
      deleteCachedFiles()
      createIdeLevelRoots()
    }
  }

  /** Deletes the cached modules through the VFS, so that the VFS doesn't hold on to stale files. */
  private fun deleteCachedFiles() {
    WriteAction.computeAndWait<Unit, IOException> {
      localFs.findFileByNioFile(cacheDir)?.children?.forEach { it.delete(this) }
    }
  }

  override fun dispose() {
    for (process in processes.values) {
      runCatching { process.close() }
    }
    processes.clear()
    FileUtil.delete(cacheDir)
  }

  private fun ExternalReader.expandMacros(): ExternalReader {
    val macros = PathMacroManager.getInstance(project)
    return ExternalReader(
      macros.expandPath(executable()),
      arguments()?.map { macros.expandPath(it) },
      workingDir()?.let { macros.expandPath(it) },
    )
  }

  /** The configured reader for [scheme]; project-level readers take precedence. */
  private fun configuredReader(scheme: String, context: PklProject?): ExternalReader? {
    val projectReader = context?.let { projectReader(scheme, it) }
    return (projectReader
        ?: project.pklSettings.state.externalModuleReaders[scheme]?.toExternalReader())
      ?.expandMacros()
  }

  /**
   * A reader declared by [context].
   *
   * The working directory is taken from `resolvedEvaluatorSettings` if possible (Pkl 0.32+),
   * because it was resolved in-language and is therefore absolute. Otherwise, relative paths (the
   * working directory, and an executable that's a relative path) are resolved against the project's
   * directory.
   *
   * This behavior deviates from Pkl < 0.32, which resolves relative paths against PWD. However, the
   * old behavior is ostensibly incorrect, and there's no way to figure out what the intended PWD is
   * supposed to be.
   */
  private fun projectReader(
    scheme: String,
    context: PklProject,
    kind: ReaderKind = ReaderKind.MODULE,
  ): ExternalReader? {
    val metadata = context.metadata
    val declared = kind.readers(metadata.evaluatorSettings)?.get(scheme) ?: return null
    val resolved = metadata.resolvedEvaluatorSettings?.let { kind.readers(it) }?.get(scheme)
    val projectDir = context.projectFile.parent
    return ExternalReader(
      resolved?.executable ?: resolveAgainst(projectDir, declared.executable, isExecutable = true),
      resolved?.arguments ?: declared.arguments,
      resolved?.workingDir
        ?: declared.workingDir?.let { resolveAgainst(projectDir, it, isExecutable = false) },
    )
  }

  /**
   * Resolves [path] against [dir] if it's a relative path.
   *
   * An executable without a path separator (e.g. `my-reader`) is a command that's looked up on the
   * `PATH`, not a relative path, so it's left alone. Paths that start with an IntelliJ path macro
   * (`$PROJECT_DIR$`) are also left alone; they get expanded later.
   */
  private fun resolveAgainst(dir: Path?, path: String, isExecutable: Boolean): String {
    if (dir == null || path.startsWith("$")) return path
    if (isExecutable && !path.contains('/') && !path.contains('\\')) return path
    return runCatching {
        val asPath = Path.of(path)
        if (asPath.isAbsolute) path else dir.resolve(asPath).normalize().toString()
      }
      .getOrDefault(path)
  }

  private fun stateFor(scheme: String, context: PklProject?): ReaderState? {
    val reader = configuredReader(scheme, context) ?: return null
    return states.computeIfAbsent(ReaderKey(scheme, reader)) {
      ReaderState(scheme, reader, processes, cacheDir).also { statesByDir["${it.id}/$scheme"] = it }
    }
  }

  /** The state of the reader that a cached file (or directory) belongs to. */
  private fun stateFor(file: VirtualFile): ReaderState? {
    val relative = relativeToCacheDir(file) ?: return null
    if (relative.size < 2) return null
    return statesByDir["${relative[0]}/${relative[1]}"]
  }

  private fun relativeToCacheDir(file: VirtualFile): List<String>? {
    val path = file.fileSystem.getNioPath(file) ?: return null
    if (!path.startsWith(cacheDir)) return null
    return cacheDir.relativize(path).map { it.toString() }
  }

  /** The original external reader URI given the file location on disk. */
  fun originalUri(file: VirtualFile): URI? {
    val relative = relativeToCacheDir(file) ?: return null
    // <reader id>/<scheme>/...
    if (relative.size < 2) return null
    val scheme = relative[1]
    stateFor(file)?.urisByCachePath?.get(file.toNioPath())?.let {
      return it
    }
    if (relative.size == 2) {
      return URI("$scheme:/")
    }
    if (relative[2] == OPAQUE_DIR) {
      // the directory itself stands for the list of all modules; files below it are opaque URIs
      if (relative.size == 3) return URI("$scheme:dummy")
      return URI(scheme, decodePath(relative.drop(3).joinToString("/")), null)
    }
    val host = relative[2].let { if (it == NO_AUTHORITY) null else it }
    val rest = "/" + decodePath(relative.drop(3).joinToString("/"))
    return URI(scheme, host, rest, null)
  }

  /**
   * The `<cacheDir>/<reader id>/<scheme>/<authority>` directories; these act as the "roots" of
   * external modules.
   */
  fun externalRoots(): List<VirtualFile> {
    val root = localFs.findFileByNioFile(cacheDir) ?: return emptyList()
    return root.children
      .filter { it.isDirectory }
      .flatMap { it.children.filter { scheme -> scheme.isDirectory } }
      .flatMap { it.children.filter { authority -> authority.isDirectory } }
  }

  /**
   * Returns the `<cacheDir>/<reader id>/<scheme>` directory of the reader that [file] was read by,
   * or `null` if it doesn't exist yet (or [file] isn't from an external reader).
   *
   * Creates the root out-of-band if it doesn't exist yet.
   */
  fun root(file: VirtualFile): VirtualFile? {
    val dir = stateFor(file)?.schemeDir ?: return null
    val existing = localFs.findFileByNioFile(dir)
    if (existing != null) return existing
    doAndRefresh { createDirectory(dir) }
    return null
  }

  /** Creates [dir] through the VFS. Must not be called while holding the read lock. */
  private fun createDirectory(dir: Path): VirtualFile =
    // computeAndWait runs the write action on the EDT; listeners such as VcsVFSListener assert
    // that.
    WriteAction.computeAndWait<VirtualFile, IOException> {
      VfsUtil.createDirectoryIfMissing(localFs, dir.absolutePathString())
        ?: throw IOException("Failed to create directory $dir")
    }

  /**
   * A display name like `mylib://host` for a directory returned from [externalRoots], else `null`.
   */
  fun rootPresentableName(dir: VirtualFile): String? {
    val relative = relativeToCacheDir(dir) ?: return null
    if (relative.size != 3) return null
    val scheme = relative[1]
    val authority = relative[2].let { if (it == NO_AUTHORITY || it == OPAQUE_DIR) null else it }
    return if (authority == null) "$scheme:" else "$scheme://$authority"
  }

  /** Tells if this file is a module from an external reader. */
  fun handlesFile(file: VirtualFile): Boolean =
    file.fileSystem.getNioPath(file)?.startsWith(cacheDir) == true

  /** Tells if there's an external reader for [scheme], as seen from a module in [context]. */
  fun handlesScheme(scheme: String, context: PklProject?): Boolean =
    configuredReader(scheme, context) != null

  /** Tells if [scheme]'s reader uses non-hierarchical URIs. May talk to the reader process. */
  fun isOpaqueScheme(scheme: String, context: PklProject?): Boolean =
    stateFor(scheme, context)?.isOpaque() == true

  /**
   * Checks the reader that [context] declares for [scheme], using the shared reader process (so it
   * doesn't start a new process every time). Returns `null` if [context] doesn't declare one, e.g.
   * because it wasn't synced yet.
   *
   * Blocks for up to [CHECK_TIMEOUT_SECONDS] on the reader process.
   */
  fun checkProjectReader(scheme: String, kind: ReaderKind, context: PklProject): ReaderCheck? {
    val reader = projectReader(scheme, context, kind) ?: return null
    return checkReader(reader, scheme, kind, shared = true)
  }

  /**
   * Starts a process for [reader] (a configuration as entered by the user, e.g. in the settings
   * panel; IntelliJ path macros are expanded), asks whether it supports [scheme], and stops it
   * again.
   *
   * If [shared], the process is the one that's shared with module resolution; it's kept running,
   * unless it turned out to be broken (so that a later check starts it anew).
   *
   * Blocks for up to [CHECK_TIMEOUT_SECONDS] on the reader process.
   */
  fun checkReader(
    reader: ExternalReader,
    scheme: String,
    kind: ReaderKind,
    shared: Boolean = false,
  ): ReaderCheck {
    val expanded = reader.expandMacros()
    val process =
      if (shared) processes.computeIfAbsent(expanded) { ExternalReaderProcess.of(it) }
      else ExternalReaderProcess.of(expanded)
    var result: ReaderCheck? = null
    try {
      // The reader talks to a child process, so give up on it if it never answers.
      val answer =
        executerService.submit<Boolean> {
          when (kind) {
            ReaderKind.MODULE -> process.getModuleReaderSpec(scheme)
            ReaderKind.RESOURCE -> process.getResourceReaderSpec(scheme)
          } != null
        }
      result =
        try {
          if (answer.get(CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) ReaderCheck.Supported
          else ReaderCheck.UnsupportedScheme
        } catch (_: TimeoutException) {
          answer.cancel(true)
          ReaderCheck.NotAReader("it didn't answer within $CHECK_TIMEOUT_SECONDS seconds")
        } catch (e: ExecutionException) {
          classifyFailure(e.cause ?: e)
        }
      return result
    } finally {
      val isBroken = result is ReaderCheck.CannotStart || result is ReaderCheck.NotAReader
      if (!shared || (isBroken && processes.remove(expanded, process))) {
        process.close()
      }
    }
  }

  private fun classifyFailure(failure: Throwable): ReaderCheck {
    // Failing to start the process is reported as an `ExternalReaderProcessException` that wraps
    // the `IOException` from `ProcessBuilder.start`.
    val startFailure =
      generateSequence(failure) { it.cause }
        .filterIsInstance<ExternalReaderProcessException>()
        .firstOrNull { it.cause is IOException }
    if (startFailure != null) {
      return ReaderCheck.CannotStart(startFailure.cause?.message ?: startFailure.message ?: "")
    }
    val cause = generateSequence(failure) { it.cause }.last()
    return ReaderCheck.NotAReader(cause.message ?: cause.toString())
  }

  /** Lists every module of a scheme with non-hierarchical URIs. */
  fun listOpaqueModules(scheme: String, context: PklProject?): List<URI> =
    stateFor(scheme, context)?.listOpaqueModules() ?: emptyList()

  /** The specs of all readers that [context] (and the IDE settings) configure, by scheme. */
  fun readerSpecs(context: PklProject?): Map<String, ModuleReaderSpec> {
    val schemes = buildSet {
      addAll(project.pklSettings.state.externalModuleReaders.keys)
      context?.metadata?.evaluatorSettings?.externalModuleReaders?.keys?.let { addAll(it) }
    }
    return buildMap {
      for (scheme in schemes) {
        stateFor(scheme, context)?.spec()?.let { put(scheme, it) }
      }
    }
  }

  private val executerService = AppExecutorUtil.getAppExecutorService()

  /**
   * Creates the root directories of IDE-level readers. (Project-level readers get theirs on
   * demand.)
   */
  private fun createIdeLevelRoots() {
    for (scheme in project.pklSettings.state.externalModuleReaders.keys) {
      runCatching { stateFor(scheme, null)?.let { createDirectory(it.schemeDir) } }
    }
  }

  init {
    executerService.execute { createIdeLevelRoots() }
  }

  /**
   * Returns the cached file for [uri], or `null` if it hasn't been fetched yet.
   *
   * This is safe to call from a read action (e.g. reference resolution): it never blocks on the
   * reader process and never takes the write lock. On a cache miss, the module is fetched on a
   * background thread; once it's available, resolve caches are dropped and highlighting is
   * restarted so that references pointing at [uri] resolve.
   */
  fun resolve(uri: URI, context: PklProject?): VirtualFile? {
    val state = stateFor(uri.scheme ?: return null, context) ?: return null
    return resolve(state, uri)
  }

  /**
   * Resolves [target], a relative URI (e.g. `foo/bar.pkl` or `/foo/bar.pkl`) that's imported from
   * [sourceFile], which is a module that's cached from an external reader.
   *
   * The target is resolved against the module's original URI, and read through the same reader as
   * the module (which can be a project-level reader that isn't visible from the source file, as the
   * cache isn't part of any project). Like [resolve], it's fetched in the background if it isn't
   * cached yet.
   */
  fun resolveRelative(sourceFile: VirtualFile, target: URI): VirtualFile? {
    val state = stateFor(sourceFile) ?: return null
    val base = originalUri(sourceFile) ?: return null
    // opaque URIs have no hierarchy to resolve against
    if (base.isOpaque) return null
    return resolve(state, base.resolve(target))
  }

  private fun resolve(state: ReaderState, uri: URI): VirtualFile? {
    val existingFile = localFs.findFileByNioFile(state.cachePath(uri))
    if (existingFile != null) {
      return existingFile
    }
    if (uri !in state.failed && state.inFlight.add(uri)) {
      doAndRefresh { state.fetch(uri) }
    }
    return null
  }

  /** Incremented whenever background work changed the cache; use as a cached-value dependency. */
  val modificationTracker = SimpleModificationTracker()

  /**
   * Resolves a glob [uri] (e.g. `mylib:*.pkl`) against the cache.
   *
   * Directories are fetched on demand and in the background, so the result may be incomplete at
   * first; [modificationTracker] changes when more of the tree has arrived.
   */
  fun resolveGlob(uri: URI, isPartial: Boolean, context: PklProject?): GlobResolver.GlobResult? {
    val state = stateFor(uri.scheme ?: return null, context) ?: return null
    val rootPath =
      state.schemeDir.resolve(if (uri.isOpaque) OPAQUE_DIR else uri.authority ?: NO_AUTHORITY)
    val root = localFs.findFileByNioFile(rootPath)
    if (root == null) {
      doAndRefresh { createDirectory(rootPath) }
      return GlobResolver.GlobResult(emptyList(), false)
    }
    val listChildren = { dir: VirtualFile ->
      ensureListed(dir)
      dir.children
    }
    return if (uri.isOpaque) {
      // opaque URIs are a flat namespace; the glob applies to the whole scheme-specific part
      GlobResolver.resolveRelativeGlob(
        root,
        uri.schemeSpecificPart,
        isPartial,
        project,
        listChildren,
      )
    } else {
      val path = uri.path ?: return null
      GlobResolver.resolveAbsoluteGlob(root, path, isPartial, project, listChildren)
    }
  }

  private fun doAndRefresh(onDone: (() -> Unit)? = null, block: () -> Unit) {
    executerService.execute {
      try {
        block()
      } catch (e: Exception) {
        // Not refreshing here keeps a failure from re-triggering itself in a loop: re-resolving
        // would just attempt the same thing again right away.
        thisLogger().warn("Failed to update external reader cache", e)
        return@execute
      }
      modificationTracker.incModificationCount()
      if (project.isDisposed) return@execute
      ApplicationManager.getApplication()
        .invokeLater(
          {
            PsiManager.getInstance(project).dropResolveCaches()
            DaemonCodeAnalyzer.getInstance(project).restart()
            onDone?.invoke()
          },
          project.disposed,
        )
    }
  }

  /**
   * Asks the reader for [baseUri]'s children, caching the result for the session.
   *
   * Blocks on the reader process; call from a background thread (completion is fine). Doesn't touch
   * the VFS or take any write lock.
   */
  fun listElements(baseUri: URI, context: PklProject?): List<PathElement> {
    val state = stateFor(baseUri.scheme ?: return emptyList(), context) ?: return emptyList()
    return state.listElements(baseUri)
  }

  /**
   * Makes sure that the cache directory [dir] contains everything the reader has at that level, so
   * that the VFS (and therefore e.g. the navigation bar's child popups) can show it.
   *
   * The reader is the one that [dir] was cached from.
   */
  fun ensureListed(dir: VirtualFile) {
    if (!dir.isDirectory) return
    val state = stateFor(dir) ?: return
    val originalUri = originalUri(dir) ?: return
    if (!state.wasChildrenListed.add(originalUri)) return
    // `URI.resolve` replaces the last path segment unless the base ends with a slash.
    val base =
      if (originalUri.path?.endsWith("/") != false) originalUri
      else URI(originalUri.scheme, originalUri.authority, originalUri.path + "/", null)
    doAndRefresh {
      // (checking the spec talks to the reader process, so this has to happen in the background)
      if (state.isOpaque()) {
        // flat namespace: the listing is every module
        for (uri in state.listOpaqueModules()) {
          if (localFs.findFileByNioFile(state.cachePath(uri)) == null) state.fetch(uri)
        }
        return@doAndRefresh
      }
      for (element in state.listElements(base)) {
        val uri = base.resolve(element.name)
        if (element.isDirectory) {
          createDirectory(state.cachePath(uri))
        } else if (localFs.findFileByNioFile(state.cachePath(uri)) == null) {
          state.fetch(uri)
        }
      }
    }
  }
}
