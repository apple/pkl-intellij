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
package org.pkl.intellij

import com.intellij.navigation.ChooseByNameContributorEx
import com.intellij.navigation.NavigationItem
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.Processor
import com.intellij.util.indexing.FindSymbolParameters
import com.intellij.util.indexing.IdFilter
import org.pkl.intellij.psi.pklStdLib

// https://www.jetbrains.org/intellij/sdk/docs/reference_guide/custom_language_support/go_to_class_and_go_to_symbol.html
class PklGoToClassContributor : ChooseByNameContributorEx {
  override fun processNames(
    processor: Processor<in String>,
    scope: GlobalSearchScope,
    filter: IdFilter?
  ) {
    // need a class index to suggest project and cached pkl hub (module) classes

    if (scope.isSearchInLibraries) {
      val stdlib = scope.project?.pklStdLib ?: return
      for (module in stdlib.modules) {
        if (module.psi.isClassLike) processor.process(module.shortName)
        val cache = module.psi.cache
        for ((typeName, _) in cache.types) processor.process(typeName)
      }
    }
  }

  override fun processElementsWithName(
    name: String,
    processor: Processor<in NavigationItem>,
    parameters: FindSymbolParameters
  ) {
    if (parameters.searchScope.isSearchInLibraries) {
      val stdlib = parameters.project.pklStdLib
      for (module in stdlib.modules) {
        val modulePsi = module.psi
        if (module.shortName == name && modulePsi.isClassLike) processor.process(modulePsi)
        val cache = modulePsi.cache
        cache.types[name]?.let { processor.process(it) }
      }
    }
  }
}
