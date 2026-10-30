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
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.psi.PsiElement
import com.intellij.util.ProcessingContext
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.relativeTo
import org.pkl.intellij.PklNamesValidator
import org.pkl.intellij.psi.*
import org.pkl.intellij.util.inferImportPropertyName

class ModuleNameCompletionProvider : PklCompletionProvider() {
  companion object {
    val namesValidator = PklNamesValidator()
  }

  override fun addCompletions(
    parameters: CompletionParameters,
    context: ProcessingContext,
    result: CompletionResultSet,
  ) {
    val module = parameters.originalFile as? PklModule ?: return
    val orig = parameters.originalPosition
    val typeAhead: String
    val pos: PsiElement
    if (orig != null && orig.elementType == PklElementTypes.IDENTIFIER) {
      // "module f" or "module foo.b"
      pos = orig
      typeAhead = orig.text.removeSurrounding("`")
    } else {
      // "module " or "module foo."
      pos = parameters.position
      typeAhead = ""
    }
    val qualifiedIdentifier = pos.parent as? PklQualifiedIdentifier ?: return
    val identifiers =
      qualifiedIdentifier.childSeq.filter { it.elementType == PklElementTypes.IDENTIFIER }.toList()
    // must be in last qualified identifier segment
    if (identifiers.isNotEmpty() && identifiers.last() != pos) return
    val completeNames = identifiers.dropLast(1).map { it.text.removeSurrounding("`") }
    completeFilename(typeAhead, completeNames, module, result)
    completePackagePath(typeAhead, completeNames, module, result)
  }

  private fun completeFilename(
    typeAhead: String,
    completeNames: List<String>,
    file: PklModule,
    result: CompletionResultSet,
  ) {
    // must be in first qualified identifier segment
    if (completeNames.size > 1) return
    val filename = file.name
    val inferredName = inferImportPropertyName(filename) ?: return
    if (!filename.startsWith(typeAhead)) return
    result.addElement(LookupElementBuilder.create(escapeName(inferredName)))
  }

  private fun completePackagePath(
    typeAhead: String,
    completeNames: List<String>,
    file: PklModule,
    result: CompletionResultSet,
  ) {
    // must be in project
    val proj = file.pklProject ?: return
    // must be in local fs (so relative to project dir)
    if (!file.virtualFile.isInLocalFileSystem) return
    // must have package name metadata
    val packageName = proj.metadata.packageName?.split('.') ?: return
    val relativeModulePath = Path.of(file.virtualFile.path).relativeTo(proj.projectDir)
    val relativeModuleName = relativeModulePath.mapIndexed { index, part ->
      if (index == relativeModulePath.nameCount - 1) inferImportPropertyName(part.name)!!
      else part.name
    }

    fun addResultPath(names: List<String>) {
      result.addElement(LookupElementBuilder.create(names.joinToString(".") { escapeName(it) }))
    }

    // given package foo.bar
    // module at baz/qux.pkl
    if (completeNames.size < packageName.size) {
      // complete f -> foo.bar.baz.qux
      // complete foo. -> foo.bar.baz.qux

      if (
        completeNames.size > 0 &&
          packageName.take(completeNames.size - 1) != completeNames.dropLast(1)
      )
        return
      val remainingPackageName = packageName.drop(completeNames.size)
      if (remainingPackageName.firstOrNull()?.startsWith(typeAhead) == false) return
      addResultPath(remainingPackageName + relativeModuleName)
    } else {
      // complete foo.bar. -> foo.bar.baz.qux
      // complete foo.bar.baz. -> foo.bar.baz.qux

      if (completeNames.take(packageName.size) != packageName) return
      val remainingPathName = relativeModuleName.drop(completeNames.size - packageName.size)
      if (remainingPathName.firstOrNull()?.startsWith(typeAhead) == false) return
      addResultPath(remainingPathName)
    }
  }

  private fun escapeName(name: String): String =
    if (namesValidator.isIdentifier(name, null)) name else "`$name`"
}
