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

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.impl.FileTypeOverrider
import com.intellij.openapi.vfs.VirtualFile
import org.pkl.intellij.PklFileType
import org.pkl.intellij.externalreader.PklExternalReaderService.Companion.OPAQUE_DIR

/**
 * Identifies modules of external readers with non-hierarchical URIs (e.g. `mylib:foo.bar`) as Pkl
 * files.
 */
@Suppress("UnstableApiUsage")
class PklExternalReaderFileTypeOverrider : FileTypeOverrider {
  override fun getOverriddenFileType(file: VirtualFile): FileType? {
    if (file.isDirectory) return null
    val path = file.fileSystem.getNioPath(file) ?: return null
    if (!path.startsWith(PklExternalReaderService.cacheDirBase)) return null
    // <project cache dir>/<reader id>/<scheme>/OPAQUE/<scheme-specific part>
    val relative = PklExternalReaderService.cacheDirBase.relativize(path)
    val isOpaqueModule = relative.nameCount > 4 && relative.getName(3).toString() == OPAQUE_DIR
    return if (isOpaqueModule) PklFileType else null
  }
}
