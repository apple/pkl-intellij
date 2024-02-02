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
import org.pkl.intellij.psi.PklClass
import org.pkl.intellij.psi.cache
import org.pkl.intellij.psi.pklStdLib

// https://www.jetbrains.org/intellij/sdk/docs/reference_guide/custom_language_support/go_to_class_and_go_to_symbol.html
// only show pkl.base symbols until we have a stub index
class PklGoToSymbolContributor : ChooseByNameContributorEx {
  override fun processNames(
    processor: Processor<in String>,
    scope: GlobalSearchScope,
    filter: IdFilter?
  ) {
    if (!scope.isSearchInLibraries) return

    val stdlib = scope.project?.pklStdLib ?: return
    for (module in stdlib.modules) {
      val modulePsi = module.psi
      if (modulePsi.isClassLike) processor.process(module.shortName)
      val moduleCache = modulePsi.cache

      for (name in moduleCache.typeDefsAndProperties.keys) processor.process(name)
      for (name in moduleCache.methods.keys) processor.process(name)

      for (typeDef in moduleCache.types.values) {
        if (typeDef is PklClass) {
          val classCache = typeDef.cache
          for (name in classCache.properties.keys) processor.process(name)
          for (name in classCache.methods.keys) processor.process(name)
        }
      }
    }
  }

  override fun processElementsWithName(
    name: String,
    processor: Processor<in NavigationItem>,
    parameters: FindSymbolParameters
  ) {
    if (!parameters.searchScope.isSearchInLibraries) return

    val stdlib = parameters.project.pklStdLib
    for (module in stdlib.modules) {
      val modulePsi = module.psi
      if (modulePsi.isClassLike) processor.process(modulePsi)
      val moduleCache = modulePsi.cache

      moduleCache.typeDefsAndProperties[name]?.let { processor.process(it) }
      moduleCache.methods[name]?.let { processor.process(it) }

      for (typeDef in moduleCache.types.values) {
        if (typeDef is PklClass) {
          val classCache = typeDef.cache
          classCache.properties[name]?.let { processor.process(it) }
          classCache.methods[name]?.let { processor.process(it) }
        }
      }
    }
  }
}
