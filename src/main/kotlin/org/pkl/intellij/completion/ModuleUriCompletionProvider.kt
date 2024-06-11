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
package org.pkl.intellij.completion

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.util.parentOfType
import com.intellij.util.ProcessingContext
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.Icon
import kotlin.io.path.name
import org.pkl.intellij.PklIcons
import org.pkl.intellij.packages.PackageDependency
import org.pkl.intellij.packages.dto.PackageAssetUri
import org.pkl.intellij.packages.pklPackageService
import org.pkl.intellij.psi.*
import org.pkl.intellij.util.findSourceAndClassesRoots
import org.pkl.intellij.util.isAbsoluteUriLike
import org.pkl.intellij.util.packagesCacheDir

class ModuleUriCompletionProvider(private val packageUriOnly: Boolean = false) :
  PklCompletionProvider() {
  companion object {
    private const val PKL_SCHEME = "pkl:"
    private const val FILE_SCHEME = "file:///"
    private const val HTTPS_SCHEME = "https://"
    private const val MODULE_PATH_SCHEME = "modulepath:/"
    private const val PACKAGE_SCHEME = "package://"

    private val SCHEME_ELEMENTS =
      listOf(
        LookupElementBuilder.create(PKL_SCHEME).completeAgain(),
        LookupElementBuilder.create(FILE_SCHEME).completeAgain(),
        LookupElementBuilder.create(HTTPS_SCHEME),
        LookupElementBuilder.create(MODULE_PATH_SCHEME).completeAgain(),
        LookupElementBuilder.create(PACKAGE_SCHEME).completeAgain()
      )

    private val GLOBBABLE_SCHEME_ELEMENTS =
      listOf(
        LookupElementBuilder.create(FILE_SCHEME).completeAgain(),
        LookupElementBuilder.create(MODULE_PATH_SCHEME).completeAgain(),
        LookupElementBuilder.create(PACKAGE_SCHEME).completeAgain()
      )

    private val LOCAL_FILE_SYSTEM_ROOT: VirtualFile =
      LocalFileSystem.getInstance().findFileByPath("/")!!

    private object ImportInsertHandler : InsertHandler<LookupElement> {
      override fun handleInsert(context: InsertionContext, item: LookupElement) {
        val importNode =
          context.file.findElementAt(context.startOffset)?.parentOfType<PklImportBase>() ?: return
        val importUri = importNode.moduleUri?.stringConstant?.content?.escapedText() ?: return
        val isAbsoluteUri = isAbsoluteUriLike(importUri)
        val isGlobImport = importNode.isGlob
        if (!isAbsoluteUri && !isGlobImport) return
        val numPounds = importNode.firstChildToken()!!.text.length - 1
        var escaped = item.lookupString
        // escape wildcards
        if (isGlobImport) {
          val replacement = if (numPounds > 0) "\\\\$1" else "\\\\\\\\$1"
          escaped = escaped.replace(Regex("([*\\\\{\\[])"), replacement)
        }
        // percent-encode
        if (isAbsoluteUri) {
          escaped = URI(null, null, escaped, null).rawPath
        }
        context.document.replaceString(
          context.startOffset,
          context.startOffset + item.lookupString.length,
          escaped
        )
      }
    }
  }

  override fun addCompletions(
    parameters: CompletionParameters,
    context: ProcessingContext,
    resultSet: CompletionResultSet
  ) {

    // Operate on the original PSI tree, not the one where [CompletionUtilCore.DUMMY_IDENTIFIER] was
    // inserted.
    // I believe this works because we aren't handing back any (potentially invalidated) PSI
    // elements.
    val stringChars = parameters.originalPosition ?: return
    val isGlobImport = stringChars.parentOfType<PklImportBase>()?.isGlob ?: false
    val moduleUri = stringChars.text.substring(0, parameters.offset - stringChars.textOffset)
    val pklModule = parameters.originalFile as PklModule
    val project = stringChars.project
    complete(moduleUri, isGlobImport, pklModule, project, resultSet)
  }

  // assumes caret location is immediately behind [moduleUri]
  private fun complete(
    targetUri: String,
    isGlobImport: Boolean,
    sourceModule: PklModule,
    project: Project,
    resultSet: CompletionResultSet
  ) {
    // collect lookup elements before adding them to result set
    // (according to docs, batch insert is preferable)
    val collector = mutableListOf<LookupElement>()

    when {
      targetUri.startsWith(PKL_SCHEME) && !isGlobImport && !packageUriOnly -> {
        for (module in sourceModule.project.pklStdLib.modules) {
          collector.add(LookupElementBuilder.create(module.uri).withIcon(PklIcons.CLASS))
        }
      }
      targetUri.startsWith(FILE_SCHEME, true) && !packageUriOnly -> {
        val roots = arrayOf(LOCAL_FILE_SYSTEM_ROOT)
        completeHierarchicalUri(roots, ".", targetUri.substring(8), false, collector)
      }
      targetUri.startsWith(MODULE_PATH_SCHEME, true) && !packageUriOnly -> {
        val roots = sourceModule.findSourceAndClassesRoots()
        completeHierarchicalUri(roots, ".", targetUri.substring(12), true, collector)
      }
      targetUri.startsWith(PACKAGE_SCHEME, true) -> {
        // if there is no fragment part, offer completions from the cached directory
        when (val packageAssetUri = PackageAssetUri.create(targetUri)) {
          null -> {
            if (targetUri.endsWith("@")) {
              completePackageBaseUriVersions(targetUri, collector)
            } else {
              completePackageBaseUris(collector)
            }
          }
          else -> {
            val packageService = project.pklPackageService
            val libraryRoots =
              packageService.getLibraryRoots(PackageDependency(packageAssetUri.packageUri, null))
                ?: return
            completeHierarchicalUri(
              arrayOf(libraryRoots.packageRoot),
              ".",
              packageAssetUri.assetPath,
              false,
              collector
            )
          }
        }
      }
      targetUri.startsWith("@") && !packageUriOnly -> {
        val dependencies = sourceModule.dependencies ?: return
        if (!targetUri.contains('/')) {
          collector.addAll(
            dependencies.keys.map { name ->
              LookupElementBuilder.create("@$name").withIcon(PklIcons.PACKAGE).completeAgain()
            }
          )
        } else {
          PklModuleUriReference.getDependencyRoot(project, targetUri, sourceModule)?.let { root ->
            val delimiter = targetUri.indexOf('/')
            val resolvedTargetUri = if (delimiter == -1) "/" else targetUri.drop(delimiter)
            completeHierarchicalUri(arrayOf(root), ".", resolvedTargetUri, false, collector)
          }
        }
      }
      !targetUri.contains(':') && packageUriOnly -> {
        collector.add(LookupElementBuilder.create(PACKAGE_SCHEME).completeAgain())
      }
      !targetUri.contains(':') -> {
        if (!targetUri.contains('/')) {
          collector.addAll(if (isGlobImport) GLOBBABLE_SCHEME_ELEMENTS else SCHEME_ELEMENTS)
        }
        completeRelativeUri(targetUri, sourceModule, isGlobImport, collector)
      }
    }

    resultSet.addAllElements(collector)
  }

  private fun collectPackages(basePath: Path, nameParts: List<String> = emptyList()): Set<String> {
    return if (Files.isDirectory(basePath))
      Files.list(basePath).toList().flatMapTo(mutableSetOf()) { file ->
        if (file.name.contains("@")) {
          listOf(nameParts.joinToString("/") { it } + "/" + file.name.substringBeforeLast("@"))
        } else {
          collectPackages(file, nameParts + file.name)
        }
      }
    else emptySet()
  }

  private fun completePackageBaseUriVersions(
    targetUri: String,
    collector: MutableList<LookupElement>
  ) {
    val basePath = targetUri.drop(10).substringBeforeLast('/')
    val packageName = targetUri.substringAfterLast('/')
    val packages = packagesCacheDir?.findFileByRelativePath(basePath)?.children ?: return
    for (pkg in packages) {
      if (pkg.name.startsWith(packageName)) {
        val version = pkg.name.substringAfterLast('@')
        val completion = "$targetUri$version".let { if (!packageUriOnly) "$it#/" else it }
        val element =
          LookupElementBuilder.create(completion).withPresentableText(version).completeAgain()
        collector.add(element)
      }
    }
  }

  private fun completePackageBaseUris(collector: MutableList<LookupElement>) {
    val root = packagesCacheDir ?: return
    val packages = collectPackages(root.toNioPath())
    for (pkg in packages) {
      val completionText = "$PACKAGE_SCHEME$pkg@"
      val pkgName = pkg.substringAfterLast('/')
      val element =
        LookupElementBuilder.create(completionText)
          .withTypeText(pkg, true)
          .withPresentableText(pkgName)
          .completeAgain()
      collector.add(element)
    }
  }

  private fun completeHierarchicalUri(
    roots: Array<VirtualFile>,
    relativeSourceDirPath: String,
    // the path to complete
    relativeTargetFilePath: String,
    isModulePath: Boolean,
    collector: MutableList<LookupElement>
  ) {
    val relativeTargetDirPath = relativeTargetFilePath.substringBeforeLast('/', ".")
    val isTripleDotDirPath = relativeTargetDirPath.startsWith("...")

    for (root in roots) {
      val dirs =
        if (isTripleDotDirPath) {
          resolveTripleDotDirPath(root, relativeSourceDirPath, relativeTargetDirPath)
        } else {
          val sourceDir = root.findFileByRelativePath(relativeSourceDirPath)
          listOfNotNull(sourceDir?.findFileByRelativePath(relativeTargetDirPath))
        }

      for (dir in dirs) {
        dir.children?.forEach { child ->
          val isDirectory = child.isDirectory
          val extension = child.extension
          val name = child.name
          if (
            !name.startsWith('.') &&
              (isDirectory || extension == "pkl" || extension == "pcf" || name == "PklProject")
          ) {
            // prefix with `./` if name starts with `@`, becuase this is reserved for dependency
            // notation.
            val completionName =
              if (name.startsWith("@") && !relativeTargetFilePath.contains('/')) "./$name" else name
            val element =
              LookupElementBuilder.create(completionName)
                .withIcon(getIcon(isDirectory, isModulePath))
                .withInsertHandler(ImportInsertHandler)
            collector.add(element)
          }
        }
      }
    }
  }

  private fun resolveTripleDotDirPath(
    root: VirtualFile,
    // source file's directory path relative to its source or class root
    relativeSourceDirPath: String,
    // target directory path relative to source file's directory path
    relativeTargetDirPath: String
  ): List<VirtualFile> { // list of directories that [relativeTargetDirPath] may refer to

    if (relativeSourceDirPath == ".") return emptyList()

    assert(relativeTargetDirPath == "..." || relativeTargetDirPath.startsWith(".../"))

    val targetDirPathAfterTripleDot =
      if (relativeTargetDirPath.length == 3) "" else relativeTargetDirPath.substring(4)

    val resultDirs = mutableListOf<VirtualFile>()

    var currentDir: String? = relativeSourceDirPath
    while (currentDir != null) {
      currentDir = VfsUtil.getParentDir(currentDir)
      val sourceDir = if (currentDir == null) root else root.findFileByRelativePath(currentDir)
      val targetDir = sourceDir?.findFileByRelativePath(targetDirPathAfterTripleDot)
      if (targetDir != null) resultDirs.add(targetDir)
    }

    return resultDirs
  }

  private fun getIcon(isDirectory: Boolean, isModulePath: Boolean): Icon {
    return if (isDirectory) {
      if (isModulePath) PklIcons.PACKAGE else PklIcons.DIRECTORY
    } else {
      PklIcons.FILE
    }
  }

  private fun completeRelativeUri(
    targetUri: String,
    sourceModule: PklModule,
    isGlobImport: Boolean,
    collector: MutableList<LookupElement>
  ) {

    val projectRootManager = ProjectRootManager.getInstance(sourceModule.project)
    val sourceFile = sourceModule.virtualFile ?: return
    val sourceDir = sourceFile.parent ?: return
    val sourceRoot = projectRootManager.fileIndex.getSourceRootForFile(sourceFile)
    val isAbsoluteTargetFilePath = targetUri.startsWith('/')
    val relativeTargetFilePath = if (isAbsoluteTargetFilePath) targetUri.drop(1) else targetUri
    if (isGlobImport && targetUri.startsWith("...")) {
      return
    }

    if (sourceRoot != null) {
      // interpret as modulepath: URI
      val roots = sourceModule.findSourceAndClassesRoots()
      val relativeSourceDirPath =
        if (isAbsoluteTargetFilePath) {
          "."
        } else {
          VfsUtilCore.findRelativePath(sourceRoot, sourceDir, '/') ?: "."
        }
      completeHierarchicalUri(roots, relativeSourceDirPath, relativeTargetFilePath, true, collector)
    } else {
      // Assertion: [sourceFile] is not under a source or classes root.
      // (Files under a classes root are read-only and can't trigger completion.)
      // An example is a Pkl file in an IDE module's root directory.
      // interpret as file: URI
      val relativeSourceDirPath =
        if (isAbsoluteTargetFilePath) {
          "."
        } else {
          VfsUtilCore.findRelativePath(LOCAL_FILE_SYSTEM_ROOT, sourceDir, '/') ?: "."
        }
      completeHierarchicalUri(
        arrayOf(LOCAL_FILE_SYSTEM_ROOT),
        relativeSourceDirPath,
        relativeTargetFilePath,
        false,
        collector
      )
    }
  }
}
