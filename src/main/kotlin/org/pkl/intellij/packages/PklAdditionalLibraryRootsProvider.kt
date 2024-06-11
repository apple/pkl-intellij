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

import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.AdditionalLibraryRootsProvider
import com.intellij.openapi.roots.SyntheticLibrary
import com.intellij.openapi.vfs.VirtualFile
import javax.swing.Icon
import org.pkl.intellij.PklIcons
import org.pkl.intellij.packages.dto.PackageMetadata
import org.pkl.intellij.util.editorSupportDir
import org.pkl.intellij.util.packagesCacheDir

data class PklLibrary(
  val packageDependency: PackageDependency,
  val metadata: PackageMetadata,
  val project: Project
) : SyntheticLibrary(), ItemPresentation {
  override fun getSourceRoots(): Collection<VirtualFile> {
    val roots = project.pklPackageService.getLibraryRoots(packageDependency) ?: return emptyList()
    return listOfNotNull(roots.packageRoot, roots.metadataFile)
  }

  override fun getPresentableText(): String = "${metadata.name} ${metadata.version}"

  override fun getLocationString(): String = packageDependency.packageUri.toString()

  override fun getIcon(unused: Boolean): Icon = PklIcons.PACKAGE
}

class PklAdditionalLibraryRootsProvider : AdditionalLibraryRootsProvider() {
  private fun PackageDependency.toPklLibrary(project: Project): PklLibrary? {
    val metadata = project.pklPackageService.getPackageMetadata(this) ?: return null
    return PklLibrary(this, metadata, project)
  }

  override fun getAdditionalProjectLibraries(project: Project): Collection<SyntheticLibrary> {
    // TODO In IDEA's project view, it'd be nice to group packages that belong to a pkl project
    // underneath its own line
    // item.
    // Seems like it might be possible; for example, Go dependencies are grouped under "Go Modules".
    return project.pklPackageService
      .allPackages()
      .distinctBy { it.packageUri }
      .mapNotNull { it.toPklLibrary(project) }
  }

  override fun getRootsToWatch(project: Project): Collection<VirtualFile> {
    return listOfNotNull(packagesCacheDir, editorSupportDir)
  }
}
