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
package org.pkl.intellij.annotator

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import java.net.URI
import java.net.URISyntaxException
import org.pkl.intellij.PklVersion
import org.pkl.intellij.action.PklDownloadDependencySourcesAction
import org.pkl.intellij.action.PklDownloadPackageAction
import org.pkl.intellij.intention.PklPrefixDependencyNotationQuickFix
import org.pkl.intellij.packages.PackageDependency
import org.pkl.intellij.packages.dto.Checksums
import org.pkl.intellij.packages.dto.PackageUri
import org.pkl.intellij.packages.dto.PklProject
import org.pkl.intellij.packages.dto.Version
import org.pkl.intellij.packages.pklPackageService
import org.pkl.intellij.psi.*
import org.pkl.intellij.util.currentProject
import org.pkl.intellij.util.escapeXml
import org.pkl.intellij.util.parseUriOrNull

class PklModuleUriAndVersionAnnotator : PklAnnotator() {
  companion object {
    val logger = Logger.getInstance(PklModuleUriAndVersionAnnotator::class.java)
  }

  override fun doAnnotate(element: PsiElement, holder: AnnotationHolder) {
    val project = holder.currentProject
    val base = project.pklBaseModule
    val context = element.enclosingModule?.pklProject

    when (element) {
      is PklModule -> {
        val projectPklVersion = base.pklVersion
        val minPklVersionDetails = element.minPklVersionDetails ?: return
        checkPklVersion(
          minPklVersionDetails.first,
          projectPklVersion,
          minPklVersionDetails.second,
          holder
        )
      }
      is PklModuleUri -> {
        val references = element.references
        if (references.isEmpty()) return

        val uriText = element.escapedContent ?: return
        val isGlobImport = (element.parent as? PklImportBase)?.isGlob ?: false
        if (uriText.startsWith("package:")) {
          if (annotatePackageUri(element, uriText, holder)) {
            return
          }
        }
        if (isGlobImport) {
          annotateGlobUri(element, references, uriText, holder, context)
        } else {
          annotateNonGlobUri(element, references, uriText, holder, context)
        }
      }
    }
  }

  private fun annotatePackageUri(
    element: PklModuleUri,
    uriText: String,
    holder: AnnotationHolder
  ): Boolean {
    if (!uriText.startsWith("package:")) return false
    val uri =
      try {
        URI(uriText)
      } catch (e: URISyntaxException) {
        createAnnotation(
          HighlightSeverity.WARNING,
          element.textRange,
          "Malformed URI",
          """
            Malformed URI: <code>$uriText</code>
          """
            .trimIndent(),
          holder
        )
        return true
      }
    if (uri.authority == null) {
      createAnnotation(
        HighlightSeverity.WARNING,
        element.textRange,
        "Missing authority",
        """
            Package URIs must contain an authority segment, for example, <code>//example.com</code> in <code>package://example.com/package@1.0.0#/MyModule.pkl</code>
          """
          .trimIndent(),
        holder
      )
      return true
    }
    if (uri.path == null) {
      createAnnotation(
        HighlightSeverity.WARNING,
        element.stringConstant.textRange,
        "Missing path",
        """
            Package URIs must contain an path segment, for example, <code>/package@1.0.0</code> in <code>package://example.com/package@1.0.0#/MyModule.pkl</code>.
          """
          .trimIndent(),
        holder
      )
      return true
    }
    val versionAndChecksumPart = uri.path.substringAfterLast('@', "")
    if (versionAndChecksumPart.isEmpty()) {
      createAnnotation(
        HighlightSeverity.WARNING,
        element.stringConstant.textRange,
        "Missing version",
        """
            Package URIs must contain a version, for example, <code>@1.0.0</code> in <code>package://example.com/package@1.0.0#/MyModule.pkl</code>.
          """
          .trimIndent(),
        holder
      )
      return true
    }
    val versionAndChecksumParts = versionAndChecksumPart.split("::")
    val version = Version.parseOrNull(versionAndChecksumParts.first())
    if (version == null) {
      val offset = element.text.lastIndexOf('@') + 1
      createAnnotation(
        HighlightSeverity.WARNING,
        TextRange(0, versionAndChecksumPart.length).shiftRight(element.textOffset + offset),
        "Invalid semver",
        "Invalid semver: <code>$versionAndChecksumPart</code>",
        holder
      )
      return true
    }
    val checksum =
      if (versionAndChecksumParts.size == 2) {
        val checksumParts = versionAndChecksumParts[1].split(':')
        if (checksumParts.size != 2) {
          createAnnotation(
            HighlightSeverity.WARNING,
            element.stringConstant.textRange,
            "Invalid checksum",
            "Invalid checksum: <code>$versionAndChecksumPart</code>. Checksums should have form `::sha256:<checksum>",
            holder
          )
          return true
        }
        val (algo, value) = checksumParts
        if (algo != "sha256") {
          createAnnotation(
            HighlightSeverity.WARNING,
            element.stringConstant.textRange,
            "Invalid checksum",
            "Invalid checksum: <code>$versionAndChecksumPart</code>. Checksums should have form `::sha256:<checksum>",
            holder
          )
          return true
        }
        Checksums(value)
      } else null
    if (uri.fragment == null) {
      createAnnotation(
        HighlightSeverity.WARNING,
        element.stringConstant.textRange,
        "Missing fragment",
        "Package URIs must contain a fragment, for example, <code>#/MyModule.pkl</code> in <code>package://example.com/package@1.0.0#/MyModule.pkl</code>.",
        holder
      )
      return true
    }
    if (!uri.fragment.startsWith('/')) {
      val offset = element.text.lastIndexOf('#') + 1
      createAnnotation(
        HighlightSeverity.WARNING,
        TextRange(0, uri.fragment.length).shiftRight(element.textOffset + offset),
        "Invalid fragment path",
        "Package imports must contain a fragment that starts with `/`.",
        holder
      )
      return true
    }
    val packageService = holder.currentProject.pklPackageService
    val packageUri = PackageUri(uri.authority, uri.path, version, checksum)
    val packageDependency = PackageDependency(packageUri, null, null)
    val roots = packageService.getLibraryRoots(packageDependency)
    if (roots == null) {
      buildAnnotation(
          HighlightSeverity.WARNING,
          element.stringConstant.content.textRange,
          "Missing package sources",
          "Missing sources for package <code>$packageUri</code>",
          holder
        )
        ?.apply { withFix(PklDownloadPackageAction(packageUri)) }
        ?.create()
      return true
    }
    return false
  }

