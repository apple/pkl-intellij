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

import java.util.*

/**
 * A Pkl language version. Follows semantic version rules.
 *
 * Originally copied from Pkl codebase.
 */
class PklVersion(
  val major: Int,
  val minor: Int,
  val patch: Int,
  val preReleaseLabel: String?,
  @Suppress("MemberVisibilityCanBePrivate") val buildMetadata: String?
) : Comparable<PklVersion> {
  override operator fun compareTo(other: PklVersion): Int = COMPARATOR.compare(this, other)

  override fun equals(other: Any?): Boolean =
    this === other || other is PklVersion && COMPARATOR.compare(this, other) == 0

  override fun hashCode(): Int {
    var result = major
    result = 31 * result + minor
    result = 31 * result + patch
    result = 31 * result + preReleaseLabel.hashCode()
    return result
  }

  override fun toString(): String {
    return "$major.$minor.$patch" +
      (if (preReleaseLabel != null) "-$preReleaseLabel" else "") +
      if (buildMetadata != null) "+$buildMetadata" else ""
  }

  companion object {
    private val REGEX =
      Regex("(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z-.]+))?(?:\\+([0-9A-Za-z-.]+))?")

    val COMPARATOR: Comparator<PklVersion> =
      compareBy(PklVersion::major, PklVersion::minor, PklVersion::patch) then
        nullsLast(compareBy(PklVersion::preReleaseLabel))

    val VERSION_0_25: PklVersion = PklVersion(0, 25, 0, null, null)
    val VERSION_0_26: PklVersion = PklVersion(0, 26, 0, null, null)

    fun parse(versionString: String): PklVersion? {
      val result = REGEX.matchEntire(versionString) ?: return null
      return PklVersion(
        result.groups[1]!!.value.toInt(),
        result.groups[2]!!.value.toInt(),
        result.groups[3]!!.value.toInt(),
        result.groups[4]?.value,
        result.groups[5]?.value
      )
    }
  }
}
