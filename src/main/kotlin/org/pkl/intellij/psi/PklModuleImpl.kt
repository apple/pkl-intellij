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

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.findFile
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import javax.swing.Icon
import org.pkl.intellij.PklFileType
import org.pkl.intellij.PklIcons
import org.pkl.intellij.PklLanguage
import org.pkl.intellij.PklVersion
import org.pkl.intellij.packages.*
import org.pkl.intellij.packages.PklProjectService.Companion.PKL_PROJECT_FILENAME
import org.pkl.intellij.packages.dto.PackageMetadata
import org.pkl.intellij.packages.dto.PklProject
import org.pkl.intellij.type.Type
import org.pkl.intellij.type.TypeParameterBindings
import org.pkl.intellij.util.getContextualCachedValue

internal val jarFs: JarFileSystem =
  VirtualFileManager.getInstance().getFileSystem("jar") as JarFileSystem

class PklModuleImpl(viewProvider: FileViewProvider) :
  PsiFileBase(viewProvider, PklLanguage), PklModule {
  companion object {
    private val projectCacheKey: Key<CachedValue<PklProject>> = Key.create("pklProject")
    private val packageKey: Key<CachedValue<PackageDependency>> = Key.create("package")
  }

  private val cacheManager = CachedValuesManager.getManager(project)

  override fun <R> accept(visitor: PklVisitor<R>): R {
    // kick off recursion in `PklRecursiveVisitor`
    return visitor.visitElement(this)
  }

  override fun getFileType(): FileType = PklFileType

  // Returning a PsiElement (and overriding getTextOffset()) doesn't have an effect for PsiFile's.
  // Not being able to override getTextOffset() or getNavigationElement() (see below),
  // I haven't found a way to place the caret at a specific PSI element when navigating to a module.
  // Instead, the caret is placed at the last remembered caret position for the file, which is a
  // confusing user experience.
  override fun getNameIdentifier(): PsiElement? = null

  // Implementing this method differently for PsiFile's breaks "Find in Path" and "Find Usages"
  // (IntelliJ unconditionally casts the return value to PsiFile).
  override fun getNavigationElement(): PsiElement = super.getNavigationElement()

  override fun setName(name: String): PsiElement = super<PsiFileBase>.setName(name)

  override val declaration: PklModuleDeclaration?
    get() = firstChildOfClass<PklModuleDeclaration>()

  override val annotationList: PklAnnotationList?
    get() = declaration?.annotationList

  override val annotations: Sequence<PklAnnotation>
    get() = annotationList?.childrenOfClass() ?: emptySequence()

  override val modifierList: PklModifierList?
    get() = declaration?.modifierList

  override val modifiers: Sequence<PsiElement>
    get() = modifierList?.childSeq ?: emptySequence()

  override val declaredName: PklQualifiedIdentifier?
    get() = declaration?.qualifiedIdentifier

  override val displayName: String
    get() = declaredName?.text ?: viewProvider.virtualFile.nameWithoutExtension

  override val shortDisplayName: String
    get() =
      declaredName?.text?.substringAfterLast('.') ?: viewProvider.virtualFile.nameWithoutExtension

  // for some reason, overriding getBaseIcon() doesn't do anything
  override fun getIcon(flags: Int): Icon {
    val icon = if (isClassLike) PklIcons.CLASS else PklIcons.FILE
    return icon.decorate(this, flags)
  }

  override val isClassLike: Boolean
    get() = name[0].isUpperCase() && !extendsAmendsClause.isAmend

  override fun getPresentation(): ItemPresentation =
    object : ItemPresentation {
      override fun getPresentableText(): String =
        declaredName?.text?.substringAfterLast('.') ?: viewProvider.virtualFile.name

      override fun getLocationString(): String? {
        val file: com.intellij.openapi.vfs.VirtualFile? = viewProvider.virtualFile.parent
        return if (file != null && file.isValid && file.isDirectory)
          project.basePath?.let { file.presentableUrl.substringAfter("$it/") }
            ?: file.presentableUrl
        else null
      }

      override fun getIcon(unused: Boolean): Icon = getIcon(0)
    }

  // improve readability in PSI viewer
  override fun toString(): String = "Module"

  override fun getLookupElementType(
    base: PklBaseModule,
    bindings: TypeParameterBindings,
    context: PklProject?
  ): Type = base.moduleType

  override val extendsAmendsClause: PklModuleExtendsAmendsClause?
    get() = declaration?.extendsAmendsClause

  override val extendsAmendsUri: PklModuleUri?
    get() = extendsAmendsClause?.moduleUri

  override val docComment: PklDocComment?
    get() = declaration?.docComment

  override fun supermodule(context: PklProject?): PklModule? {
    return extendsAmendsUri?.resolve(context)
  }

  override fun supermodules(context: PklProject?): Sequence<PklModule> {
    return generateSequence(supermodule(context)) { it.supermodule(context) }
  }

  override val importList: PklImportList
    get() = firstChildOfClass<PklImportList>()!!

  override val imports: Sequence<PklImport>
    get() = importList.childrenOfClass()

  override val memberList: PklModuleMemberList
    get() = firstChildOfClass<PklModuleMemberList>()!!

  override val members: Sequence<PklModuleMember>
    get() = memberList.childrenOfClass()

  override val typeDefs: Sequence<PklTypeDef>
    get() = memberList.childrenOfClass()

  override val properties: Sequence<PklClassProperty>
    get() = memberList.childrenOfClass()

  override val typeDefsAndProperties: Sequence<PklTypeDefOrProperty>
    get() = memberList.childrenOfClass()

  override val methods: Sequence<PklClassMethod>
    get() = memberList.childrenOfClass()

  override val minPklVersion: PklVersion?
    get() = minPklVersionDetails?.first

  override val effectivePklVersion: PklVersion
    // could do more and compute minimum `minPklVersion` of transitively referenced modules
    get() = minPklVersion ?: supermodule(null)?.effectivePklVersion ?: project.pklStdLib.version

  override val minPklVersionDetails: Pair<PklVersion, PklElement>?
    get() {
      val base = project.pklBaseModule

      for (ann in annotations) {
        val annType = ann.typeName?.resolve(null) ?: continue

        if (annType == base.moduleInfoType.psi) {
          ann.objectBody?.properties?.forEach { property ->
            if (property.name == "minPklVersion") {
              val minVersionLiteral = property.expr as? PklStringLiteral? ?: return null
              val minVersionContent = minVersionLiteral.content
              val minVersionToken =
                minVersionContent.singleChildOfType(PklElementTypes.STRING_CHARS) ?: return null
              val minVersion = PklVersion.parse(minVersionToken.text) ?: return null
              return minVersion to minVersionContent
            }
          }
          return null
        }
      }
      return null
    }

  override val isPklBaseModule: Boolean = declaredName?.text == "pkl.base"

  override val isStdLibModule: Boolean = declaredName?.text?.startsWith("pkl.") ?: false

  override fun cache(context: PklProject?): ModuleMemberCache =
    getContextualCachedValue(context) {
      val cache = ModuleMemberCache.create(this, context)
      CachedValueProvider.Result.create(cache, cache.dependencies)
    }

  private fun doGetPackage(): PackageDependency? {
    if (virtualFile == null) return null
    val directlyImportedPackage = project.pklPackageService.getDirectlyImportedPackage(virtualFile)
    if (directlyImportedPackage != null) {
      return directlyImportedPackage
    }
    val jarFile = jarFs.getLocalByEntry(virtualFile) ?: return null
    val jsonFile = jarFile.parent.findFile(jarFile.nameWithoutExtension + ".json") ?: return null
    val packageUri = PackageMetadata.load(jsonFile).packageUri
    return PackageDependency(packageUri, null)
  }

  override val `package`: PackageDependency?
    get() {
      return cacheManager.getCachedValue(
        this,
        packageKey,
        { CachedValueProvider.Result(doGetPackage(), ModificationTracker.NEVER_CHANGED) },
        false
      )
    }

  /** Find the closest project to this module. */
  override val pklProject: PklProject?
    get() {
      return cacheManager.getCachedValue(
        this,
        projectCacheKey,
        {
          val virtualFile = originalFile.virtualFile
          if (virtualFile.fileSystem !is LocalFileSystem) {
            return@getCachedValue null
          }
          var dir = virtualFile.parent
          while (dir != null) {
            if (dir.findChild(PKL_PROJECT_FILENAME) != null) {
              return@getCachedValue project.pklProjectService.getPklProject(dir)?.let {
                CachedValueProvider.Result(it, project.pklProjectService)
              }
            }
            dir = dir.parent
          }
          return@getCachedValue null
        },
        false
      )
    }

  override fun dependencies(context: PklProject?): Map<String, Dependency>? =
    `package`?.let { project.pklPackageService.getResolvedDependencies(it, context) }
      ?: pklProject?.getResolvedDependencies(context)

  override val canonicalUri: String
    get() =
      `package`?.let { "${it.packageUri}#/${virtualFile.path.substringAfter("!/")}" }
        ?: virtualFile.url

  override fun isEquivalentTo(other: PsiElement): Boolean {
    if (this === other) return true
    if (other !is PklModule) return false
    return canonicalUri == other.canonicalUri
  }
}
