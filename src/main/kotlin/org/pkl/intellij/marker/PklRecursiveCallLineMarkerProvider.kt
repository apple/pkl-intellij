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
package org.pkl.intellij.marker

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.markup.GutterIconRenderer.Alignment.RIGHT
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.util.parentOfTypes
import com.intellij.util.FunctionUtil
import org.pkl.intellij.PklIcons
import org.pkl.intellij.psi.*
import org.pkl.intellij.resolve.ResolveVisitors

class PklRecursiveCallLineMarkerProvider : LineMarkerProvider {
  override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

  override fun collectSlowLineMarkers(
    elements: MutableList<out PsiElement>,
    result: MutableCollection<in LineMarkerInfo<*>>
  ) {

    // add at most one recursive line marker per line
    val lineNumbers = mutableSetOf<Int>()

    nextElement@ for (element in elements) {
      // line markers should be added for leaf elements only
      // (see docs for LineMarkerProvider.getLineMarkerInfo)
      if (element.elementType != PklElementTypes.IDENTIFIER) continue

      ProgressManager.checkCanceled()

      // cache enclosing document (assumes [elements] are contained in the same document)
      var document: Document? = null

      var base: PklBaseModule? = null

      when (val accessName = element.parent) {
        is PklUnqualifiedAccessName -> {
          val accessExpr = accessName.parent as? PklUnqualifiedAccessExpr ?: continue@nextElement
          if (accessExpr.argumentList == null) continue@nextElement

          when (
            val method =
              accessName.parentOfTypes(
                PklMethod::class,
                // stop tokens
                PklObjectBody::class,
                PklFunctionLiteral::class
              )
          ) {
            is PklMethod -> {
              if (accessName.identifier.text == method.name) {
                if (document == null)
                  document = findEnclosingDocument(method) ?: continue@nextElement
                markRecursiveMethod(element, document, lineNumbers, result)
              }
            }
          }
        }
        is PklQualifiedAccessName -> {
          val accessExpr = accessName.parent as? PklQualifiedAccessExpr ?: continue@nextElement
          if (accessExpr.argumentList == null) continue@nextElement

          when (
            val method =
              accessName.parentOfTypes(
                PklMethod::class,
                // stop tokens
                PklObjectBody::class,
                PklFunctionLiteral::class
              )
          ) {
            is PklMethod -> {
              // TODO: check that class (and hence method) is final (or accept the risk of false
              // positives)
              val methodName = method.name ?: continue@nextElement
              if (accessName.identifier.text == methodName) {
                if (base == null) base = method.project.pklBaseModule
                val targetMethod =
                  accessExpr.resolve(
                    base,
                    null,
                    mapOf(),
                    ResolveVisitors.firstElementNamed(methodName, base)
                  )
                if (targetMethod === method) {
                  if (document == null) document = findEnclosingDocument(method) ?: return
                  markRecursiveMethod(element, document, lineNumbers, result)
                }
              }
            }
          }
        }
      }
    }
  }

  private fun findEnclosingDocument(element: PsiElement): Document? {
    val containingFile = element.containingFile
    return PsiDocumentManager.getInstance(containingFile.project).getDocument(containingFile)
  }

  private fun markRecursiveMethod(
    identifier: PsiElement,
    document: Document,
    lineNumbers: MutableSet<Int>,
    result: MutableCollection<in LineMarkerInfo<*>>
  ) {

    val lineNumber = document.getLineNumber(identifier.textOffset)
    if (!lineNumbers.add(lineNumber)) return

    result.add(
      LineMarkerInfo(
        identifier,
        identifier.textRange,
        PklIcons.RECURSIVE_METHOD_MARKER,
        FunctionUtil.constant("Recursive call"),
        null,
        RIGHT
      ) {
        "recursive method marker"
      }
    )
  }
}
