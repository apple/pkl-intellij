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
package org.pkl.intellij.lexer;

import com.intellij.lexer.FlexLexer;
import com.intellij.psi.tree.IElementType;

import java.util.ArrayDeque;
import java.util.Deque;

import static com.intellij.psi.TokenType.BAD_CHARACTER;
import static com.intellij.psi.TokenType.WHITE_SPACE;
import static org.pkl.intellij.psi.PklElementTypes.*;

%%

%public
%class _PklLexer
%implements FlexLexer

%{
  public _PklLexer() {
    this(null);
  }

  private static final class State {
    // lexer state (see %state, %xstate)
    final int state;

    // number of unclosed parens in string interpolation expr (0 -> end of interpolation expr)
    final int parenCount;

    // number of pound signs of string literal's opening delimiter (-1 -> N/A)
    final int poundCount;

    State(int state, int parenCount, int poundCount) {
      this.state = state;
      this.parenCount = parenCount;
      this.poundCount = poundCount;
    }
  }

  private final Deque<State> states = new ArrayDeque<State>();

  private int parenCount;
  private int poundCount;

  private void pushState(int newState, int newPoundCount) {
    // push current top state
    states.push(new State(yystate(), parenCount, poundCount));
    // new top state is stored in locals
    parenCount = 0;
    poundCount = newPoundCount;
    yybegin(newState);
  }

  private void popState() {
    State state = states.pop();
    parenCount = state.parenCount;
    poundCount = state.poundCount;
    yybegin(state.state);
  }
%}

%function advance
%type IElementType
%unicode

%state INTERPOLATION MEMBER_PREDICATE SUBSCRIPT_IN_PREDICATE
%xstate STRING ML_STRING SEPARATOR

// \r and \r\n are normalized to \n by IntelliJ
// \R doesn't work although JFlex 1.5.0 changelog claims it does
NEWLINE = [\n\u000B\u000C\u0085\u2028\u2029]
SEPARATOR = ({NEWLINE} | ";")+
WHITE_SPACE = [ \t\f]+

DEC_DIGIT = [0-9]
HEX_DIGIT = [0-9a-fA-F]
BINARY_DIGIT = [01]
OCTAL_DIGIT = [0-7]
EXPONENT = [eE] [+-]? {DEC_NUMBER}

// need to avoid name collision with numberLiteral (which becomes NUMBER_LITERAL) defined in pkl.bnf
NUMBER = {DEC_NUMBER} | {HEX_NUMBER} | {BIN_NUMBER} | {OCTAL_NUMBER} | {FLOAT_NUMBER}

DEC_CHARACTERS = ({DEC_DIGIT} | "_")+
DEC_NUMBER = {DEC_DIGIT} {DEC_CHARACTERS}*

FLOAT_NUMBER = ({DEC_NUMBER}? ("." {DEC_NUMBER} {EXPONENT}?)) | ({DEC_NUMBER} {EXPONENT})

HEX_CHARACTERS = ({HEX_DIGIT} | "_")+
HEX_NUMBER = "0x" {HEX_DIGIT} {HEX_CHARACTERS}*

BINARY_CHARACTERS = ({BINARY_DIGIT} | "_")+
BIN_NUMBER = "0b" {BINARY_DIGIT} {BINARY_CHARACTERS}*

OCTAL_CHARACTERS = ({OCTAL_DIGIT} | "_")+
OCTAL_NUMBER = "0o" {OCTAL_DIGIT} {OCTAL_CHARACTERS}*

SHEBANG_COMMENT = "#!" .*
DOC_COMMENT_LINE = "///" .*
LINE_COMMENT = "//" .*
// TODO: support nested block comments
// TODO: block comment is not initially highlighted but only after editing its content
//       (appears to be a problem with incremental highlighting; which regex is used doesn't seem to matter)
BLOCK_COMMENT = "/*" ~"*/"

