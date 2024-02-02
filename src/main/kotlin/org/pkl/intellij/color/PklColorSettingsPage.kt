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

import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import javax.swing.Icon
import org.pkl.intellij.PklIcons
import org.pkl.intellij.PklLanguage
import org.pkl.intellij.PklSyntaxHighlighter

class PklColorSettingsPage : ColorSettingsPage {
  override fun getAttributeDescriptors(): Array<AttributesDescriptor> =
    PklColor.values().map { it.attributesDescriptor }.toTypedArray()

  override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY

  override fun getDisplayName(): String = PklLanguage.displayName

  override fun getIcon(): Icon = PklIcons.LOGO

  override fun getHighlighter(): SyntaxHighlighter = PklSyntaxHighlighter()

  override fun getAdditionalHighlightingTagToDescriptorMap() =
    PklColor.values().associateBy({ it.name }, { it.textAttributesKey })

  /**
   * Text shown in the settings window when previewing colors.
   *
   * If a color is added by [org.pkl.intellij.annotator.PklHighlightingAnnotator], the corresponding
   * text range must be delimited by tags. Otherwise, the color settings page will not show the
   * correct color for it.
   *
   * The tag names are determined by the enum names of [PklColor].
   */
  override fun getDemoText(): String {
    return """
      #!/usr/bin/env pkl
     
      <ANNOTATION>@ModuleInfo</ANNOTATION> { <PROPERTY>minPklVersion</PROPERTY> = "0.25.0" }
      module com.example.MyModule
      
      amends "../myParentModule.pkl"
      
      import "pkl:math"
      import "pkl:xml" as <MODULE>xmlModule</MODULE>
      
      <PROPERTY>json</PROPERTY> = import("pkl:json")
      
      <PROPERTY>pigeon</PROPERTY> {
        // Set the name to Pigeon
        <PROPERTY>name</PROPERTY> = "Pigeon"
      }
      
      /*
      Blocked out comment
      */
      
      <ANNOTATION>@Deprecated</ANNOTATION>
      class <CLASS>Person</CLASS> {
        <PROPERTY>name</PROPERTY>: <CLASS>String</CLASS>
        <PROPERTY>age</PROPERTY>: <CLASS>Int</CLASS>
      }
      
      typealias <TYPE_ALIAS>PersonOrString</TYPE_ALIAS> = <CLASS>Person</CLASS> | <CLASS>String</CLASS>
      
      <PROPERTY>random</PROPERTY>: <MODULE>math</MODULE>.<CLASS>Random</CLASS> = <MODULE>math</MODULE>.<METHOD>Random</METHOD>(5)
      
      <PROPERTY>youngParrot</PROPERTY>: <CLASS>Person</CLASS> = new { <PROPERTY>name</PROPERTY> = "Parrot"; <PROPERTY>age</PROPERTY> = 8 }
      
      /// This is makes a person
      function <METHOD>makePerson</METHOD>(<PARAMETER>_name</PARAMETER>: <CLASS>String</CLASS>): <CLASS>Person</CLASS> = new {
        <PROPERTY>name</PROPERTY> = <PARAMETER>_name</PARAMETER>
      }
      
      local <PROPERTY>toUpperCase</PROPERTY> = (<PARAMETER>str</PARAMETER>: <CLASS>String</CLASS>) -> <PARAMETER>str</PARAMETER>.<METHOD>toUpperCase</METHOD>()
      
      <PROPERTY>ten</PROPERTY> = 5 + 5
      
      <PROPERTY>tenNum</PROPERTY> = <PROPERTY>ten</PROPERTY> as <CLASS>Number</CLASS>
      
      <PROPERTY>ageStr</PROPERTY> = "My age is \(<PROPERTY>ten</PROPERTY>)"
      
      <PROPERTY>persons</PROPERTY>: <CLASS>Listing</CLASS><<CLASS>Person</CLASS>>
      
      <PROPERTY>personsRenamed</PROPERTY> = (<PROPERTY>persons</PROPERTY>) {
        [[this.<PROPERTY>name</PROPERTY> == "Pigeon"]] {
          <PROPERTY>name</PROPERTY> = "Dove"
        }
      }
      
      <PROPERTY>personsCloned</PROPERTY>: <CLASS>Listing</CLASS><<CLASS>Person</CLASS>> = new {
        for (<LOCAL_VARIABLE>person</LOCAL_VARIABLE> in <PROPERTY>persons</PROPERTY>) {
          <LOCAL_VARIABLE>person</LOCAL_VARIABLE>
        }
      }
      
      <PROPERTY>cities</PROPERTY> = <METHOD>Set</METHOD>("Los Angeles", "San Francisco", "Seattle")
      
      <PROPERTY>firstCity</PROPERTY> = <PROPERTY>cities</PROPERTY>[0] ?? "New York"
      
      <PROPERTY>myFile</PROPERTY> = read("file:/my-file.txt")
      
      <PROPERTY>div</PROPERTY> = <MODULE>xmlModule</MODULE>.<METHOD>Element</METHOD>("div")
    """
      .trimIndent()
  }
}
