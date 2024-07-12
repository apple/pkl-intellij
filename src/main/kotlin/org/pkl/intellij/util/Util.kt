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
package org.pkl.intellij.util

import com.intellij.codeInsight.intention.impl.BaseIntentionAction
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.openapi.module.ModuleUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.OrderEnumerator
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import java.math.BigInteger
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Path
import java.util.*
import java.util.regex.Pattern
import kotlin.math.max
import org.pkl.intellij.packages.dto.PklProject
import org.pkl.intellij.packages.pklProjectService
import org.pkl.intellij.psi.*

private const val SIGNIFICAND_MASK = 0x000fffffffffffffL

private const val SIGNIFICAND_BITS = 52

private const val IMPLICIT_BIT: Long = SIGNIFICAND_MASK + 1

val pklDir: VirtualFile?
  get() = VfsUtil.getUserHomeDir()?.findChild(".pkl")

val pklCacheDir: VirtualFile?
  get() = pklDir?.findChild("cache")

interface CacheDir {
  val file: VirtualFile

  fun resolve(path: String): VirtualFile?
}

data class Package2CacheDir(override val file: VirtualFile) : CacheDir {
  override fun resolve(path: String): VirtualFile? {
    return file.findFileByRelativePath(encodePath(path))
  }
}

data class Package1CacheDir(override val file: VirtualFile) : CacheDir {
  override fun resolve(path: String): VirtualFile? {
    return file.findFileByRelativePath(path)
  }
}

val packages2CacheDir: CacheDir?
  get() = pklCacheDir?.findChild("package-2")?.let { Package2CacheDir(it) }

val packages1CacheDir: CacheDir?
  get() = pklCacheDir?.findChild("package-1")?.let { Package1CacheDir(it) }

@Suppress("UNUSED_PARAMETER")
fun escapeString(content: String, startDelimiter: String = "\""): String {
  return content // TODO
}

fun isMathematicalInteger(x: Double): Boolean {
  val exponent = StrictMath.getExponent(x)
  return (exponent <= java.lang.Double.MAX_EXPONENT &&
    (x == 0.0 ||
      SIGNIFICAND_BITS - java.lang.Long.numberOfTrailingZeros(getSignificand(x)) <= exponent))
}

fun appendNumericSuffix(name: String): String =
  when (name.last()) {
    in '0'..'9' -> {
      val number = name.takeLastWhile { it in '0'..'9' }
      name.substring(0, name.length - number.length) + (number.toInt() + 1)
    }
    else -> name + '2'
  }

fun inferImportPropertyName(moduleUriStr: String): String? {
  val moduleUri =
    try {
      URI(moduleUriStr)
    } catch (e: URISyntaxException) {
      return null
    }

  if (moduleUri.isOpaque) {
    // convention: take last segment of dot-separated name after stripping any colon-separated
    // version number
    return takeLastSegment(dropLastSegment(moduleUri.schemeSpecificPart, ':'), '.')
  }
  if (moduleUri.scheme == "package") {
    return moduleUri.fragment?.let(::getNameWithoutExtension)
  }
  if (moduleUri.isAbsolute) {
    return getNameWithoutExtension(moduleUri.path)
  }
  return getNameWithoutExtension(moduleUri.schemeSpecificPart)
}

fun unexpectedType(obj: Any?): Nothing {
  throw UnexpectedTypeError(obj?.javaClass?.typeName ?: "null")
}

/**
 * If [this] belongs to an IDE module, returns the source and classes roots visible to that module.
 * Otherwise, returns all modules' source and classes roots.
 */
fun PsiFile.findSourceAndClassesRoots(): Array<VirtualFile> {
  val ideModule = ModuleUtil.findModuleForFile(this)
  return if (ideModule != null) {
    OrderEnumerator.orderEntries(ideModule)
      .recursively()
      .withoutSdk()
      .withoutLibraries()
      .sourceRoots +
      OrderEnumerator.orderEntries(ideModule)
        .recursively()
        .withoutSdk()
        .withoutModuleSourceEntries()
        .classesRoots
  } else {
    val ideProject = project // only compute once
    // .recursively() has no effect here
    OrderEnumerator.orderEntries(ideProject).withoutSdk().withoutLibraries().sourceRoots +
      OrderEnumerator.orderEntries(ideProject)
        .withoutSdk()
        .withoutModuleSourceEntries()
        .classesRoots
  }
}

fun CharSequence.escapeXml(): String = StringUtil.escapeXmlEntities(this.toString())

fun String.decapitalized() = replaceFirstChar { it.lowercase(Locale.getDefault()) }

fun String.capitalized() = replaceFirstChar {
  if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
}

inline fun <T> T.code(action: Appendable.() -> Unit): T where T : Appendable {
  notEscapeXml { append("<code>") }
  action()
  notEscapeXml { append("</code>") }
  return this
}

inline fun <T> T.escapeXml(action: Appendable.() -> Unit): T where T : Appendable {
  action(if (this is XmlEscapingAppendable) this else XmlEscapingAppendable(this))
  return this
}

inline fun <T> T.notEscapeXml(action: Appendable.() -> Unit): T where T : Appendable {
  action(if (this is XmlEscapingAppendable) this.delegate else this)
  return this
}

inline fun <reified T> Sequence<T>.toTypedArray(): Array<T> {
  val size = count()
  val iter = iterator()
  return Array(size) { iter.next() }
}

private fun getSignificand(d: Double): Long {
  val exponent = StrictMath.getExponent(d)
  assert(exponent <= java.lang.Double.MAX_EXPONENT)
  var bits = java.lang.Double.doubleToRawLongBits(d)
  bits = bits and SIGNIFICAND_MASK
  return if (exponent == java.lang.Double.MIN_EXPONENT - 1) bits shl 1 else bits or IMPLICIT_BIT
}

