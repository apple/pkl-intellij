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
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.UserDataHolder
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.*
import com.intellij.openapi.vfs.impl.http.HttpVirtualFile
import com.intellij.openapi.vfs.impl.http.RemoteFileState
import com.intellij.psi.*
import com.intellij.util.IncorrectOperationException
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.ParameterizedCachedValueProvider
import com.intellij.psi.util.PsiModificationTracker
import java.net.URI
import java.net.URISyntaxException
import org.pkl.intellij.PklLanguage
import org.pkl.intellij.cacheKeyService
import org.pkl.intellij.packages.PklProjectService.Companion.PKL_PROJECT_FILENAME
import org.pkl.intellij.packages.dto.PackageUri
import org.pkl.intellij.packages.dto.PklProject
import org.pkl.intellij.packages.pklProjectService
import org.pkl.intellij.util.*

/** A reference within a module URI. May reference a package/directory or file. */
class PklModuleUriReference(uri: PklModuleUri, rangeInElement: TextRange) :
  PsiReferenceBase<PklModuleUri>(uri, rangeInElement),
  PsiPolyVariantReference,
  UserDataHolder,
  PklReference {

  val moduleUri: String = element.stringConstant.content.text

  private val project = uri.project

  private val targetUri: String =
    moduleUri.substring(0, rangeInElement.endOffset - element.stringConstant.stringStart.textLength)

  private class UriResolveResult(private val element: PsiElement) : ResolveResult {
    override fun getElement(): PsiElement = element

    override fun isValidResult(): Boolean = true
  }

  private val isGlobImport = (uri.parent as? PklImportBase)?.isGlob ?: false

  override fun resolveContextual(context: PklProject?): PsiElement? =
    when {
      isGlobImport && GlobResolver.isRegularPathPart(targetUri) ->
        resolve(
          targetUri,
          moduleUri,
          element.containingFile,
          element.enclosingModule,
          project,
          context
        )
      isGlobImport -> null
      else ->
        resolve(
          targetUri,
          moduleUri,
          element.containingFile,
          element.enclosingModule,
          project,
          context
        )
    }

  override fun resolve(): PsiElement? = resolveContextual(null)

  fun resolveGlob(context: PklProject?): List<PsiFileSystemItem>? {
    return resolveGlob(targetUri, moduleUri, element, context)
  }

  override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
    if (isGlobImport) {
      return resolveGlob(null)?.map(::UriResolveResult)?.toTypedArray() ?: emptyArray()
    }
    return resolve(
        targetUri,
        moduleUri,
        element.containingFile,
        element.enclosingModule,
        project,
        null
      )
      ?.let { arrayOf(UriResolveResult(it)) }
      ?: emptyArray()
  }

  override fun handleElementRename(newElementName: String): PsiElement {
    val stringConstant = element.stringConstant
    val stringStart = stringConstant.stringStart
    val stringContent = stringConstant.content
    val rangeInContent = rangeInElement.shiftLeft(stringStart.textLength)
    val newText =
      stringContent.text.replaceRange(
        rangeInContent.startOffset,
        rangeInContent.endOffset,
        newElementName
      )
    val newContent = PklPsiFactory.createStringContent(newText, stringStart.text, element.project)
    stringContent.node.replaceAllChildrenToChildrenOf(newContent.node)
    return element
  }

  /**
   * Called by IntelliJ's refactoring engine when a referenced file/directory is moved.
   * Recomputes the URI to point at [newTarget]'s new location.
   *
   * - For `file:` URIs: replaces with the new absolute VFS URL.
   * - For relative URIs: if the source file is **outside** the PKL project that owns the moved
   *   file, and the source's PKL project declares that project as a local dependency, the URI is
   *   rewritten as `@<depName>/<path-from-dep-root>`. Otherwise falls back to a plain relative
   *   path.
   * - Other schemes (`pkl:`, `package:`, `https:`, `modulepath:`) are unaffected by file moves.
   */
  @Throws(IncorrectOperationException::class)
  override fun bindToElement(newTarget: PsiElement): PsiElement {
    val targetVirtualFile =
      when (newTarget) {
        is PsiFile -> newTarget.virtualFile
        is PsiDirectory -> newTarget.virtualFile
        else -> null
      } ?: return element

    val sourceVirtualFile = element.containingFile.originalFile.virtualFile ?: return element
    val currentUri = element.stringConstant.content.text

    val newUri =
      when {
        // Absolute file: URI — replace with the new absolute VFS URL.
        currentUri.startsWith("file:", ignoreCase = true) -> targetVirtualFile.url
        // Relative URI (no scheme) — prefer @dep/path when crossing project boundaries.
        !currentUri.contains(':') ->
          computeNewRelativeUri(sourceVirtualFile, targetVirtualFile) ?: return element
        // modulepath:, package:, pkl:, https: — not affected by moves; skip.
        else -> return element
      }

    val stringConstant = element.stringConstant
    val newContent =
      PklPsiFactory.createStringContent(newUri, stringConstant.stringStart.text, element.project)
    stringConstant.content.node.replaceAllChildrenToChildrenOf(newContent.node)
    return element
  }

  /**
   * Returns the best URI string for a relative import after [targetFile] has moved.
   *
   * When [sourceFile] is outside the PKL project that contains [targetFile], attempts to produce
   * an `@<depName>/<relPath>` URI using the following resolution order:
   *
   * 1. Search the source module's resolved dependencies for one whose root matches the target
   *    project directory (handles the case where the dep is already declared).
   * 2. Find the target project's own `package.name` via [pklProjectService] (handles the case
   *    where the dep is not yet declared but the target has a package identity — the user still
   *    needs to add the dependency declaration to their PklProject).
   *
   * Falls back to a plain filesystem-relative path if neither strategy yields a name.
   *
   * Returns `null` if a path cannot be computed at all (e.g. no common ancestor).
   */
  private fun computeNewRelativeUri(sourceFile: VirtualFile, targetFile: VirtualFile): String? {
    val ideaProject = element.project

    // Find the PKL project dir of the moved (target) file.
    val targetPklProjectDir = findPklProjectDir(targetFile)

    if (targetPklProjectDir != null) {
      val sourcePklProjectDir = findPklProjectDir(sourceFile)

      // Only attempt @dep/path when source is outside the target's PKL project.
      // Compare by URL string to avoid VirtualFile identity mismatches.
      if (sourcePklProjectDir?.url != targetPklProjectDir.url) {
        val relPath = VfsUtilCore.findRelativePath(targetPklProjectDir, targetFile, '/')

        if (relPath != null) {
          // Strategy 1: find a declared dependency in the source module whose root matches.
          // Uses enclosing module's resolved deps so it works for both packages and projects.
          val sourceDeps =
            element.enclosingModule?.dependencies(null)
              ?: sourcePklProjectDir?.let { srcDir ->
                ideaProject.pklProjectService.pklProjects.values
                  .find { it.projectDirVirtualFile?.url == srcDir.url }
                  ?.myDependencies
              }

          val declaredDepName =
            sourceDeps?.entries?.find { (_, dep) ->
              dep.getRoot(ideaProject)?.url == targetPklProjectDir.url
            }?.key

          if (declaredDepName != null) return "@$declaredDepName/$relPath"

          // Strategy 2: the source project hasn't declared the dep yet, but the target
          // project has a package name we can derive from its packageUri.
          // e.g. package://localhost:0/tests@1.0.0  →  name = "tests"
          val targetPackageName =
            ideaProject.pklProjectService.pklProjects.values
              .find { it.projectDirVirtualFile?.url == targetPklProjectDir.url }
              ?.metadata?.packageUri?.path
              ?.substringBeforeLast('@')
              ?.substringAfterLast('/')
              ?.takeIf { it.isNotEmpty() }

          if (targetPackageName != null) return "@$targetPackageName/$relPath"
        }
      }
    }

    // Default: plain relative path from the source file's directory.
    val sourceDir = sourceFile.parent ?: return null
    return VfsUtilCore.findRelativePath(sourceDir, targetFile, '/')
  }

  companion object {
    /**
     * Walks up the directory tree from [file] and returns the first ancestor directory that
     * contains a `PklProject` file, or `null` if none is found.
     */
    fun findPklProjectDir(file: VirtualFile): VirtualFile? {
      var dir = if (file.isDirectory) file else file.parent
      while (dir != null) {
        if (dir.findChild(PKL_PROJECT_FILENAME) != null) return dir
        dir = dir.parent
      }
      return null
    }

    /**
     * [targetUri] is the prefix of [moduleUri] that this reference refers to. For URIs with a
     * single reference, `targetUri == moduleUri`. See [PklModuleUriBase] for which URIs have one
     * vs. multiple references.
     *
     * Resolves `...` in the context of `targetUri` rather than the entire `moduleUri`. This runs a
     * slight risk of deviating from Pkl's `...` semantics if the directory hierarchy contains
     * multiple directories with the same name. However, it is easier to implement and simplifies
     * highlighting the first problematic path segment of unresolvable `...` URIs.
     *
     * [context] is the source element where the resolution started from, if exists.
     */
    fun resolve(
      targetUri: String,
      moduleUri: String,
      sourceFile: PsiFile,
      enclosingModule: PklModule?,
      project: Project,
      context: PklProject?
    ): PsiFileSystemItem? {
      // if `targetUri == "..."`, add enough context to make it resolvable on its own
      val effectiveTargetUri =
        when (targetUri) {
          "..." ->
            when {
              moduleUri == "..." -> ".../${sourceFile.name}"
              moduleUri.startsWith(".../") -> {
                val nextPathSegment = moduleUri.drop(4).substringBefore("/")
                if (nextPathSegment.isEmpty()) return null
                ".../$nextPathSegment/.."
              }
              else -> return null
            }
          else -> targetUri
        }

      return resolveVirtual(project, effectiveTargetUri, sourceFile, enclosingModule, context)
        ?.let { virtualFile ->
          val psiManager = PsiManager.getInstance(sourceFile.project)
          if (virtualFile.isDirectory) {
            psiManager.findDirectory(virtualFile)
          } else {
            psiManager.findFile(virtualFile)
          }
        }
    }

    private fun listClassPathChildren(
      file: VirtualFile,
      roots: Array<VirtualFile>,
      fileIndex: ProjectFileIndex
    ): Array<VirtualFile> {
      val result = mutableMapOf<String, VirtualFile>()
      val myRoot =
        fileIndex.getSourceRootForFile(file)
          ?: fileIndex.getClassRootForFile(file)
            ?: throw AssertionError("File $file should be under source or class root but isn't")
      val myRelativePath = file.path.substringAfter(myRoot.path)
      for (root in roots) {
        val baseDir =
          if (myRelativePath.isEmpty()) root
          else root.findFileByRelativePath(myRelativePath) ?: continue
        for (child in baseDir.children) {
          result.putIfAbsent(child.name, child)
        }
      }
      return result.values.toTypedArray()
    }

    data class ResolveGlobParams(
      val targetUriString: String,
      val moduleUriString: String,
      val element: PklModuleUri,
      val context: PklProject?
    )

    private val resolvedGlobProvider:
      ParameterizedCachedValueProvider<List<PsiFileSystemItem>, ResolveGlobParams> =
      ParameterizedCachedValueProvider { (targetUriString, moduleUriString, element, context) ->
        val result =
          doResolveGlob(targetUriString, moduleUriString, element, context)
            ?: return@ParameterizedCachedValueProvider noCacheResult()
        val dependencies = buildList {
          add(PsiModificationTracker.getInstance(element.project).forLanguage(PklLanguage))
          if (context != null) {
            add(element.project.pklProjectService)
          }
        }
        CachedValueProvider.Result.create(result, dependencies)
      }

    /**
     * @param targetUriString The prefix of [moduleUriString] that this reference refers to
     * @param moduleUriString The whole URI
     */
    fun resolveGlob(
      targetUriString: String,
      moduleUriString: String,
      element: PklModuleUri,
      context: PklProject?
    ): List<PsiFileSystemItem>? =
      CachedValuesManager.getManager(element.project)
        .getParameterizedCachedValue(
          element,
          element.project.cacheKeyService.getKey(
            "PklModuleUriReference.resolveGlob",
            "${context?.projectFile}-resolveGlob-${targetUriString}-${moduleUriString}"
          ),
          resolvedGlobProvider,
          false,
          ResolveGlobParams(targetUriString, moduleUriString, element, context)
        )

    private fun doResolveGlob(
      targetUriString: String,
      moduleUriString: String,
      element: PklModuleUri,
      context: PklProject?
    ): List<PsiFileSystemItem>? {
      val sourceFile = element.containingFile
      // triple-dot URI's are not supported
      if (targetUriString.startsWith("...")) {
        return null
      }
      val isPartialUri = moduleUriString != targetUriString
      val targetUri = parseUriOrNull(targetUriString) ?: return null
      val project = sourceFile.project
      val psiManager = PsiManager.getInstance(project)

      val fileManager = VirtualFileManager.getInstance()
      val toFileSystemItem = { virtualFile: VirtualFile ->
        if (virtualFile.isDirectory) psiManager.findDirectory(virtualFile)
        else psiManager.findFile(virtualFile)
      }

      val projectRootManager = ProjectRootManager.getInstance(project)
      val fileIndex = projectRootManager.fileIndex
      val virtualFile = sourceFile.virtualFile ?: return null
      val sourceRoot = fileIndex.getSourceRootForFile(virtualFile)
      val effectiveScheme =
        targetUri.scheme
        // if file is in a source directory, infer enclosing URI as modulepath
        ?: sourceRoot?.let { "modulepath" } ?: parseUriOrNull(virtualFile.url)?.scheme
      return when (effectiveScheme) {
        "file" -> {
          val listChildren = { it: VirtualFile -> it.children }
          val targetPath = targetUri.path ?: return null
          val resolved =
            when {
              targetPath.startsWith('/') -> {
                val fileRoot = fileManager.findFileByUrl("file:///") ?: return null
                GlobResolver.resolveAbsoluteGlob(fileRoot, targetPath, isPartialUri, listChildren)
              }
              targetPath.startsWith('@') -> {
                getDependencyRoot(project, targetPath, element.enclosingModule, context)?.let { root
                  ->
                  val effectiveTargetString = targetPath.substringAfter('/', "")
                  GlobResolver.resolveRelativeGlob(
                    root,
                    effectiveTargetString,
                    isPartialUri,
                    listChildren
                  )
                }
              }
              else ->
                GlobResolver.resolveRelativeGlob(
                  virtualFile.parent,
                  targetUriString,
                  isPartialUri,
                  listChildren
                )
            }
          return resolved?.mapNotNull(toFileSystemItem)
        }
        "modulepath" -> {
          val roots = sourceFile.findSourceAndClassesRoots()
          if (roots.isEmpty()) return null
          val listChildren = { it: VirtualFile -> listClassPathChildren(it, roots, fileIndex) }
          val targetPath = targetUri.path ?: return null
          val resolved =
            if (targetPath.startsWith('/')) {
              GlobResolver.resolveAbsoluteGlob(roots[0], targetPath, isPartialUri, listChildren)
            } else {
              GlobResolver.resolveRelativeGlob(
                virtualFile.parent,
                targetUriString,
                isPartialUri,
                listChildren
              )
            }
          return resolved.mapNotNull(toFileSystemItem)
        }
        "package" -> {
          if (targetUri.fragment?.startsWith('/') != true) {
            return null
          }
          val packageRoot =
            getDependencyRoot(project, targetUriString, element.enclosingModule, context)
              ?: return null
          val listChildren = { it: VirtualFile -> it.children }
          val targetPath = targetUri.fragment ?: return null
          val resolved =
            GlobResolver.resolveAbsoluteGlob(packageRoot, targetPath, isPartialUri, listChildren)
          return resolved.mapNotNull(toFileSystemItem)
        }
        else -> null
      }
    }

    fun getDependencyRoot(
      project: Project,
      targetUriStr: String,
      enclosingModule: PklModule?,
      context: PklProject?
    ): VirtualFile? {
      if (targetUriStr.startsWith("package:")) {
        val packageUri = PackageUri.create(targetUriStr) ?: return null
        return packageUri.asPackageDependency(null).getRoot(project)
      }
      val dependencyName = targetUriStr.substringBefore('/').drop(1)
      val dependencies = enclosingModule?.dependencies(context) ?: return null
      val dependency = dependencies[dependencyName] ?: return null
      return dependency.getRoot(project)
    }

    private fun resolveVirtual(
      project: Project,
      targetUriStr: String,
      sourcePsiFile: PsiFile,
      enclosingModule: PklModule?,
      context: PklProject?
    ): VirtualFile? {
      // `.originalFile` because IntelliJ's code completion mechanism
      // creates PsiFile copy which returns `null` for `.virtualFile`
      val sourceVirtualFile = sourcePsiFile.originalFile.virtualFile
      val sourceUriStr =
        sourceVirtualFile
          .url // don't convert to java.net.URI because not always a valid URI (e.g., may contain
      // spaces)

      val targetUri = parseUriOrNull(targetUriStr) ?: return null

      val fileManager = VirtualFileManager.getInstance()

      when (targetUri.scheme) {
        "pkl" ->
          return sourcePsiFile.project.pklStdLib
            .getModuleByShortName(targetUri.schemeSpecificPart)
            ?.file
        "modulepath" ->
          return when {
            // be on the safe side and only follow modulepath: URIs from local files
            sourceUriStr.startsWith("file:", ignoreCase = true) ->
              targetUri.path?.let { findOnClassPath(sourcePsiFile, null, it.drop(1)) }
            else -> null
          }
        "package" -> {
          if (targetUri.fragment?.startsWith('/') != true) {
            return null
          }
          return getDependencyRoot(project, targetUriStr, enclosingModule, context)
            ?.findFileByRelativePath(targetUri.fragment)
        }
        "file" ->
          return when {
            // be on the safe side and only follow file: URLs from local files
            sourceUriStr.startsWith("file", ignoreCase = true) ->
              fileManager.findFileByUrl(targetUriStr)
            else -> null
          }
        "https" ->
          return when {
            targetUri.host != null -> resolveVirtualHttp(targetUriStr, fileManager)
            else -> null
          }

        // targetUri is a relative URI or a dependency
        null -> {
          // If [sourceUri] is an https: URI, interpret [targetUri] as https: URI.
          // If [sourceFile] is under a source or class root, interpret [targetUri] as modulepath:
          // URI.
          // Otherwise, interpret [targetUri] as file: URI.

          if (sourceUriStr.startsWith("https:", ignoreCase = true)) {
            val sourceUri =
              try {
                URI(sourceUriStr)
              } catch (e: URISyntaxException) {
                return null
              }
            return resolveVirtualHttp(sourceUri.resolve(targetUri).toString(), fileManager)
          }

          if (targetUriStr.startsWith("@")) {
            val root = getDependencyRoot(project, targetUriStr, enclosingModule, context)
            if (root != null) {
              val resolvedTargetUri =
                targetUriStr.substringAfter('/', "").ifEmpty {
                  return root
                }
              return root.findFileByRelativePath(resolvedTargetUri)
            }
          }

          val projectRootManager = ProjectRootManager.getInstance(sourcePsiFile.project)
          val fileIndex = projectRootManager.fileIndex

          val sourceRoot = fileIndex.getSourceRootForFile(sourceVirtualFile)
          if (enclosingModule?.isInPackage != true) {
            if (sourceRoot != null) {
              if (targetUriStr.startsWith('/')) {
                return findOnClassPath(sourcePsiFile, null, targetUriStr.drop(1))
              }
              val sourceDir = sourceVirtualFile.parent
              if (sourceDir == sourceRoot) {
                return findOnClassPath(sourcePsiFile, null, targetUriStr)
              }
              val relativeSourcePath =
                VfsUtilCore.findRelativePath(sourceRoot, sourceDir, '/')
                  ?: throw AssertionError(
                    "$sourceVirtualFile isn't under $sourceRoot although it should be."
                  )
              return findOnClassPath(sourcePsiFile, relativeSourcePath, targetUriStr)
            }

            val classRoot = fileIndex.getClassRootForFile(sourceVirtualFile)
            if (classRoot != null) {
              if (targetUriStr.startsWith('/')) {
                return findOnClassPath(sourcePsiFile, null, targetUriStr.drop(1))
              }
              val sourceDir = sourceVirtualFile.parent
              val relativeSourcePath =
                VfsUtilCore.findRelativePath(classRoot, sourceDir, '/')
                  ?: throw AssertionError(
                    "$sourceVirtualFile isn't under $classRoot although it should be."
                  )
              return findOnClassPath(sourcePsiFile, relativeSourcePath, targetUriStr)
            }
          }

          return findOnFileSystem(sourceVirtualFile, targetUriStr)
        }

        // unsupported scheme
        else -> return null
      }
    }

    private fun resolveVirtualHttp(
      moduleUrl: String,
      fileManager: VirtualFileManager
    ): VirtualFile? {
      val virtualFile = fileManager.findFileByUrl(moduleUrl) ?: return null

      val httpFile = virtualFile as HttpVirtualFile
      val fileInfo = httpFile.fileInfo ?: return null

      return when (fileInfo.state) {
        null -> null
        RemoteFileState.DOWNLOADING_NOT_STARTED -> {
          fileInfo.startDownloading()
          virtualFile
        }
        RemoteFileState.DOWNLOADING_IN_PROGRESS -> virtualFile
        RemoteFileState.DOWNLOADED -> virtualFile
        RemoteFileState.ERROR_OCCURRED -> null
      }
    }

    private fun findOnFileSystem(sourceFile: VirtualFile, targetPath: String): VirtualFile? {
      return when {
        targetPath.startsWith(".../") -> findTripleDotPathOnFileSystem(sourceFile, targetPath)
        targetPath.startsWith("/") -> sourceFile.fileSystem.findFileByPath(targetPath)
        else -> sourceFile.parent?.findFileByRelativePath(targetPath)
      }
    }

    private fun findTripleDotPathOnFileSystem(
      sourceFile: VirtualFile,
      targetPath: String
    ): VirtualFile? {
      assert(targetPath.startsWith(".../"))

      val targetPathAfterTripleDot = targetPath.substring(4)

      var currentDir = sourceFile.parent?.parent
      while (currentDir != null) {
        val result = currentDir.findFileByRelativePath(targetPathAfterTripleDot)
        if (result != null && result.path != sourceFile.path) return result
        currentDir = currentDir.parent
      }

      return null
    }

    private fun findOnClassPath(
      sourceFile: PsiFile,
      // source file's directory path relative to its source or class root
      relativeSourceDirPath: String?,
      // target file path relative to source file's directory path
      relativeTargetFilePath: String
    ): VirtualFile? {

      val roots = sourceFile.findSourceAndClassesRoots()

      when {
        relativeTargetFilePath.startsWith(".../") -> {
          for (root in roots) {
            val result =
              findTripleDotPathOnClassPath(root, relativeSourceDirPath, relativeTargetFilePath)
            if (result != null) return result
          }
          return null
        }
        else -> {
          for (root in roots) {
            val sourceDir =
              if (relativeSourceDirPath == null) root
              else root.findFileByRelativePath(relativeSourceDirPath)
            val result = sourceDir?.findFileByRelativePath(relativeTargetFilePath)
            if (result != null) return result
          }
          return null
        }
      }
    }

    private fun findTripleDotPathOnClassPath(
      root: VirtualFile,
      // source file's directory path relative to its source or class root
      relativeSourceDirPath: String?,
      // target file path relative to source file's directory path
      relativeTargetFilePath: String
    ): VirtualFile? {

      assert(relativeTargetFilePath.startsWith(".../"))

      val targetFilePathAfterTripleDot = relativeTargetFilePath.substring(4)

      var currentDir = relativeSourceDirPath
      while (currentDir != null) {
        currentDir = VfsUtil.getParentDir(currentDir)
        val sourceDir = if (currentDir == null) root else root.findFileByRelativePath(currentDir)
        val result = sourceDir?.findFileByRelativePath(targetFilePathAfterTripleDot)
        if (result != null) return result
      }

      return null
    }
  }

  private val dataHolder = UserDataHolderBase()

  override fun <T : Any?> getUserData(key: Key<T>): T? = dataHolder.getUserData(key)

  override fun <T : Any?> putUserData(key: Key<T>, value: T?) = dataHolder.putUserData(key, value)
}
