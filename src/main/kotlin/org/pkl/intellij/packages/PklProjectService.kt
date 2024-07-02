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
package org.pkl.intellij.packages

import com.intellij.openapi.components.*
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.externalSystem.autoimport.ExternalSystemProjectTracker
import com.intellij.openapi.progress.runBackgroundableTask
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.SmartList
import com.intellij.util.messages.Topic
import com.jetbrains.rd.util.concurrentMapOf
import java.net.URI
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.name
import org.jdom.Element
import org.pkl.intellij.packages.dto.Checksums
import org.pkl.intellij.packages.dto.PackageUri
import org.pkl.intellij.packages.dto.PklProject
import org.pkl.intellij.packages.dto.PklProject.Companion.DerivedProjectMetadata
import org.pkl.intellij.toolchain.PklEvalException
import org.pkl.intellij.toolchain.pklCli
import org.pkl.intellij.util.pklCacheDir

val Project.pklProjectService: PklProjectService
  get() = service()

interface PklProjectListener {
  /** Fired when a project gets added. */
  fun pklProjectsUpdated(service: PklProjectService, projects: Map<String, PklProject>) {}

  /** Fired when a project dependency gets updated (downloaded, or re-synced) */
  fun pklProjectDependenciesUpdated(service: PklProjectService, pklProject: PklProject) {}
}

interface PklProjectSyncListener {
  fun pklProjectSyncStarted()

  fun pklProjectSyncFinished()
}