val AnnotationHolder.currentFile: PsiFile
  get() = currentAnnotationSession.file

val AnnotationHolder.currentProject: Project
  get() = currentFile.project

val AnnotationHolder.currentModule: PklModule
  get() = currentFile as PklModule

/**
 * Not sure what the difference between `BaseIntentionAction.canModify()` and
 * `PsiElement.isWritable()` is. Judging from the name, the former seems more appropriate for quick
 * fixes.
 */
fun PsiElement.canModify(): Boolean = BaseIntentionAction.canModify(this)

private fun getNameWithoutExtension(path: String): String {
  val lastSep = max(path.lastIndexOf('/'), path.lastIndexOf('\\'))
  val lastDot = path.lastIndexOf('.')
  return if (lastDot == -1 || lastDot < lastSep) path.substring(lastSep + 1)
  else path.substring(lastSep + 1, lastDot)
}

private fun takeLastSegment(name: String, separator: Char): String {
  val lastSep = name.lastIndexOf(separator)
  return name.substring(lastSep + 1)
}

private fun dropLastSegment(name: String, separator: Char): String {
  val lastSep = name.lastIndexOf(separator)
  return if (lastSep == -1) name else name.substring(0, lastSep)
}

class UnexpectedTypeError(message: String) : AssertionError(message)

class XmlEscapingAppendable(val delegate: Appendable) : Appendable {
  override fun append(csq: CharSequence): java.lang.Appendable {
    delegate.append(StringUtil.escapeXmlEntities(csq.toString()))
    return this
  }

  override fun append(csq: CharSequence, start: Int, end: Int): java.lang.Appendable {
    delegate.append(StringUtil.escapeXmlEntities(csq.subSequence(start, end).toString()))
    return this
  }

  override fun append(c: Char): java.lang.Appendable {
    when (c) {
      '<' -> delegate.append("&lt;")
      '>' -> delegate.append("&gt;")
      '&' -> delegate.append("&amp;")
      '\'' -> delegate.append("&#39;")
      '"' -> delegate.append("&quot;")
      else -> delegate.append(c)
    }
    return this
  }
}

private val absoluteUriLike = Pattern.compile("\\w+:.*")

fun isAbsoluteUriLike(uriStr: String): Boolean = absoluteUriLike.matcher(uriStr).matches()

fun parseUriOrNull(uriStr: String): URI? =
  try {
    if (isAbsoluteUriLike(uriStr)) URI(uriStr) else URI(null, null, uriStr, null)
  } catch (_: URISyntaxException) {
    null
  }

fun Path.dropRoot(): Path = toAbsolutePath().let { it.subpath(0, it.count()) }

/**
 * Windows reserves characters `<>:"\|?*` in filenames.
 *
 * For any such characters, enclose their decimal character code with parentheses. Verbatim `(` is
 * encoded as `((`.
 *
 * This code is copied from `org.pkl.core.util.IoUtils.encodePath()`.
 */
fun encodePath(path: String): String {
  if (path.isEmpty()) return path
  return buildString {
    for (i in path.indices) {
      when (val character = path[i]) {
        '<',
        '>',
        ':',
        '"',
        '\\',
        '|',
        '?',
        '*' -> {
          append('(')
          append(BigInteger(byteArrayOf(character.code.toByte())).toString(16))
          append(")")
        }
        '(' -> append("((")
        else -> append(path[i])
      }
    }
  }
}

/** Decodes a path encoded with [encodePath]. */
fun decodePath(path: String): String {
  if (path.isEmpty()) return path
  return buildString {
    var i = 0
    while (i < path.length) {
      val character = path[i]
      if (character == '(') {
        require(i != path.length - 1) { "Invalid path encoding: $path" }
        i++
        var nextCharacter = path[i]
        if (nextCharacter == '(') {
          append('(')
          i++
          continue
        }
        require(nextCharacter != ')') { "Invalid path encoding: $path" }
        val codePointBuilder = StringBuilder()
        while (nextCharacter != ')') {
          when (nextCharacter) {
            '0',
            '1',
            '2',
            '3',
            '4',
            '5',
            '6',
            '7',
            '8',
            '9',
            'a',
            'b',
            'c',
            'd',
            'e',
            'f' -> codePointBuilder.append(nextCharacter)
            else -> throw IllegalArgumentException("Invalid path encoding: $path")
          }
          i++
          require(i != path.length) { "Invalid path encoding: $path" }
          nextCharacter = path[i]
        }
        append(codePointBuilder.toString().toInt(16).toChar())
      } else {
        append(character)
      }
      i++
    }
  }
}

/** Cache values in terms of the given [context]. */
fun <T> PklElement.getContextualCachedValue(
  context: PklProject?,
  provider: CachedValueProvider<T>
): T {
  val manager = CachedValuesManager.getManager(project)
  // Optimization: if the context is null, or if this element is not in a project or a package, no
  // need to create a new CachedValueProvider.
  if (
    context == null || (enclosingModule?.isInPackage ?: enclosingModule?.isInPklProject) != true
  ) {
    return manager.getCachedValue(this, Key.create("static"), provider, false)
  }
  return CachedValuesManager.getManager(project)
    .getCachedValue(
      this,
      Key.create("${context.projectFile}-cache"),
      {
        val result = provider.compute() ?: return@getCachedValue null
        CachedValueProvider.Result.create(
          result.value,
          project.pklProjectService,
          *result.dependencyItems
        )
      },
      false
    )
}
