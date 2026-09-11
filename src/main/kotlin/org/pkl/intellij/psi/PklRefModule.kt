/**
 * Copyright © 2024-2026 Apple Inc. and the Pkl project authors. All rights reserved.
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
package org.pkl.intellij.psi

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import org.pkl.intellij.type.Type

/**
 * The `pkl:ref` module.
 *
 * `null` if less than Pkl 0.32
 */
val Project.pklRefModule: PklRefModule?
  get() =
    CachedValuesManager.getManager(this).getCachedValue(this) {
      val stdLibModule = pklStdLib.refModule
      when {
        stdLibModule == null -> CachedValueProvider.Result.create(null)
        else -> {
          // Invalidate [PklRefModule] on any change to [rootManager], i.e., any change to a
          // project root.
          val dependencies = listOfNotNull(ProjectRootManager.getInstance(this), stdLibModule.psi)
          CachedValueProvider.Result.create(
            PklRefModule(stdLibModule),
            *dependencies.toTypedArray()
          )
        }
      }
    }

class PklRefModule(refModule: PklStdLibModule) {
  val psi: PklModule = refModule.psi

  val types: Map<String, Type> = buildMap {
    for (member in psi.members) {
      if (member is PklClass) {
        put(member.name!!, Type.Class.create(member))
      }
    }
  }

  val referenceType: Type.Reference by lazy { types["Reference"] as Type.Reference }
}