/** Keeps track of all Pkl projects within an IntelliJ project. */
@Service(Service.Level.PROJECT)
@State(name = "PklProject", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class PklProjectService(private val project: Project) :
  PersistentStateComponent<Element>, ModificationTracker {
  companion object {
    val PKL_PROJECTS_TOPIC: Topic<PklProjectListener> =
      Topic("PklProject changes", PklProjectListener::class.java)
    val PKL_PROJECTS_SYNC_TOPIC: Topic<PklProjectSyncListener> =
      Topic("PklProject sync", PklProjectSyncListener::class.java)

    const val PKL_PROJECT_FILENAME = "PklProject"
    const val PKL_PROJECT_DEPS_FILENAME = "PklProject.deps.json"
    const val UNKNOWN_PACKAGE_URI = "<UNKNOWN>"

    private const val DEPENDENCIES_EXPR =
      """
      new JsonRenderer { omitNullProperties = false }
        .renderValue(new Dynamic {
          projectFileUri = module.projectFileUri
          packageUri = module.package?.uri
          declaredDependencies = module.dependencies.toMap().mapValues((_, value) ->
            if (value is RemoteDependency) value.uri
            else value.package.uri
          )
          evaluatorSettings = module.evaluatorSettings
        })
      """

    private val LOG = logger<PklProjectService>()

    fun getInstance(project: Project): PklProjectService = project.service()
  }

  init {
    registerProjectAware()
  }

  /** All projects, keyed by the project directory. */
  var pklProjects: MutableMap<String, PklProject> = concurrentMapOf()

  private var isOutOfDate: Boolean = false

  private val modificationCount: AtomicLong = AtomicLong(0)

  fun unsyncProjects() {
    pklProjects.clear()
    pklProjectErrors.clear()
    project.messageBus.syncPublisher(PKL_PROJECTS_TOPIC).pklProjectsUpdated(this, pklProjects)
  }

  /**
   * For each project file:
   * 1. Resolve the project (via `pkl project resolve`)
   * 2. Gather the declared dependencies and resolved dependencies (via an expression passed to `pkl
   *    eval`)
   * 3. Download the remote dependencies of each package
   */
  @Suppress("DialogTitleCapitalization")
  fun syncProjects(basePath: Path? = null) =
    runBackgroundableTask("Sync PklProject", project) { _ ->
      project.messageBus.syncPublisher(PKL_PROJECTS_SYNC_TOPIC).pklProjectSyncStarted()
      pklProjects.clear()
      pklProjectErrors.clear()
      val pklProjectFiles = discoverProjectFiles(basePath?.let { localFs.findFileByNioFile(it) })
      if (pklProjectFiles.isEmpty()) {
        project.messageBus.syncPublisher(PKL_PROJECTS_SYNC_TOPIC).pklProjectSyncFinished()
        project.messageBus.syncPublisher(PKL_PROJECTS_TOPIC).pklProjectsUpdated(this, pklProjects)
        return@runBackgroundableTask
      }
      project.pklCli.resolveProject(pklProjectFiles.map { it.parent.toNioPath() })
      val metadatas =
        getProjectMetadatas(pklProjectFiles).map { metadata ->
          val projectFile = localFs.findFileByNioFile(Path.of(URI(metadata.projectFileUri)))!!
          projectFile to metadata
        }
      for ((projectFile, metadata) in metadatas) {
        val key = projectFile.projectKey
        val projectDir = projectFile.parent
        try {
          val resolvedDepsPath = projectDir.findFileByRelativePath(PKL_PROJECT_DEPS_FILENAME)
          val resolvedDeps = resolvedDepsPath?.let { PklProject.loadProjectDeps(it) }
          val pklProject = PklProject(metadata, resolvedDeps)
          pklProjects[key] = pklProject
          pklCacheDir?.let { cacheDir -> doDownloadDependencies(pklProject, cacheDir.toNioPath()) }
        } catch (e: PklEvalException) {
          pklProjectErrors[key] = e
        }
      }
      isOutOfDate = false
      modificationCount.getAndIncrement()
      project.messageBus.syncPublisher(PKL_PROJECTS_SYNC_TOPIC).pklProjectSyncFinished()
      project.messageBus.syncPublisher(PKL_PROJECTS_TOPIC).pklProjectsUpdated(this, pklProjects)
    }

  fun getPklProject(file: VirtualFile): PklProject? =
    if (file.fileSystem !is LocalFileSystem) null else pklProjects[file.projectKey]

  fun getError(file: VirtualFile): Throwable? =
    if (file.fileSystem !is LocalFileSystem) null else pklProjectErrors[file.projectKey]

  private fun doDownloadDependencies(pklProject: PklProject, cacheDir: Path) {
    val packageUris =
      pklProject.projectDeps
        ?.resolvedDependencies
        ?.values
        ?.filterIsInstance<PklProject.Companion.RemoteDependency>()
        ?.map { it.uri.copy(checksums = it.checksums) }
        ?.ifEmpty { null }
        ?: return
    project.pklCli.downloadPackage(
      packageUris,
      cacheDir = cacheDir,
      // All transitive dependencies are already declared in PklProject.deps.json
      noTrasitive = true
    )
  }

  fun downloadDependencies(pklProject: PklProject, cacheDir: Path) {
    runBackgroundableTask("Download project dependencies") {
      doDownloadDependencies(pklProject, cacheDir)
      modificationCount.getAndIncrement()
      project.messageBus
        .syncPublisher(PKL_PROJECTS_TOPIC)
        .pklProjectDependenciesUpdated(this, pklProject)
    }
  }

  fun hasErrors(): Boolean = pklProjectErrors.isNotEmpty()

  override fun getState(): Element {
    val state = Element("state")
    for ((_, project) in pklProjects) {
      val projectElement = Element("pkl-project")
      projectElement.setAttribute("project-file-uri", project.metadata.projectFileUri)
      projectElement.setAttribute(
        "package-uri",
        project.metadata.packageUri?.toString() ?: UNKNOWN_PACKAGE_URI
      )
      for ((key, value) in project.metadata.declaredDependencies) {
        val dependency =
          Element("declared-dependency").apply {
            setAttribute("name", key)
            setAttribute("uri", value.toString())
          }
        projectElement.addContent(dependency)
      }
      project.projectDeps?.let { deps ->
        for ((canonicalName, resolvedDep) in deps.resolvedDependencies) {
          val elem =
            Element("resolved-dependency").apply {
              setAttribute("canonical-name", canonicalName)
              setAttribute("type", resolvedDep.type.strValue)
              setAttribute("uri", resolvedDep.uri.toString())
              if (resolvedDep is PklProject.Companion.LocalDependency) {
                setAttribute("path", resolvedDep.path)
              } else {
                resolvedDep as PklProject.Companion.RemoteDependency
                setAttribute("checksum-sha256", resolvedDep.checksums?.sha256)
              }
            }
          projectElement.addContent(elem)
        }
      }
      val evaluatorSettings =
        Element("evaluator-settings").apply {
          project.metadata.evaluatorSettings.moduleCacheDir?.let {
            setAttribute("module-cache-dir", it)
          }
        }
      projectElement.addContent(evaluatorSettings)
      state.addContent(projectElement)
    }
    return state
  }

  override fun loadState(state: Element) {
    this.pklProjects =
      concurrentMapOf<String, PklProject>().apply {
        val elems = state.getChildren("pkl-project")
        for (elem in elems) {
          val project = loadProjectFromState(elem)
          put(project.metadata.projectFileUri, project)
        }
      }
    project.messageBus.syncPublisher(PKL_PROJECTS_TOPIC).pklProjectsUpdated(this, pklProjects)
  }

  private fun loadProjectFromState(elem: Element): PklProject {
    // attribute package-uri got added to workspace state in pkl-intellij 0.28. If not present, show
    // as out-of-date,
    // which causes a banner to appear (see PklSyncProjectNotificationProvider)
    val packageUriValue = elem.getAttributeValue("package-uri")
    if (packageUriValue == null) {
      isOutOfDate = true
    }
    val packageUri =
      packageUriValue?.let { if (it == UNKNOWN_PACKAGE_URI) null else PackageUri.create(it) }
    val projectFileUri = elem.getAttributeValue("project-file-uri")
    val declaredDependencies =
      buildMap<String, PackageUri> {
        for (dependencyElem in elem.getChildren("declared-dependency")) {
          val name = dependencyElem.getAttributeValue("name")
          val uri = PackageUri.create(dependencyElem.getAttributeValue("uri")) ?: continue
          put(name, uri)
        }
      }
    val resolvedDependencies =
      buildMap<String, PklProject.Companion.ResolvedDependency> {
        for (resolvedElem in elem.getChildren("resolved-dependency")) {
          val canonicalName = resolvedElem.getAttributeValue("canonical-name")
          val type = resolvedElem.getAttributeValue("type")
          val uriStr = resolvedElem.getAttributeValue("uri")
          val uri = PackageUri.create(uriStr)
          if (uri == null) {
            LOG.warn("Invalid package URI from state: $uriStr")
            return@buildMap
          }
          val dependency =
            if (type == "remote") {
              val sha256Checksum = resolvedElem.getAttributeValue("checksum-sha256")
              if (sha256Checksum == null) {
                LOG.warn("Missing checksum value in remote dependency")
                return@buildMap
              }
              PklProject.Companion.RemoteDependency(uri, Checksums(sha256Checksum))
            } else PklProject.Companion.LocalDependency(uri, resolvedElem.getAttributeValue("path"))
          put(canonicalName, dependency)
        }
      }
    val evaluatorSettings =
      elem.getChild("evaluator-settings")?.let { settings ->
        val moduleCacheDir = settings.getAttributeValue("module-cache-dir")
        PklProject.Companion.EvaluatorSettings(moduleCacheDir)
      }
        ?: PklProject.Companion.EvaluatorSettings()
    val metadata =
      DerivedProjectMetadata(projectFileUri, packageUri, declaredDependencies, evaluatorSettings)
    val projectDeps =
      if (resolvedDependencies.isNotEmpty())
        PklProject.Companion.ProjectDeps(1, resolvedDependencies)
      else null
    return PklProject(metadata, projectDeps)
  }

  private fun discoverProjectFiles(projectDir: VirtualFile?): List<VirtualFile> {
    val resolvedProjectDir = projectDir ?: project.guessProjectDir() ?: return emptyList()
    val fut = CompletableFuture<List<VirtualFile>>()
    runBackgroundableTask("Discover PklProject files") {
      val result = SmartList<VirtualFile>()
      Files.walkFileTree(
        resolvedProjectDir.toNioPath(),
        object : SimpleFileVisitor<Path>() {
          override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            if (file.name == PKL_PROJECT_FILENAME) {
              result.add(LocalFileSystem.getInstance().findFileByNioFile(file))
            }
            return FileVisitResult.CONTINUE
          }
        }
      )
      fut.complete(result)
    }
    return fut.get()
  }

  private fun registerProjectAware() {
    if (project.isDefault) return
    val projectAware = PklExternalSystemProjectAware(project)
    val projectTracker = ExternalSystemProjectTracker.getInstance(project)
    projectTracker.register(projectAware)
  }

  /**
   * Cached errors encountered when attempting to load a project.
   *
   * We show these errors as notifications (see
   * [org.pkl.intellij.notification.PklSyncProjectNotificationProvider])
   */
  private var pklProjectErrors: MutableMap<String, Throwable> = concurrentMapOf()

  private val VirtualFile.projectKey
    get(): String = if (name == PKL_PROJECT_FILENAME) url else "$url/PklProject"

  private fun getProjectMetadatas(projectFiles: List<VirtualFile>): List<DerivedProjectMetadata> {
    val output =
      project.pklCli.eval(
        projectFiles.map { it.toNioPath().toString() },
        title = "get project metadata",
        expression = DEPENDENCIES_EXPR,
        moduleOutputSeparator = ", "
      )
    return PklProject.parseMetadata("[$output]")
  }

  override fun getModificationCount(): Long = modificationCount.get()

  fun isOutOfDate(): Boolean {
    return isOutOfDate
  }
}
