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

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import com.intellij.util.PlatformIcons
import javax.swing.Icon

object PklIcons {
  val FILE: Icon = loadIcon("/org/pkl/intellij/file.svg")
  val DIRECTORY: Icon = PlatformIcons.FOLDER_ICON

  val CLASS: Icon = loadIcon("/org/pkl/intellij/class.svg")
  val ABSTRACT_CLASS: Icon = loadIcon("/org/pkl/intellij/abstractClass.svg")

  val ANNOTATION_TYPE: Icon = loadIcon("/org/pkl/intellij/annotationType.svg")

  val TYPE_ALIAS: Icon = loadIcon("/org/pkl/intellij/typeAlias.svg")

  val METHOD: Icon = loadIcon("/org/pkl/intellij/method.svg")
  val ABSTRACT_METHOD: Icon = loadIcon("/org/pkl/intellij/abstractMethod.svg")

  val PROPERTY: Icon = loadIcon("/org/pkl/intellij/property.svg")

  val LOGO: Icon = loadIcon("/org/pkl/intellij/logo.svg")

  // TODO replace me
  val RELOAD_PROJECT: Icon = LOGO

  // TODO replace me
  val PACKAGE: Icon = LOGO

  // TODO replace me
  val PKL_RUN: Icon = LOGO

  val PUBLIC: Icon = PlatformIcons.PUBLIC_ICON
  val PRIVATE: Icon = PlatformIcons.PRIVATE_ICON

  val IMPLEMENTING_METHOD_MARKER: Icon = AllIcons.Gutter.ImplementingMethod
  val OVERRIDING_METHOD_MARKER: Icon = AllIcons.Gutter.OverridingMethod
  val RECURSIVE_METHOD_MARKER: Icon = AllIcons.Gutter.RecursiveMethod

  private fun loadIcon(path: String) = IconLoader.getIcon(path, PklIcons::class.java)
}
