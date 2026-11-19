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

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import java.io.IOException
import java.net.URI
import java.nio.file.Path
import java.util.Collections.newSetFromMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.absolutePathString
import org.pkl.intellij.externalreader.PklExternalReaderService.Companion.NO_AUTHORITY
import org.pkl.intellij.externalreader.PklExternalReaderService.Companion.OPAQUE_DIR
import org.pkl.intellij.packages.localFs
import org.pkl.intellij.util.encodePath
import org.pkl.messaging.PathElement
import org.pkl.messaging.externalreader.ExternalReader
import org.pkl.messaging.externalreader.ExternalReaderProcess
import org.pkl.messaging.externalreader.ExternalReaderProcessException
import org.pkl.messaging.externalreader.ModuleReaderSpec

/**
 * Everything that's specific to one reader configuration (for one scheme): its spec, its cache
 * directory, and the bookkeeping of what was fetched.
 */
internal class ReaderState(
  val scheme: String,
  val reader: ExternalReader,
  val processes: MutableMap<ExternalReader, ExternalReaderProcess>,
  cacheDir: Path,
) {
  val id: String = Integer.toHexString(reader.hashCode())

  /** `<cacheDir>/<reader id>/<scheme>` */
  val schemeDir: Path = cacheDir.resolve(id).resolve(scheme)

  val process: ExternalReaderProcess
    get() = processes.computeIfAbsent(reader) { ExternalReaderProcess.of(it) }

  @Volatile private var cachedSpec: ModuleReaderSpec? = null
  val wasChildrenListed: MutableSet<URI> = newSetFromMap(ConcurrentHashMap())
  val listings: MutableMap<URI, List<PathElement>> = ConcurrentHashMap()
  val urisByCachePath: MutableMap<Path, URI> = ConcurrentHashMap()
  val inFlight: MutableSet<URI> = newSetFromMap(ConcurrentHashMap())

  /** URIs that failed to be read; these aren't tried again until the caches are invalidated. */
  val failed: MutableSet<URI> = newSetFromMap(ConcurrentHashMap())

  /** Talks to the reader process, so don't call this in time-sensitive places. */
  fun spec(): ModuleReaderSpec? {
    cachedSpec?.let {
      return it
    }
    return process.getModuleReaderSpec(scheme)?.also { cachedSpec = it }
  }

  fun cachePath(uri: URI): Path {
    // Opaque URIs (e.g. `mylib:foo.bar`) have no authority or path; they're a flat namespace.
    if (uri.isOpaque) {
      return schemeDir.resolve(OPAQUE_DIR).resolve(encodePath(uri.schemeSpecificPart))
    }
    return schemeDir
      .resolve(uri.authority ?: NO_AUTHORITY)
      .resolve(encodePath(uri.path.removePrefix("/")))
  }

  /**
   * The URI that's sent to the reader to list *every* module of a scheme with non-hierarchical URIs
   * (similar to how `pkl:` lists every stdlib module).
   */
  val opaqueListingUri: URI = URI("$scheme:dummy")

  /**
   * Asks the reader for [baseUri]'s children.
   *
   * An [IOException] or [ExternalReaderProcessException] (the reader can't list that URI, or its
   * process can't be started or is gone) is terminal: the result is remembered as empty until the
   * caches are invalidated. Any other exception is logged, and the listing is tried again next
   * time.
   */
  fun listElements(baseUri: URI): List<PathElement> {
    listings[baseUri]?.let {
      return it
    }
    return try {
      ProgressManager.checkCanceled()
      process.getModuleResolver(0).listElements(baseUri).also { listings[baseUri] = it }
    } catch (e: ProcessCanceledException) {
      throw e
    } catch (e: IOException) {
      failListing(baseUri, e)
    } catch (e: ExternalReaderProcessException) {
      failListing(baseUri, e)
    } catch (e: Exception) {
      thisLogger().warn("Failed to list elements of $baseUri; will try again", e)
      emptyList()
    }
  }

  private fun failListing(baseUri: URI, e: Exception): List<PathElement> {
    thisLogger()
      .warn("Failed to list elements of $baseUri (won't retry until cleared): ${e.message}")
    listings[baseUri] = emptyList()
    return emptyList()
  }

  fun isOpaque(): Boolean = spec()?.hasHierarchicalUris == false

  fun listOpaqueModules(): List<URI> =
    listElements(opaqueListingUri).filter { !it.isDirectory }.map { URI(scheme, it.name, null) }

  /**
   * Reads [uri] from the reader into the cache.
   *
   * If the read fails with an [IOException] (the reader says it can't read that module, or the
   * cache can't be written) or an [ExternalReaderProcessException] (the reader's process can't be
   * started or is gone), the failure is permanent: the URI is remembered in [failed] and not tried
   * again until the caches are invalidated. Any other exception is propagated; it's logged by the
   * caller and the module will be tried again next time it's needed.
   */
  fun fetch(uri: URI) {
    if (uri in failed) {
      inFlight.remove(uri)
      return
    }
    try {
      val resolved = process.getModuleResolver(1).resolveModule(uri)
      val path = cachePath(uri)
      urisByCachePath[path] = uri
      writeToCache(path, resolved)
    } catch (e: IOException) {
      failFetch(uri, e)
    } catch (e: ExternalReaderProcessException) {
      failFetch(uri, e)
    } finally {
      inFlight.remove(uri)
    }
  }

  private fun failFetch(uri: URI, e: Exception) {
    failed.add(uri)
    thisLogger()
      .warn("Failed to read $uri from external reader (won't retry until cleared): ${e.message}")
  }

  private fun writeToCache(path: Path, text: String): VirtualFile =
    WriteAction.computeAndWait<VirtualFile, IOException> {
      val directory =
        VfsUtil.createDirectoryIfMissing(localFs, path.parent.absolutePathString())
          ?: throw IOException("Failed to create directory ${path.parent}")
      val file =
        directory.findChild(path.fileName.toString())
          ?: directory.createChildData(this, path.fileName.toString())
      VfsUtil.saveText(file, text)
      file
    }
}
