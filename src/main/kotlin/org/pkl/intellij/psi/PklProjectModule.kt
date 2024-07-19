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
package org.pkl.intellij.psi

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import org.pkl.intellij.type.Type

val Project.pklProjectModule: PklProjectModule
  get() =
    CachedValuesManager.getManager(this).getCachedValue(this) {
      val stdLib = pklStdLib
      CachedValueProvider.Result.create(
        PklProjectModule(stdLib),
        // Invalidate [PklProjectModule] on any change to [rootManager], i.e., any change to a
        // project root.
        // (Is there a better way to track class roots affecting pkl.base?)
        // Additionally track changes to the [projectModule] PSI (not sure if this makes a
        // difference).
        ProjectRootManager.getInstance(this),
        stdLib.projectModule.psi
      )
    }

class PklProjectModule(stdLib: PklStdLib) {
  val psi: PklModule = stdLib.projectModule.psi

  val types: Map<String, Type> = buildMap {
    for (member in psi.members) {
      if (member is PklClass) {
        put(member.name!!, Type.Class(member))
      }
    }
    put("Project", Type.module(psi, "pkl.Project", context = null))
  }

  val projectType by lazy { types["Project"] as Type.Module }
}
