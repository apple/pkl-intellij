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
package org.pkl.intellij.parser

import com.intellij.lexer.FlexAdapter
import com.intellij.lexer.Lexer
import com.intellij.testFramework.LexerTestCase
import java.io.File
import org.junit.Ignore
import org.pkl.intellij.lexer._PklLexer

@Ignore
class PklLexerTest : LexerTestCase() {
  override fun createLexer(): Lexer {
    return FlexAdapter(_PklLexer())
  }

  override fun getDirPath(): String {
    return File("src/test/resources", "lexer").absolutePath
  }

  override fun getPathToTestDataFile(extension: String): String {
    return File(dirPath, getTestName(false) + extension).absolutePath
  }

  fun testLiterals() {
    doTest( // String literals
      "\"abc\" \"\\\"\" " + // Comments
      "// abc\n /// doc1 \n /// doc 2\n /* block */\n" + // Numeric literals
        "42 -1 0xac -0xDC09 0b0010111 1.23 1e3 1.23e-2 .12 .1e3 " + // keywords
        "true false"
    )
  }

  fun testNestedBlockComment() {
    doTest("/* b1 /* b2 */ */")
  }
}
