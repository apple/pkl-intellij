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
package org.pkl.intellij.color

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as Default
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.options.colors.AttributesDescriptor

enum class PklColor(humanName: String, default: TextAttributesKey) {
  // region colors matched by [PklSyntaxHighlighter]
  KEYWORD("Keyword", Default.KEYWORD),
  BRACKETS("Braces and Operators//Brackets", Default.BRACKETS),
  MEMBER_PREDICATE("Braces and Operators//Member Predicate", BRACKETS.textAttributesKey),
  BRACES("Braces and Operators//Braces", Default.BRACES),
  PARENTHESES("Braces and Operators//Parentheses", Default.PARENTHESES),
  COMMA("Braces and Operators//Comma", Default.COMMA),
  DOT("Braces and Operators//Dot", Default.DOT),
  OPERATOR("Braces and Operators//Operator", Default.OPERATION_SIGN),
  ARROW("Braces and Operators//Arrow", OPERATOR.textAttributesKey),
  COALESCE("Braces and Operators//Null Coalescing", OPERATOR.textAttributesKey),
  NON_NULL("Braces and Operators//Non-Null", OPERATOR.textAttributesKey),
  PIPE("Braces and Operators//Pipe", OPERATOR.textAttributesKey),
  STRING("Strings//String text", Default.STRING),
  STRING_ESCAPE("Strings//String escape", Default.VALID_STRING_ESCAPE),
  BAD_CHARACTER("Bad character", HighlighterColors.BAD_CHARACTER),
  NUMBER("Number", Default.NUMBER),
  SHEBANG_COMMENT("Comments//Shebang comment", Default.LINE_COMMENT),
  LINE_COMMENT("Comments//Line comment", Default.LINE_COMMENT),
  BLOCK_COMMENT("Comments//Block comment", Default.BLOCK_COMMENT),
  DOC_COMMENT("Comments//Doc comment", Default.DOC_COMMENT),
  IDENTIFIER("Identifiers//Identifier", Default.IDENTIFIER),
  // endregion

  // region colors matched by [PklHighlightingAnnotator]
  ANNOTATION("Annotation", Default.METADATA), // not under identifiers because it includes the `@`
  LOCAL_VARIABLE("Identifiers//Local variable", Default.LOCAL_VARIABLE),
  CLASS("Identifiers//Class", Default.CLASS_NAME),
  METHOD("Identifiers//Method", Default.INSTANCE_METHOD),
  MODULE("Identifiers//Module", Default.LOCAL_VARIABLE),
  PARAMETER("Identifiers//Parameter", Default.PARAMETER),
  // [Default.INSTANCE_FIELD] would cause large parts of the code
  // to be accentuated with default color schemes,
  // which seems undesirable
  PROPERTY("Identifiers//Property", Default.IDENTIFIER),
  TYPE_ALIAS("Identifiers//Type alias", CLASS.textAttributesKey);
  // endregion

  val textAttributesKey = TextAttributesKey.createTextAttributesKey("PKL_$name", default)
  val attributesDescriptor = AttributesDescriptor(humanName, textAttributesKey)
  val testSeverity: HighlightSeverity = HighlightSeverity(name, HighlightSeverity.INFORMATION.myVal)
}
