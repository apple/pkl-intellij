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
package org.pkl.intellij

import com.intellij.lang.ImportOptimizer
import com.intellij.psi.PsiFile
import org.pkl.intellij.psi.*
import org.pkl.intellij.resolve.visitUsedLocalDefinitions

class PklImportOptimizer : ImportOptimizer {
  override fun supports(file: PsiFile): Boolean = file is PklModule

  override fun processFile(file: PsiFile): Runnable {
    val module = file as PklModule
    val project = module.project
    val base = project.pklBaseModule
    if (module.imports.none()) return Runnable {}

    val usedImports = mutableListOf<PklImport>()
    visitUsedLocalDefinitions(module, base) { if (it is PklImport) usedImports.add(it) }

    val optimizedImports =
      usedImports
        .asSequence()
        .map { ImportInfo.create(it) }
        .filter { it.uri != null }
        .distinctBy { it.uri to it.alias }
        .sorted()
        .map { it.import }

    val optimizedImportList = PklPsiFactory.createImportList(optimizedImports, project)

    return Runnable { module.importList.replace(optimizedImportList) }
  }

  data class ImportInfo(
    val import: PklImport,
    val uri: String?,
    val hasSchema: Boolean,
    val alias: String?
  ) : Comparable<ImportInfo> {
    companion object {
      private val comparator: Comparator<ImportInfo> =
        compareBy({ !it.hasSchema }, { it.uri }, { it.alias })

      fun create(import: PklImport): ImportInfo {
        val uri = import.moduleUri?.stringConstant?.content?.escapedText()
        val hasSchema = uri != null && uri.contains(':')
        val alias = import.identifier?.text
        return ImportInfo(import, uri, hasSchema, alias)
      }
    }

    override operator fun compareTo(other: ImportInfo): Int = comparator.compare(this, other)
  }
}
