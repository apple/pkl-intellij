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

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.removeUserData
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.refactoring.move.moveFilesOrDirectories.MoveFileHandler
import com.intellij.refactoring.util.MoveRenameUsageInfo
import com.intellij.usageView.UsageInfo
import com.intellij.util.IncorrectOperationException
import com.intellij.util.withPath
import java.net.URI
import java.nio.file.Path
import java.util.*
import org.pkl.intellij.psi.*

class MovePklModuleHandler : MoveFileHandler() {

  private val originalModuleDirectoryKey: Key<Path> = Key.create("ORIGINAL_MODULE_DIRECTORY")

  override fun canProcessElement(element: PsiFile): Boolean {
    return element is PklModule
  }

  override fun prepareMovedFile(
    file: PsiFile,
    moveDestination: PsiDirectory,
    oldToNewMap: MutableMap<PsiElement, PsiElement>
  ) {
    if (file is PklModule) {
      val moduleDirectoryPath =
        file.originalFile.virtualFile?.toNioPath()?.toAbsolutePath()?.parent ?: return
      file.putUserData(originalModuleDirectoryKey, moduleDirectoryPath)
    }
  }

  override fun findUsages(
    psiFile: PsiFile,
    newParent: PsiDirectory,
    searchInComments: Boolean,
    searchInNonJavaFiles: Boolean
  ): List<UsageInfo>? {
    val searchScope = GlobalSearchScope.projectScope(psiFile.project)
    return ReferencesSearch.search(psiFile, searchScope, false)
      .distinct()
      .map {
        val range = it.rangeInElement
        MoveRenameUsageInfo(it.element, it, range.startOffset, range.endOffset, psiFile, false)
      }
      .toList()
      .ifEmpty { null }
  }

  override fun retargetUsages(
    usageInfos: List<UsageInfo>,
    oldToNewMap: Map<PsiElement, PsiElement>
  ) {
    usageInfos.filterIsInstance<MoveRenameUsageInfo>().forEach {
      val element = it.referencedElement as? PklModuleUri ?: return@forEach
      val reference = it.getReference() as? PklModuleUriReference ?: return@forEach
      try {
        reference.bindToElement(element)
      } catch (ex: IncorrectOperationException) {
        LOG.error(ex)
      }
    }
  }

  @Throws(IncorrectOperationException::class)
  override fun updateMovedFile(file: PsiFile) {
    if (file is PklModule) {
      val oldModuleDirectoryPath = file.removeUserData(originalModuleDirectoryKey) ?: return
      val newModuleDirectoryPath =
        file.originalFile.virtualFile?.toNioPath()?.toAbsolutePath()?.parent ?: return
      val offsetPath = newModuleDirectoryPath.relativize(oldModuleDirectoryPath)
      WriteCommandAction.writeCommandAction(file.project).compute<
        Array<PsiElement>, RuntimeException
      > {
        file.imports.forEach { import -> rewriteRelativeImport(import, offsetPath) }
        arrayOf(file)
      }
    }
  }

  private fun rewriteRelativeImport(existingImport: PklImport, offsetPath: Path) {
    val moduleUri = existingImport.moduleUri ?: return
    val existingOtherModuleUri = moduleUri.getModuleUri()?.let { URI.create(it) } ?: return
    val existingOtherModulePath = Path.of(existingOtherModuleUri.path)
    if (existingOtherModulePath.isAbsolute) return
    val newOtherModulePath = offsetPath.resolve(existingOtherModulePath).normalize()
    val newOtherModuleUri = existingOtherModuleUri.withPath(newOtherModulePath.toString())
    val newConstant =
      PklPsiFactory.createStringConstant(newOtherModuleUri.toString(), existingImport.project)
    moduleUri.stringConstant.replace(newConstant)
  }

  companion object {
    private val LOG = Logger.getInstance(MovePklModuleHandler::class.java)
  }
}
