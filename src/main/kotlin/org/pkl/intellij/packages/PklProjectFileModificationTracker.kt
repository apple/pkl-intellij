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

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import java.util.concurrent.atomic.AtomicLong
import org.pkl.intellij.PklFileType
import org.pkl.intellij.packages.PklProjectService.Companion.PKL_PROJECT_FILENAME

fun Project.pklProjectModificationTracker(): PklProjectFileModificationTracker = service()

/**
 * Keeps track of add/delete/move/property change events for PklProject files within the project.
 */
@Service(Service.Level.PROJECT)
class PklProjectFileModificationTracker(project: Project) : ModificationTracker {
  init {
    project.messageBus
      .connect()
      .subscribe(
        VirtualFileManager.VFS_CHANGES,
        object : BulkFileListener {
          override fun after(events: List<VFileEvent>) {
            for (event in events) {
              if (event is VFileContentChangeEvent) continue
              if (event.file?.fileType == PklFileType && event.file?.name == PKL_PROJECT_FILENAME) {
                modificationCount.getAndIncrement()
              }
            }
          }
        }
      )
  }

  private val modificationCount: AtomicLong = AtomicLong(0)

  override fun getModificationCount(): Long = modificationCount.get()
}
