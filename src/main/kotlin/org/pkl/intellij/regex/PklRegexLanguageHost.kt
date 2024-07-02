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
package org.pkl.intellij.regex

import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import java.lang.Character.UnicodeBlock
import java.lang.Character.UnicodeScript
import java.util.*
import org.intellij.lang.regexp.AsciiUtil
import org.intellij.lang.regexp.DefaultRegExpPropertiesProvider
import org.intellij.lang.regexp.RegExpLanguageHost
import org.intellij.lang.regexp.RegExpLanguageHost.Lookbehind
import org.intellij.lang.regexp.RegExpLanguageHost.Lookbehind.FINITE_REPETITION
import org.intellij.lang.regexp.UnicodeCharacterNames
import org.intellij.lang.regexp.psi.*
import org.intellij.lang.regexp.psi.RegExpBoundary.Type.RESET_MATCH
import org.intellij.lang.regexp.psi.RegExpGroup.Type
import org.intellij.lang.regexp.psi.RegExpGroup.Type.NAMED_GROUP
import org.intellij.lang.regexp.psi.RegExpSimpleClass.Kind.*

/**
 * Based on:
 * https://github.com/JetBrains/intellij-community/blob/master/java/java-impl/src/com/intellij/psi/impl/JavaRegExpHost.java
 */
class PklRegexLanguageHost : RegExpLanguageHost {
  companion object {
    private val SUPPORTED_NAMED_GROUP_TYPES = EnumSet.of(NAMED_GROUP)
  }

  override fun supportsInlineOptionFlag(flag: Char, context: PsiElement): Boolean =
    when (flag) {
      'i',
      'd',
      'm',
      's',
      'u',
      'x',
      'U' -> true
      else -> false
    }

  override fun characterNeedsEscaping(c: Char, isInClass: Boolean): Boolean = false

  override fun supportsNamedCharacters(namedCharacter: RegExpNamedCharacter): Boolean =
    true // Java 1.9+

  override fun supportsPerl5EmbeddedComments(): Boolean = false

  override fun supportsPossessiveQuantifiers(): Boolean = true

  override fun supportsPythonConditionalRefs(): Boolean = false

  override fun supportsNamedGroupSyntax(group: RegExpGroup): Boolean = group.type == NAMED_GROUP

  override fun supportsNamedGroupRefSyntax(ref: RegExpNamedGroupRef): Boolean = ref.isNamedGroupRef

  override fun getSupportedNamedGroupTypes(context: RegExpElement): EnumSet<Type> =
    SUPPORTED_NAMED_GROUP_TYPES

  override fun isValidGroupName(name: String, group: RegExpGroup): Boolean =
    name.all { AsciiUtil.isLetterOrDigit(it) }

  override fun supportsExtendedHexCharacter(regExpChar: RegExpChar): Boolean =
    regExpChar.unescapedText[1] == 'x'

  // UNICODE_EXTENDED_GRAPHEME is Java 1.9+
  override fun supportsBoundary(boundary: RegExpBoundary): Boolean = boundary.type != RESET_MATCH

  // UNICODE_GRAPHEME is Java 1.9+
  override fun supportsSimpleClass(simpleClass: RegExpSimpleClass): Boolean =
    when (simpleClass.kind) {
      XML_NAME_START,
      NON_XML_NAME_START,
      XML_NAME_PART,
      NON_XML_NAME_PART -> false
      else -> true
    }

  override fun supportsLiteralBackspace(aChar: RegExpChar): Boolean = false

  override fun isValidCategory(category: String): Boolean {
    return when {
      category.startsWith("In") -> isValidUnicodeBlock(category)
      category.startsWith("Is") -> {
        var theCategory = category.substring(2)
        if (isValidProperty(theCategory)) return true
        // Unicode properties and scripts available since JDK 1.7
        theCategory = StringUtil.toUpperCase(category)
        when (theCategory) {
          "WHITESPACE",
          "HEXDIGIT",
          "NONCHARACTERCODEPOINT",
          "JOINCONTROL",
          "ALPHABETIC",
          "LETTER",
          "IDEOGRAPHIC",
          "LOWERCASE",
          "UPPERCASE",
          "TITLECASE",
          "WHITE_SPACE",
          "CONTROL",
          "PUNCTUATION",
          "HEX_DIGIT",
          "ASSIGNED",
          "NONCHARACTER_CODE_POINT",
          "DIGIT",
          "ALNUM",
          "BLANK",
          "GRAPH",
          "PRINT",
          "WORD",
          "JOIN_CONTROL" -> true
          else -> isValidUnicodeScript(theCategory)
        }
      }
      else -> isValidProperty(category)
    }
  }

  private fun isValidProperty(category: String): Boolean =
    allKnownProperties.any { it[0] == category }

  private fun isValidUnicodeBlock(category: String): Boolean =
    try {
      UnicodeBlock.forName(category.substring(2)) != null
    } catch (e: IllegalArgumentException) {
      false
    }

  private fun isValidUnicodeScript(category: String): Boolean =
    try {
      UnicodeScript.forName(category) != null
    } catch (e: IllegalArgumentException) {
      false
    }

  override fun isValidNamedCharacter(namedCharacter: RegExpNamedCharacter): Boolean =
    UnicodeCharacterNames.getCodePoint(namedCharacter.name) >= 0

  override fun supportsLookbehind(lookbehindGroup: RegExpGroup): Lookbehind = FINITE_REPETITION

  override fun getQuantifierValue(number: RegExpNumber): Int? = number.unescapedText.toIntOrNull()

  override fun getAllKnownProperties(): Array<Array<String>> =
    DefaultRegExpPropertiesProvider.getInstance().allKnownProperties

  override fun getPropertyDescription(name: String?): String? =
    DefaultRegExpPropertiesProvider.getInstance().getPropertyDescription(name)

  override fun getKnownCharacterClasses(): Array<Array<String>> =
    DefaultRegExpPropertiesProvider.getInstance().knownCharacterClasses
}
