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

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Iconable
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.impl.FakePsiElement
import com.intellij.psi.impl.source.tree.LeafPsiElement
import javax.swing.Icon
import org.pkl.intellij.PklIcons
import org.pkl.intellij.packages.dto.PklProject
import org.pkl.intellij.type.Type
import org.pkl.intellij.type.TypeParameterBindings
import org.pkl.intellij.type.toType

class PklReferenceQualifiedAccessProxy(private val myName: String, val referent: PklType, val myProject: Project) :
  FakePsiElement(), PklElement, PsiNamedElement, Iconable {
  val type = ReferenceType(referent)

  override fun <R> accept(visitor: PklVisitor<R>): R? = null

  override fun clone(): Any = PklReferenceQualifiedAccessProxy(myName, referent, myProject)

  override fun getParent(): PsiElement? = null

  override fun isValid(): Boolean = true

  override fun getProject(): Project = myProject

  override fun getContainingFile(): PsiFile? = null

  override fun getName(): String = myName

  override fun getIcon(open: Boolean): Icon = PklIcons.PROPERTY

  fun getLookupElementType(
    base: PklBaseModule,
    bindings: TypeParameterBindings,
    context: PklProject?
  ): Type = base.referenceType!!.withTypeArguments(referent.toType(base, bindings, context))

  object UnknownType : FakePsiElement(), PklUnknownType {
    override fun <R> accept(visitor: PklVisitor<R>): R? = visitor.visitUnknownType(this)

    override fun clone(): Any = UnknownType

    override fun getParent(): PsiElement? = null

    private fun readResolve(): Any = UnknownType
  }

  class UnionType private constructor(val myLeftType: PklType, val myRightType: PklType) :
    FakePsiElement(), PklUnionType {
    companion object {
      fun create(types: List<PklType>): PklType =
        when {
          types.isEmpty() -> UnknownType
          types.size == 1 -> types.first()
          else -> types.reduce { t1, t2 -> UnionType(t1, t2) }
        }
    }

    override fun <R> accept(visitor: PklVisitor<R>): R? = visitor.visitUnionType(this)

    override fun clone(): Any = UnionType(leftType, rightType)

    override fun getParent(): PsiElement? = null

    override fun getTypeList(): List<PklType?> = listOf(myLeftType, myRightType)

    override fun getLeftType(): PklType = myLeftType

    override fun getRightType(): PklType = myRightType
  }

  class ReferenceType(val referent: PklType) : FakePsiElement(), PklDeclaredType {
    override fun <R> accept(visitor: PklVisitor<R>): R? = visitor.visitDeclaredType(this)

    override fun clone(): Any = UnknownType

    override fun getParent(): PsiElement? = null

    override fun getTypeArgumentList(): PklTypeArgumentList =
      object : FakePsiElement(), PklTypeArgumentList {
        override fun <R> accept(visitor: PklVisitor<R>): R? = visitor.visitTypeArgumentList(this)

        override fun clone(): Any = UnknownType

        override fun getParent(): PsiElement? = null

        override fun getElements(): List<PklType?> = listOf(referent)
      }

    override fun getTypeName(): PklTypeName =
      object : FakePsiElement(), PklTypeName {
        override fun <R> accept(visitor: PklVisitor<R>): R? = visitor.visitTypeName(this)

        override fun clone(): Any = UnknownType

        override fun getParent(): PsiElement? = null

        override fun getModuleName(): PklModuleName? = null

        override fun getSimpleName(): PklSimpleTypeName =
          object : FakePsiElement(), PklSimpleTypeName {
            override fun <R> accept(visitor: PklVisitor<R>): R? = visitor.visitSimpleTypeName(this)

            override fun clone(): Any = UnknownType

            override fun getParent(): PsiElement? = null

            override val identifier: PsiElement =
              LeafPsiElement(PklElementTypes.IDENTIFIER, "Reference")

            override fun getText(): String = "Reference"
          }
      }
  }
}
