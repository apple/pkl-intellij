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
package org.pkl.intellij.documentation

import java.net.URI
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.flavours.commonmark.CommonMarkFlavourDescriptor
import org.intellij.markdown.flavours.gfm.*
import org.intellij.markdown.html.GeneratingProvider
import org.intellij.markdown.html.TrimmingInlineHolderProvider
import org.intellij.markdown.parser.LinkMap

object PkldocFlavorDescriptor : CommonMarkFlavourDescriptor() {
  // enable support for GFM tables
  override val markerProcessorFactory = GFMMarkerProcessor.Factory

  override fun createHtmlGeneratingProviders(
    linkMap: LinkMap,
    baseURI: URI?
  ): Map<IElementType, GeneratingProvider> {
    // `TablesGeneratingProvider` is an internal class, so grab it from `GFMFlavourDescriptor`
    val tablesGeneratingProvider =
      GFMFlavourDescriptor()
        .createHtmlGeneratingProviders(linkMap, baseURI)
        .getValue(GFMElementTypes.TABLE)

    return super.createHtmlGeneratingProviders(linkMap, baseURI) +
      mapOf(
        MarkdownElementTypes.SHORT_REFERENCE_LINK to PkldocLinkGeneratingProvider,
        MarkdownElementTypes.FULL_REFERENCE_LINK to PkldocLinkGeneratingProvider,
        GFMElementTypes.TABLE to tablesGeneratingProvider,
        GFMTokenTypes.CELL to TrimmingInlineHolderProvider()
      )
  }
}