IDENTIFIER = ([_$\p{xidstart}][_$\p{xidcontinue}]*) | ("`" [^`\n\r]+ "`")

STRING_START = "#"* "\""
STRING_END = "\"" "#"*
STRING_CHARS = [^\"\\\n\r]+

// TODO: enforce newline requirements of start/end delimiter (perhaps higher up with an annotator)
ML_STRING_START = "#"* "\"\"\""
ML_STRING_END = "\"\"\"" "#"*
ML_STRING_CHARS = ("\"" "\""?) | (("\"" "\""?)? [^\n\u000B\u000C\u0085\u2028\u2029 \t\f\"\\])+

ESCAPE_PREFIX = "\\" "#"*
CHAR_ESCAPE = {ESCAPE_PREFIX} [tnr\"\\]
UNICODE_ESCAPE = {ESCAPE_PREFIX} "u{" {HEX_DIGIT}+ "}"
INTERPOLATION_START = {ESCAPE_PREFIX} "("

%%
{STRING_START}          { pushState(STRING, yylength() - 1);
                          return STRING_START;
                        }
<STRING> {STRING_CHARS} { return STRING_CHARS; }
<STRING> {NEWLINE}      { popState();
                          yypushback(1);
                          return BAD_CHARACTER;
                        }
<STRING> {STRING_END}   { int delta = yylength() - poundCount;
                          if (delta == 1) {
                            popState();
                            return STRING_END;
                          }
                          if (delta < 1) {
                            return STRING_CHARS;
                          }
                          popState();
                          return BAD_CHARACTER;
                        }

{ML_STRING_START}                        { pushState(ML_STRING, yylength() - 3);
                                           return ML_STRING_START;
                                         }
<ML_STRING> {ML_STRING_CHARS}            { return STRING_CHARS; }

// lexing as WHITE_SPACE helps with formatting
// TODO: "excess" leading whitespace must not be removed when reformatting a multiline string
<ML_STRING> ({NEWLINE} | {WHITE_SPACE})+ { return WHITE_SPACE; }

<ML_STRING> {ML_STRING_END}              { int delta = yylength() - poundCount;
                                           if (delta == 3) {
                                             popState();
                                             return ML_STRING_END;
                                           }
                                           if (delta < 3) {
                                             return STRING_CHARS;
                                           }
                                           popState();
                                           return BAD_CHARACTER;
                                         }

// only matches if none of CHAR_ESCAPE/UNICODE_ESCAPE/INTERPOLATION_START matches (longest match wins)
<STRING, ML_STRING> {ESCAPE_PREFIX}       { int delta = yylength() - poundCount;
                                            if (delta < 1) return STRING_CHARS;
                                            return INVALID_ESCAPE;
                                          }
<STRING, ML_STRING> {CHAR_ESCAPE}         { int delta = yylength() - poundCount;
                                            if (delta == 2) return CHAR_ESCAPE;
                                            if (delta < 2) {
                                              // since this didn't turn out to be an escape sequence,
                                              // push back double quote, which could be part of string end
                                              if (yycharat(yylength() - 1) == '"') yypushback(1);
                                              return STRING_CHARS;
                                            }
                                            return INVALID_ESCAPE;
                                          }
<STRING, ML_STRING> {UNICODE_ESCAPE}      { if (yycharat(1 + poundCount) == 'u') return UNICODE_ESCAPE;
                                            for (int i = 0; i < poundCount; i++) {
                                              if (yycharat(1 + i) == 'u') return STRING_CHARS;
                                            }
                                            return INVALID_ESCAPE;
                                          }
<STRING, ML_STRING> {INTERPOLATION_START} { int delta = yylength() - poundCount;
                                            if (delta == 2) {
                                              pushState(INTERPOLATION, -1);
                                              return INTERPOLATION_START;
                                            }
                                            if (delta < 2) {
                                              return STRING_CHARS;
                                            }
                                            return INVALID_ESCAPE;
                                          }

<INTERPOLATION> "(" { parenCount += 1;
                      return LPAREN;
                    }
<INTERPOLATION> ")" { if (parenCount == 0) {
                        popState();
                        return INTERPOLATION_END;
                      }
                      parenCount -= 1;
                      return RPAREN;
                    }

"[["                    { pushState(MEMBER_PREDICATE, 0);
                          return LPRED;
                        }

<MEMBER_PREDICATE> "]]" {
                          popState();
                          return RPRED;
                        }

// handle edge case where member predicate ends with subscript expression: `[[foo[bar]]]`
<MEMBER_PREDICATE, SUBSCRIPT_IN_PREDICATE> "[" {
                                                 pushState(SUBSCRIPT_IN_PREDICATE, 0);
                                                 return LBRACK;
                                               }

<SUBSCRIPT_IN_PREDICATE> "]" {
                               popState();
                               return RBRACK;
                             }

{SEPARATOR}               { pushState(SEPARATOR, 0); return WHITE_SPACE; }
<SEPARATOR> {WHITE_SPACE} { return WHITE_SPACE; }
<SEPARATOR> {SEPARATOR}   { return WHITE_SPACE; }
<SEPARATOR> [-(\[]        { popState(); yypushback(1); return SEP; }
<SEPARATOR> .             { popState(); yypushback(1); }

"abstract"  { return ABSTRACT; }
"amends"    { return AMENDS; }
"as"        { return AS; }
"class"     { return CLASS_KEYWORD; }
"const"     { return CONST; }
"delete"    { return DELETE;  }
"else"      { return ELSE; }
"extends"   { return EXTENDS; }
"external"  { return EXTERNAL; }
"false"     { return FALSE; }
"fixed"     { return FIXED; }
"for"       { return FOR; }
"function"  { return FUNCTION; }
"hidden"    { return HIDDEN; }
"if"        { return IF; }
"import"    { return IMPORT_KEYWORD; }
"import*"   { return IMPORT_GLOB; }
"in"        { return IN; }
"is"        { return IS; }
"let"       { return LET; }
"local"     { return LOCAL; }
"module"    { return MODULE; }
"new"       { return NEW; }
"nothing"   { return NOTHING; }
"null"      { return NULL; }
"open"      { return OPEN; }
"out"       { return OUT; }
"outer"     { return OUTER; }
"read"      { return READ; }
"read*"     { return READ_GLOB; }
"read?"     { return READ_OR_NULL; }
"super"     { return SUPER; }
"this"      { return THIS; }
"throw"     { return THROW; }
"trace"     { return TRACE; }
"true"      { return TRUE; }
"typealias" { return TYPEALIAS; }
"unknown"   { return UNKNOWN; }
"when"      { return WHEN; }
"..."       { return SPREAD; }
"...?"      { return QSPREAD; }

// reserved keywords
"protected" { return PROTECTED;  }
"override"  { return OVERRIDE;  }
"record"    { return RECORD;  }
"case"      { return CASE; }
"switch"    { return SWITCH; }
"vararg"    { return VARARG; }

"("  { return LPAREN; }
")"  { return RPAREN; }
"{"  { return LBRACE; }
"}"  { return RBRACE; }
"["  { return LBRACK; }
"]"  { return RBRACK; }
","  { return COMMA; }
"."  { return DOT; }
"?." { return QDOT; }
"??" { return COALESCE; }
"!!" { return NON_NULL; }
"@"  { return AT; }
"="  { return ASSIGN; }
"<"  { return LT; }
">"  { return GT; }
"!"  { return NOT; }
"?"  { return QUESTION; }
":"  { return COLON; }
"->" { return ARROW; }
"==" { return EQUAL; }
"!=" { return NOT_EQUAL; }
"<=" { return LTE; }
">=" { return GTE; }
"&&" { return AND; }
"||" { return OR; }
"+"  { return PLUS; }
"-"  { return MINUS; }
"**" { return POW; }
"*"  { return MUL; }
"/"  { return DIV; }
"~/" { return INT_DIV; }
"%"  { return MOD; }
"|"  { return UNION; }
"|>" { return PIPE; }

{SHEBANG_COMMENT}  { return SHEBANG_COMMENT; }
{DOC_COMMENT_LINE} { return DOC_COMMENT_LINE; } // must come before LINE_COMMENT
{LINE_COMMENT}     { return LINE_COMMENT; }
{BLOCK_COMMENT}    { return BLOCK_COMMENT; }

{IDENTIFIER} { return IDENTIFIER; }
{NUMBER}     { return NUMBER; }

{WHITE_SPACE} { return WHITE_SPACE; }

// error fallbacks
[^]                     { return BAD_CHARACTER; }
<STRING, ML_STRING> [^] { return BAD_CHARACTER; }
