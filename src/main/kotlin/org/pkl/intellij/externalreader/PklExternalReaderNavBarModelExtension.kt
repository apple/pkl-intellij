/**
 * Copyright © 2026 Apple Inc. and the Pkl project authors. All rights reserved.
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
package org.pkl.intellij.externalreader

import com.intellij.ide.navigationToolbar.AbstractNavBarModelExtension
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory

/**
 * Makes the navigation bar show external-reader modules relative to their `scheme://authority`
 * instead of relative to the on-disk cache directory.
 */
class PklExternalReaderNavBarModelExtension : AbstractNavBarModelExtension() {
  // Treating the scheme/authority directories as roots stops the nav bar from walking further up.
  override fun additionalRoots(project: Project): Collection<VirtualFile> =
    project.pklExternalReaderService.externalRoots()

  override fun getPresentableText(`object`: Any?): String? {
    val dir = (`object` as? PsiDirectory)?.virtualFile ?: return null
    val service = `object`.project.pklExternalReaderService
    // The nav bar builds its items as soon as a file is shown. Use that as a cue to fetch the rest
    // of the directory in the background, so that clicking on a parent shows all of its children
    // (the popup can only list what's in the VFS, and building it can't wait for a write action).
    if (service.handlesFile(dir)) {
      service.ensureListed(dir)
    }
    return service.rootPresentableName(dir)
  }
}
