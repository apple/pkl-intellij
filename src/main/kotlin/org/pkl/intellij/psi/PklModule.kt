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

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import org.pkl.intellij.PklVersion
import org.pkl.intellij.packages.Dependency
import org.pkl.intellij.packages.PackageDependency
import org.pkl.intellij.packages.dto.PklProject

interface PklModule : PsiFile, PklTypeDefOrModule {
  val declaration: PklModuleDeclaration?

  val annotations: Sequence<PklAnnotation>

  val modifiers: Sequence<PsiElement>

  /** The file name of this module. */
  override fun getName(): String

  /** Last identifier of [declaredName] if non-null; otherwise file name without extension. */
  val shortDisplayName: String

  /** The qualified identifier following the `module` keyword, if any. */
  val declaredName: PklQualifiedIdentifier?

  val isClassLike: Boolean

  val extendsAmendsClause: PklModuleExtendsAmendsClause?

  val extendsAmendsUri: PklModuleUri?

  val supermodule: PklModule?

  val supermodules: Sequence<PklModule>

  val importList: PklImportList

  val imports: Sequence<PklImport>

  val memberList: PklModuleMemberList

  val members: Sequence<PklModuleMember>

  val typeDefs: Sequence<PklTypeDef>

  val properties: Sequence<PklClassProperty>

  val typeDefsAndProperties: Sequence<PklTypeDefOrProperty>

  val methods: Sequence<PklClassMethod>

  val minPklVersion: PklVersion?

  val effectivePklVersion: PklVersion

  val minPklVersionDetails: Pair<PklVersion, PklElement>?

  val isPklBaseModule: Boolean

  val isStdLibModule: Boolean

  val cache: ModuleMemberCache

  val `package`: PackageDependency?

  val pklProject: PklProject?

  /**
   * The dependencies available to the module, that can be imported using dependency notation (i.e.
   * `import "@foo/bar"`).
   */
  val dependencies: Map<String, Dependency>?
}
