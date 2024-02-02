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
package org.pkl.intellij.settings

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project

val Project.pklSettings: PklSettingsService
  get() = PklSettingsService.getInstance(this)

@Service
@State(name = "PklSettings", storages = [Storage("pklSettings.xml")])
class PklSettingsService : PersistentStateComponent<PklSettingsService.State> {
  companion object {
    fun getInstance(project: Project): PklSettingsService {
      return project.getService(PklSettingsService::class.java)
    }
  }

  private var state = State()

  override fun getState(): State = state

  override fun loadState(state: State) {
    this.state = state
  }

  data class State(var pklPath: String = "")
}