  private fun checkDependencyNotation(
    element: PklModuleUri,
    reference: PsiReference,
    resolved: List<PsiElement>,
    holder: AnnotationHolder,
    context: PklProject?
  ): Boolean {
    if (!element.stringConstant.content.text.startsWith("@")) return false
    if (!element.isPhysical) return false
    if (resolved.isNotEmpty() && resolved.all { it.isInPackage }) return false

    val dependencyName = element.escapedContent?.substringBefore('/')?.drop(1) ?: return false
    if (resolved.isEmpty()) {
      val deps = element.enclosingModule?.dependencies(context) ?: return false
      if (deps.containsKey(dependencyName)) {
        buildAnnotation(
            HighlightSeverity.WARNING,
            reference.rangeInElement.shiftRight(element.textOffset),
            "Missing sources for dependency '$dependencyName'",
            "Missing sources for dependency <code>${dependencyName.escapeXml()}</code>",
            holder
          )
          ?.apply { withFix(PklDownloadDependencySourcesAction()) }
          ?.create()
        return true
      } else {
        createAnnotation(
          HighlightSeverity.WARNING,
          reference.rangeInElement.shiftRight(element.textOffset),
          "Cannot find dependency '$dependencyName'",
          "Cannot find dependency <code>${dependencyName.escapeXml()}</code>.",
          holder
        )
        return true
      }
    }
    resolved.singleOrNull()?.let { res ->
      if (res is PsiDirectory && res.name == "@$dependencyName") {
        buildAnnotation(
            HighlightSeverity.WARNING,
            reference.rangeInElement.shiftRight(element.textOffset),
            "Invalid path",
            """
              Paths starting with <code>@</code> is notation for importing a dependency.
            """
              .trimIndent(),
            holder
          )
          ?.apply { withFix(PklPrefixDependencyNotationQuickFix(element)) }
          ?.create()
        return true
      }
    }
    return false
  }

  private fun checkScheme(
    element: PklModuleUri,
    scheme: String,
    holder: AnnotationHolder
  ): Boolean {
    // only warn on known unglobbable schemes
    if (scheme == "pkl" || scheme == "http" || scheme == "https") {
      createAnnotation(
        HighlightSeverity.WARNING,
        element.stringConstant.textRange,
        "Scheme \"$scheme\" is not globbable",
        "Scheme \"$scheme\" is not globbable",
        holder,
        PklProblemGroups.unresolvedElement,
        element
      )
      return true
    }
    return false
  }

