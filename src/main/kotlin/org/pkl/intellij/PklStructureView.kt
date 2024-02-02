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

import com.intellij.ide.structureView.*
import com.intellij.ide.structureView.StructureViewModel.ElementInfoProvider
import com.intellij.lang.PsiStructureViewFactory
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiFile
import org.pkl.intellij.psi.*
import org.pkl.intellij.util.toTypedArray

// https://www.jetbrains.org/intellij/sdk/docs/reference_guide/custom_language_support/structure_view.html
class PklStructureViewFactory : PsiStructureViewFactory {
  override fun getStructureViewBuilder(psiFile: PsiFile): StructureViewBuilder? {
    if (psiFile !is PklModule) return null

    return object : TreeBasedStructureViewBuilder() {
      override fun createStructureViewModel(editor: Editor?): StructureViewModel {
        return object : TextEditorBasedStructureViewModel(editor, psiFile), ElementInfoProvider {

          override fun getRoot(): StructureViewTreeElement = PklStructureViewTreeElement(psiFile)

          override fun getSuitableClasses(): Array<Class<*>> =
            arrayOf(
              PklModule::class.java,
              PklClass::class.java,
              PklTypeAlias::class.java,
              PklClassMethod::class.java,
              PklClassProperty::class.java
            )

          override fun isAlwaysShowsPlus(element: StructureViewTreeElement): Boolean = false

          override fun isAlwaysLeaf(element: StructureViewTreeElement): Boolean =
            element.value !is PklModule && element.value !is PklClass
        }
      }
    }
  }
}

class PklStructureViewTreeElement(private val psi: PklNavigableElement) : StructureViewTreeElement {
  override fun navigate(requestFocus: Boolean) = psi.navigate(requestFocus)

  override fun getPresentation(): ItemPresentation =
    object : ItemPresentation by psi.presentation!! {
      override fun getLocationString(): String? = null // doesn't make sense for structure view
    }

  @Suppress("RemoveExplicitTypeArguments") // kotlinc complains otherwise
  override fun getChildren(): Array<StructureViewTreeElement> {
    return when (psi) {
      is PklModule -> psi.members.map { PklStructureViewTreeElement(it) }.toTypedArray()
      is PklClass ->
        psi.members.map { PklStructureViewTreeElement(it) }.toTypedArray<StructureViewTreeElement>()
      else -> StructureViewTreeElement.EMPTY_ARRAY
    }
  }

  override fun canNavigate(): Boolean = psi.canNavigate()

  override fun getValue(): Any = psi

  override fun canNavigateToSource(): Boolean = psi.canNavigateToSource()
}
