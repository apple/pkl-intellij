/**
 * Copyright © 2026 Apple Inc. and the Pkl project authors. All rights reserved.
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

import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Iconable
import com.intellij.openapi.util.TextRange
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.impl.FakePsiElement
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.util.containers.headTail
import javax.swing.Icon
import org.pkl.intellij.PklIcons
import org.pkl.intellij.packages.dto.PklProject
import org.pkl.intellij.type.Type
import org.pkl.intellij.type.TypeParameterBindings
import org.pkl.intellij.type.toType

class PklReferenceQualifiedAccessProxy(
  private val myName: String,
  val domain: Type,
  val referent: PklType,
  val myProject: Project,
  val classProperties: List<PklClassProperty>
) : FakePsiElement(), PklElement, PsiNamedElement, Iconable, PklDocCommentOwner {
  // `referenceType` guaranteed to exist; PklReferenceQualifiedAccessProxy is only created when
  // pkl:ref is available.
  val type =
    DeclaredType(project.pklRefModule.referenceType!!, listOf(DeclaredType(domain), referent))

  override fun <R> accept(visitor: PklVisitor<R>): R? = null

  override fun clone(): Any =
    PklReferenceQualifiedAccessProxy(myName, domain, referent, myProject, classProperties)

  override fun getParent(): PsiElement? = null

  override fun isValid(): Boolean = true

  override fun getProject(): Project = myProject

  override fun getContainingFile(): PsiFile? = null

  override fun getName(): String = myName

  override fun getIcon(open: Boolean): Icon = PklIcons.PROPERTY

  override fun navigate(requestFocus: Boolean) {
    classProperties.firstOrNull()?.navigate(requestFocus)
  }

  override fun canNavigate(): Boolean {
    return true
  }

  override fun canNavigateToSource(): Boolean {
    return true
  }

  override fun getPresentation(): ItemPresentation? {
    return classProperties.firstOrNull()?.presentation
  }

  fun getLookupElementType(
    base: PklBaseModule,
    bindings: TypeParameterBindings,
    context: PklProject?
  ): Type =
    project.pklRefModule.referenceType!!.withTypeArguments(
      domain,
      referent.toType(base, bindings, context)
    )

  override val docComment: PklDocComment? = classProperties.firstOrNull()?.docComment

  object UnknownType : FakePsiElement(), PklUnknownType {
    override fun <R> accept(visitor: PklVisitor<R>): R? = visitor.visitUnknownType(this)

    override fun clone(): Any = UnknownType

    override fun getParent(): PsiElement? = null

    private fun readResolve(): Any = UnknownType
  }

  class UnionType private constructor(val myLeftType: PklType, val myRightType: PklType) :
    FakePsiElement(), PklUnionType {
    companion object {
      fun create(properties: List<PklClassProperty>): PklType =
        when {
          properties.isEmpty() -> UnknownType
          properties.size == 1 -> properties.first().type ?: UnknownType
          else -> {
            val (head, tail) = properties.headTail()
            UnionType(head.type ?: UnknownType, create(tail))
          }
        }
    }

    override fun <R> accept(visitor: PklVisitor<R>): R? = visitor.visitUnionType(this)

    override fun clone(): Any = UnionType(leftType, rightType)

    override fun getParent(): PsiElement? = null

    override fun getTypeList(): List<PklType?> = listOf(myLeftType, myRightType)

    override fun getLeftType(): PklType = myLeftType

    override fun getRightType(): PklType = myRightType
  }

  class DeclaredType(val type: Type, val typeArguments: List<PklType?> = emptyList()) :
    FakePsiElement(), PklDeclaredType {
    override fun <R> accept(visitor: PklVisitor<R>): R? = visitor.visitDeclaredType(this)

    override fun clone(): Any = DeclaredType(type, typeArguments)

    override fun getParent(): PsiElement? = null

    override fun getTypeArgumentList(): PklTypeArgumentList =
      object : FakePsiElement(), PklTypeArgumentList {
        override fun <R> accept(visitor: PklVisitor<R>): R? = visitor.visitTypeArgumentList(this)

        override fun clone(): Any = UnknownType

        override fun getParent(): PsiElement? = null

        override fun getElements(): List<PklType?> = typeArguments
      }

    override fun getTypeName(): PklTypeName = TypeName(type)
  }

  class TypeName(val type: Type) : FakePsiElement(), PklTypeName {
    override fun <R> accept(visitor: PklVisitor<R>): R? = visitor.visitTypeName(this)

    override fun clone(): Any = UnknownType

    override fun getParent(): PsiElement? = null

    override fun getModuleName(): PklModuleName = ModuleName(type)

    override fun getSimpleName(): PklSimpleTypeName = SimpleTypeName(type)
  }

  class ModuleName(val type: Type) : FakePsiElement(), PklModuleName {
    override fun <R> accept(visitor: PklVisitor<R>): R? = visitor.visitModuleName(this)

    override fun clone(): Any = UnknownType

    override fun getParent(): PsiElement? = null

    override fun getIdentifier(): PsiElement =
      LeafPsiElement(
        PklElementTypes.IDENTIFIER,
        when (type) {
          is Type.Class -> type.psi.enclosingModule?.declaredName?.text
          is Type.Alias -> type.psi.enclosingModule?.declaredName?.text
          is Type.Module -> ""
          else -> null
        }
          ?: "<module>"
      )

    override fun getText(): String = identifier.text

    override fun getReference(): PsiReference? =
      type.psi?.let { psi ->
        object : PsiReferenceBase<PklModuleName>(this), PklModuleNameReferenceEx {
          override fun getRangeInElement(): TextRange =
            ElementManipulators.getValueTextRange(this@ModuleName)

          override fun getCanonicalText(): String = this@ModuleName.identifier.text

          override fun resolveContextual(context: PklProject?): PklModule? = psi.enclosingModule

          override fun resolve(): PklModule? = psi.enclosingModule
        }
      }
  }

  class SimpleTypeName(val type: Type) : FakePsiElement(), PklSimpleTypeName {
    override fun <R> accept(visitor: PklVisitor<R>): R? = visitor.visitSimpleTypeName(this)

    override fun clone(): Any = UnknownType

    override fun getParent(): PsiElement? = null

    override val identifier: PsiElement =
      LeafPsiElement(
        PklElementTypes.IDENTIFIER,
        when (type) {
          is Type.Class -> type.psi.name ?: "<class>"
          is Type.Alias -> type.psi.name ?: "<alias>"
          is Type.Module -> type.psi.displayName
          is Type.Variable -> type.psi.text
          else -> "<type>"
        }
      )

    override fun getText(): String = identifier.text

    override fun getReference(): PsiReference? =
      type.psi?.let { psi ->
        object : PsiReferenceBase<PklSimpleTypeName>(this), PklReference {
          override fun getRangeInElement(): TextRange =
            ElementManipulators.getValueTextRange(this@SimpleTypeName)

          override fun getCanonicalText(): String = this@SimpleTypeName.identifier.text

          override fun resolveContextual(context: PklProject?): PsiElement = psi

          override fun resolve(): PsiElement = psi
        }
      }
  }
}

private val Type.psi: PklElement?
  get() =
    when (this) {
      is Type.Alias -> psi
      is Type.Class -> psi
      is Type.Module -> psi
      is Type.Variable -> psi
      else -> null
    }
