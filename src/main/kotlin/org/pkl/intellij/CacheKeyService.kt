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

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.psi.util.CachedValuesManager
import com.jetbrains.rd.util.concurrentMapOf
import org.pkl.intellij.packages.dto.PklProject

val Project.cacheKeyService: CacheKeyService
  get() = service()

/**
 * The [CachedValuesManager] API treats keys of the same name as _different_ keys. Calling
 * `getCachedValue(elem, Key.create("foo"), doCompute)` multiple times will result in cache misses
 * every time, and a new result is created.
 *
 * To mitigate, we keep track of previously created keys by placing them in our own store.
 */
@Service(Service.Level.PROJECT)
class CacheKeyService : Disposable {
  private val cacheKeys: MutableMap<String, Key<*>> = concurrentMapOf()

  @Suppress("UNCHECKED_CAST")
  private fun <T> doGetKey(name: String): Key<T> {
    return cacheKeys.computeIfAbsent(name) { Key.create<Any>(it) } as Key<T>
  }

  fun <T> getKey(methodName: String, context: PklProject?): Key<T> =
    if (context == null) doGetKey("$methodName-static")
    else doGetKey("$methodName-${context.projectFile}")

  fun <T> getKey(methodName: String, name: String): Key<T> = doGetKey("$methodName-$name")

  override fun dispose() = cacheKeys.clear()
}