  private fun annotateGlobUri(
    element: PklModuleUri,
    references: Array<PsiReference>,
    uriText: String,
    holder: AnnotationHolder,
    context: PklProject?
  ) {
    val sourceFileUri = element.containingFile.virtualFile.url
    val scheme = parseUriOrNull(uriText)?.scheme ?: URI(sourceFileUri).scheme
    if (checkScheme(element, scheme, holder)) {
      return
    }
    if (uriText.startsWith("...")) {
      createAnnotation(
        HighlightSeverity.WARNING,
        TextRange(element.textRange.startOffset + 1, element.textRange.startOffset + 4),
        "Cannot combine triple-dot module URIs with glob imports",
        "Cannot combine triple-dot module URIs with glob imports",
        holder,
        PklProblemGroups.unresolvedElement,
        element
      )
      return
    }

    for ((index, reference) in references.withIndex()) {
      if (reference !is PklModuleUriReference) return
      val resolved = reference.resolveGlob(context) ?: return
      if (index == 0 && checkDependencyNotation(element, reference, resolved, holder, context)) {
        return
      }
      if (resolved.isEmpty()) {
        createAnnotation(
          HighlightSeverity.WARNING,
          reference.rangeInElement.shiftRight(element.textOffset),
          "Glob pattern has no matches",
          "Glob pattern has no matches",
          holder,
          PklProblemGroups.unresolvedElement,
          element
        )
      }
      if (index == references.lastIndex && resolved.any { it !is PklModule }) {
        createAnnotation(
          HighlightSeverity.WARNING,
          reference.rangeInElement.shiftRight(element.textOffset),
          "Some matched entries are not Pkl modules",
          "Some matched entries are not Pkl modules",
          holder,
          PklProblemGroups.unresolvedElement,
          element
        )
      }
    }
  }

  private fun annotateNonGlobUri(
    element: PklModuleUri,
    references: Array<PsiReference>,
    uriText: String,
    holder: AnnotationHolder,
    context: PklProject?
  ) {
    val project = holder.currentProject
    val base = project.pklBaseModule

    for ((index, reference) in references.withIndex()) {
      if (reference !is PklModuleUriReference) return
      if (index == 0) {
        // don't blame unresolved import on leading ".../"
        if (uriText.startsWith(".../")) {
          continue
        }
      }
      val resolved = reference.resolveContextual(context)
      if (
        index == 0 &&
          checkDependencyNotation(
            element,
            reference,
            resolved?.let { listOf(it) } ?: emptyList(),
            holder,
            context
          )
      ) {
        return
      }
      if (resolved == null) {
        createAnnotation(
          HighlightSeverity.WARNING,
          reference.rangeInElement.shiftRight(element.textOffset),
          "Unresolved import",
          "Unresolved import",
          holder,
          PklProblemGroups.unresolvedElement,
          element
        )
        continue
      }
      if (index == references.lastIndex) {
        if (resolved !is PklModule) {
          holder
            .newAnnotation(HighlightSeverity.ERROR, "Resolved import is not a Pkl module")
            .range(reference.rangeInElement.shiftRight(element.textOffset))
            .create()
          return
        }
        if (uriText.endsWith('/')) {
          val range = element.stringConstant.content.textRange
          holder
            .newAnnotation(HighlightSeverity.WARNING, "Module URI cannot have trailing slashes")
            .range(
              TextRange(range.startOffset + uriText.indexOfLast { it != '/' } + 1, range.endOffset)
            )
            .create()
          return
        }
        val projectPklVersion = base.pklVersion
        val minPklVersion = resolved.cache(context).minPklVersion ?: return
        checkPklVersion(minPklVersion, projectPklVersion, element, holder)
      }
    }
  }

  private fun checkPklVersion(
    moduleMinPklVersion: PklVersion,
    projectPklVersion: PklVersion,
    element: PklElement,
    holder: AnnotationHolder
  ) {

    if (moduleMinPklVersion > projectPklVersion) {
      createMismatchAnnotation(
        HighlightSeverity.WARNING,
        getHighlightRange(element),
        "Pkl version",
        "$moduleMinPklVersion or higher",
        projectPklVersion.toString(),
        holder,
        PklProblemGroups.pklVersionMismatch,
        element
      )
    }
  }

  private fun getHighlightRange(element: PklElement): TextRange {
    return when (element) {
      is PklModuleUri -> {
        // only highlight last part of module uri (the "module reference")
        val content = element.stringConstant.content
        val contentRange = content.textRange
        val contentText = content.text
        var index = contentText.lastIndexOf('/')
        if (index == -1) index = contentText.lastIndexOf(':')
        return TextRange(contentRange.startOffset + index + 1, contentRange.endOffset)
      }
      else -> element.textRange
    }
  }
}
