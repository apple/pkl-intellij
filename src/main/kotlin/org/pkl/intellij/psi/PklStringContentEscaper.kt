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

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.TextRange
import com.intellij.psi.LiteralTextEscaper
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.TokenType
import java.lang.IllegalArgumentException
import kotlin.math.max
import kotlin.math.min

class PklStringContentEscaper(private val content: PklStringContentBase) :
  LiteralTextEscaper<PsiLanguageInjectionHost>(content) {

  companion object {
    private val logger = logger<PklStringContentEscaper>()
  }

  // non-zero length differences between original and decoded text at various decoded text offsets
  private var lengthDiffs: List<Pair<Int, Int>> = listOf()

  // not currently supporting multi-line strings
  override fun isOneLine(): Boolean = true

  override fun getOffsetInHost(offsetInDecoded: Int, rangeInsideHost: TextRange): Int {
    var result = offsetInDecoded
    for ((offset, diff) in lengthDiffs) {
      if (offset > offsetInDecoded) break
      result += diff
    }
    return result
  }

  override fun decode(rangeInsideHost: TextRange, outChars: StringBuilder): Boolean {
    val delimiterPounds = content.prevSibling.textLength - (if (isOneLine) 1 else 3)
    val startToken = content.findElementAt(rangeInsideHost.startOffset)
    val endToken = content.findElementAt(rangeInsideHost.endOffset)
    val newLengthDiffs = mutableListOf<Pair<Int, Int>>()
    val initialOutCharsLength = outChars.length
    var token = startToken
    var result = true

    tokenLoop@ while (token != null) {
      val isPartialToken =
        token === startToken && token.startOffsetInParent != rangeInsideHost.startOffset ||
          token === endToken &&
            token.startOffsetInParent + token.textLength != rangeInsideHost.endOffset

      if (isPartialToken) {
        val start = max(token.startOffsetInParent, rangeInsideHost.startOffset)
        val end = min(token.startOffsetInParent + token.textLength, rangeInsideHost.endOffset)
        when (token.elementType) {
          PklElementTypes.STRING_CHARS,
          TokenType.WHITE_SPACE -> {
            outChars.append(token.text.substring(start, end))
          }
          PklElementTypes.CHAR_ESCAPE,
          PklElementTypes.UNICODE_ESCAPE -> {
            newLengthDiffs.add(outChars.length - initialOutCharsLength to end - start)
            result = false
          }
          else ->
            throw AssertionError(
              "PklStringContentEscaper should only be used with valid constant single-line string literals"
            )
        }
      } else {
        when (token.elementType) {
          PklElementTypes.STRING_CHARS,
          TokenType.WHITE_SPACE -> outChars.append(token.text)
          PklElementTypes.CHAR_ESCAPE -> {
            val text = token.text
            when (text[text.lastIndex]) {
              'n' -> outChars.append('\n')
              'r' -> outChars.append('\r')
              't' -> outChars.append('\t')
              '\\' -> outChars.append('\\')
              '"' -> outChars.append('"')
              else -> {
                logger.error(
                  "Encountered unexpected token of type CHAR_ESCAPE in string literal. Text: ${token.text}"
                )
                return false
              }
            }
            newLengthDiffs.add(outChars.length - initialOutCharsLength to token.textLength - 1)
          }
          PklElementTypes.UNICODE_ESCAPE -> {
            val text = token.text
            val hexCode = text.substring(delimiterPounds + 3, text.lastIndex) // \##u{...}
            val hexNum = hexCode.toInt(16)
            try {
              val chars = Character.toChars(hexNum)
              outChars.append(chars)
              newLengthDiffs.add(
                outChars.length - initialOutCharsLength to token.textLength - chars.size
              )
            } catch (e: IllegalArgumentException) {
              newLengthDiffs.add(outChars.length - initialOutCharsLength to token.textLength)
              result = false
            }
          }
          else -> {
            logger.error(
              "Encountered unexpected token while decoding string literal. Element type: ${token.elementType} Text: ${token.text}"
            )
            return false
          }
        }
      }

      if (token === endToken) break
      token = token.nextSibling
    }

    lengthDiffs = newLengthDiffs
    return result
  }
}
