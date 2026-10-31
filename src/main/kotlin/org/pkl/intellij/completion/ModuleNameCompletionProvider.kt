/**
 * Copyright © 2024-2026 Apple Inc. and the Pkl project authors. All rights reserved.
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
import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.util.ProcessingContext
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.relativeTo
import org.pkl.intellij.psi.*
import org.pkl.intellij.util.inferImportPropertyName
import org.pkl.parser.Lexer

class ModuleNameCompletionProvider : PklCompletionProvider() {
  companion object {
    // for testing: allow test to disable sourceroot resolution to disregard test env as details
    var allowSourceRootCompletion = true
  }

  override fun addCompletions(
    parameters: CompletionParameters,
    context: ProcessingContext,
    result: CompletionResultSet,
  ) {
    val module = parameters.originalFile as? PklModule ?: return
    val orig = parameters.originalPosition
    val pos =
      if (orig != null && orig.elementType == PklElementTypes.IDENTIFIER) {
        // "module f" or "module foo.b"
        orig
      } else {
        // "module " or "module foo."
        parameters.position
      }
    val qualifiedIdentifier = pos.parent as? PklQualifiedIdentifier ?: return
    val identifiers =
      qualifiedIdentifier.childSeq.filter { it.elementType == PklElementTypes.IDENTIFIER }.toList()
    // must be in last qualified identifier segment
    if (identifiers.isNotEmpty() && identifiers.last() != pos) return
    val completeNames = identifiers.dropLast(1).map { it.text.removeSurrounding("`") }

    result.prefixMatcher.isStartMatch("")

    val completions = ArrayList<LookupElement>(2)
    var inRoot = completePackagePath(result.prefixMatcher, completeNames, module, completions)
    if (allowSourceRootCompletion) {
      inRoot =
        completeSourceRootPath(result.prefixMatcher, completeNames, module, completions) || inRoot
    }
    if (!inRoot) {
      completeFilename(result.prefixMatcher, completeNames, module, completions)
    }
    result.addAllElements(completions)
  }

  private fun completePackagePath(
    prefixMatcher: PrefixMatcher,
    completeNames: List<String>,
    file: PklModule,
    result: MutableList<LookupElement>,
  ): Boolean {
    // must be in project
    val proj = file.pklProject ?: return false
    // must be in local fs (so relative to project dir)
    if (!file.virtualFile.isInLocalFileSystem) return false
    // must have package name metadata
    val packageName = proj.metadata.packageName?.split('.') ?: return false
    val relativeModuleName =
      Path.of(file.virtualFile.path).relativeTo(proj.projectDir).splitToModuleNameParts()

    // given package foo.bar
    // module at baz/qux.pkl
    if (completeNames.size < packageName.size) {
      // complete f -> foo.bar.baz.qux
      // complete foo. -> foo.bar.baz.qux

      if (
        completeNames.isNotEmpty() &&
          packageName.take(completeNames.size - 1) != completeNames.dropLast(1)
      )
        return true
      val remainingPackageName = packageName.drop(completeNames.size)
      if (remainingPackageName.firstOrNull()?.let { prefixMatcher.isStartMatch(it) } == false)
        return true
      result.addResultPath(remainingPackageName + relativeModuleName)
    } else {
      // complete foo.bar. -> foo.bar.baz.qux
      // complete foo.bar.baz. -> foo.bar.baz.qux

      if (completeNames.take(packageName.size) != packageName) return true
      val remainingPathName = relativeModuleName.drop(completeNames.size - packageName.size)
      if (remainingPathName.firstOrNull()?.let { prefixMatcher.isStartMatch(it) } == false)
        return true
      result.addResultPath(remainingPathName)
    }
    return true
  }

  private fun completeSourceRootPath(
    prefixMatcher: PrefixMatcher,
    completeNames: List<String>,
    file: PklModule,
    result: MutableList<LookupElement>,
  ): Boolean {
    val projectRootManager = ProjectRootManager.getInstance(file.project)
    val fileIndex = projectRootManager.fileIndex
    val sourceRoot = fileIndex.getSourceRootForFile(file.virtualFile) ?: return false
    val relativePath =
      VfsUtilCore.findRelativePath(sourceRoot, file.virtualFile, '/') ?: return false
    val relativeModuleName = Path.of(relativePath).splitToModuleNameParts()
    if (completeNames.size >= relativeModuleName.size) return true
    if (relativeModuleName.take(completeNames.size) != completeNames) return true
    val remainingPathName = relativeModuleName.drop(completeNames.size)
    if (remainingPathName.firstOrNull()?.let { prefixMatcher.isStartMatch(it) } == false)
      return true
    result.addResultPath(remainingPathName)
    return true
  }

  private fun completeFilename(
    prefixMatcher: PrefixMatcher,
    completeNames: List<String>,
    file: PklModule,
    result: MutableList<LookupElement>,
  ) {
    // must be in first qualified identifier segment
    if (completeNames.size > 1) return
    val filename = file.name
    val inferredName = inferImportPropertyName(filename) ?: return
    if (!prefixMatcher.isStartMatch(filename)) return
    result.addResultPath(listOf(inferredName))
  }

  private fun Path.splitToModuleNameParts(): List<String> = mapIndexed { index, part ->
    if (index == nameCount - 1) inferImportPropertyName(part.name)!! else part.name
  }

  private fun MutableList<LookupElement>.addResultPath(names: List<String>) {
    add(LookupElementBuilder.create(names.joinToString(".") { Lexer.maybeQuoteIdentifier(it) }))
  }
}
