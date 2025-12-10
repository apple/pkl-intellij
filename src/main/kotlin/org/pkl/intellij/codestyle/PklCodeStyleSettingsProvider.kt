/**
 * Copyright © 2024-2025 Apple Inc. and the Pkl project authors. All rights reserved.
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
package org.pkl.intellij.codestyle

import com.intellij.application.options.CodeStyleAbstractConfigurable
import com.intellij.application.options.TabbedLanguageCodeStylePanel
import com.intellij.lang.Language
import com.intellij.psi.codeStyle.CodeStyleConfigurable
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.codeStyle.CodeStyleSettingsProvider
import org.pkl.intellij.PklLanguage

class PklCodeStyleSettingsProvider : CodeStyleSettingsProvider() {
  override fun createCustomSettings(settings: CodeStyleSettings) = PklCodeStyleSettings(settings)

  override fun getConfigurableDisplayName() = PklLanguage.displayName

  override fun getLanguage(): Language = PklLanguage

  override fun createConfigurable(
    settings: CodeStyleSettings,
    modelSettings: CodeStyleSettings
  ): CodeStyleConfigurable {

    return object :
      CodeStyleAbstractConfigurable(settings, modelSettings, configurableDisplayName) {
      override fun createPanel(settings: CodeStyleSettings) =
        PklCodeStyleMainPanel(currentSettings, settings)
    }
  }

  private class PklCodeStyleMainPanel(
    currentSettings: CodeStyleSettings,
    settings: CodeStyleSettings
  ) : TabbedLanguageCodeStylePanel(PklLanguage, currentSettings, settings) {

    override fun initTabs(settings: CodeStyleSettings) {
      addIndentOptionsTab(settings)
    }
  }
}
