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
package org.pkl.intellij

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.WritingAccessProvider
import org.pkl.intellij.externalreader.PklExternalReaderService

class PklWritingAccessProvider : WritingAccessProvider() {
  private fun isExternalReaderFile(file: VirtualFile): Boolean =
    file.fileSystem.getNioPath(file)?.startsWith(PklExternalReaderService.cacheDirBase) == true

  override fun isPotentiallyWritable(file: VirtualFile): Boolean {
    return super.isPotentiallyWritable(file) && !isExternalReaderFile(file)
  }

  /** Returns the files that may not be modified. */
  override fun requestWriting(files: Collection<VirtualFile>): Collection<VirtualFile> {
    return files.filter { isExternalReaderFile(it) }
  }
}
