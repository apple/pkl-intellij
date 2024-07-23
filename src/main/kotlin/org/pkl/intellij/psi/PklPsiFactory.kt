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

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.util.LocalTimeCounter
import org.pkl.intellij.PklFileType
import org.pkl.intellij.util.escapeString

@Suppress("unused")
object PklPsiFactory {
  @Suppress("MemberVisibilityCanBePrivate")
  fun createModule(text: String, project: Project): PklModule =
    PsiFileFactory.getInstance(project)
      .createFileFromText(
        "dummy.pkl",
        PklFileType,
        text,
        LocalTimeCounter.currentTime(),
        false,
        true
      ) as PklModule

  fun createImport(moduleUri: String, project: Project): PklImport {
    val module = createModule("import \"$moduleUri\"", project)
    return module.imports.single()
  }

  fun createAliasedImport(moduleUri: String, importAlias: String, project: Project): PklImport {
    val module = createModule("import \"$moduleUri\" as $importAlias", project)
    return module.imports.single()
  }

  fun createImportList(imports: Sequence<PklImport>, project: Project): PklImportList {
    val source = buildString {
      for (import in imports) {
        import.moduleUri?.stringConstant?.let { stringConstant ->
          append("import ")
          append(stringConstant.stringStart.text)
          append(stringConstant.content.text)
          append(stringConstant.stringEnd.text)
          import.identifier?.let { identifier ->
            append(" as ")
            append(identifier.text)
          }
          appendLine()
        }
      }
    }
    val module = createModule(source, project)
    return module.importList
  }

  fun createClass(name: String, project: Project): PklClass {
    val module = createModule("class $name", project)
    return module.typeDefs.single() as PklClass
  }

  fun createTypeAlias(name: String, aliasedType: String, project: Project): PklTypeAlias {
    val module = createModule("typealias $name = $aliasedType", project)
    return module.typeDefs.single() as PklTypeAlias
  }

  fun createTypeParameter(name: String, project: Project): PklTypeParameter {
    val module = createModule("class X<$name>", project)
    return module.typeDefs.single().typeParameterList!!.elements.single()
  }

  fun createModuleName(name: String, project: Project): PklModuleName {
    val module = createModule("x: $name.Clazz", project)
    return (module.properties.single().type as PklDeclaredType).typeName.moduleName!!
  }

  fun createSimpleTypeName(name: String, project: Project): PklSimpleTypeName {
    val module = createModule("x: $name", project)
    return (module.properties.single().type as PklDeclaredType).typeName.simpleName
  }

  fun createObjectBody(project: Project, member: PklObjectMember): PklObjectBody {
    val module = createModule("x {\ny\n}", project)
    return module.properties.single().objectBodyList.single().apply {
      members.first().replace(member)
    }
  }

  fun createNewExpr(typeName: String, objectBody: PklObjectBody, project: Project): PklNewExpr {
    val module = createModule("x = new $typeName {}", project)
    return (module.properties.single().expr as PklNewExpr).apply {
      this.objectBody!!.replace(objectBody)
    }
  }

  fun createQualifiedNewExpr(
    moduleName: String,
    typeName: String,
    objectBody: PklObjectBody,
    project: Project
  ): PklNewExpr {
    val module = createModule("x = new $moduleName.$typeName {}", project)
    return (module.properties.single().expr as PklNewExpr).apply {
      this.objectBody!!.replace(objectBody)
    }
  }

  fun createParenthesizedAmendExpr(
    expr: PklExpr,
    objectBody: PklObjectBody,
    project: Project
  ): PklAmendExpr {
    val module = createModule("result = (${expr.text}) ${objectBody.text}", project)
    return module.properties.first().expr as PklAmendExpr
  }

  fun createReadOrNullExpr(project: Project): PklReadExpr {
    val module = createModule("x = read?(\"foo\")", project)
    return module.properties.single().expr as PklReadExpr
  }

  fun createUnqualifiedPropertyAccessExpr(
    propertyName: String,
    project: Project
  ): PklUnqualifiedAccessExpr {
    val module = createModule("x = $propertyName", project)
    return module.properties.single().expr as PklUnqualifiedAccessExpr
  }

  fun createMethodCallExpr(
    methodName: String,
    arg1Expr: PklExpr,
    arg2Expr: PklExpr,
    project: Project
  ): PklUnqualifiedAccessExpr {
    val module = createModule("x = $methodName(a, b)", project)
    return (module.properties.single().expr as PklUnqualifiedAccessExpr).apply {
      with(argumentList!!) {
        elements[0].replace(arg1Expr)
        elements[1].replace(arg2Expr)
      }
    }
  }

  fun createForGenerator(
    keyVarName: String,
    valueVarName: String,
    expr: PklExpr,
    body: PklObjectBody,
    project: Project
  ): PklForGenerator {
    val module = createModule("x { for ($keyVarName, $valueVarName in y) {} }", project)
    return (module.properties.single().objectBodyList.single().members.single() as PklForGenerator)
      .apply {
        this.iterableExpr!!.replace(expr)
        this.objectBody!!.replace(body)
      }
  }

  fun createLParen(project: Project): LeafPsiElement {
    val module = createModule("x = (42)", project)
    return module.properties.single().expr!!.firstChildOfType(PklElementTypes.LPAREN)
      as LeafPsiElement
  }

  fun createRParen(project: Project): LeafPsiElement {
    val module = createModule("x = (42)", project)
    return module.properties.single().expr!!.firstChildOfType(PklElementTypes.RPAREN)
      as LeafPsiElement
  }

  fun createIdentifier(name: String, project: Project): PsiElement {
    val module = createModule("$name = 42", project)
    return module.properties.single().nameIdentifier
  }

  fun createModifier(name: String, project: Project): PsiElement {
    val module = createModule("$name module Foo", project)
    return module.modifiers.single()
  }

  fun createStringContent(content: String, delimiter: String, project: Project): PklStringContent {
    val escaped = escapeString(content, delimiter)
    val module = createModule("x = $delimiter$escaped${delimiter.reversed()}", project)
    val property = module.properties.first()
    return (property.expr as PklStringLiteral).content
  }

  fun createStringConstant(content: String, project: Project): PklStringConstant {
    val escaped = escapeString(content)
    val module = createModule("import \"$escaped\"", project)
    return module.imports.first().moduleUri!!.stringConstant
  }

  fun createLineComment(text: String, project: Project): PsiComment {
    val module = createModule("// ${text}\nmodule Foo", project)
    return module.firstChildOfClass()!!
  }

  fun createToken(text: String, project: Project): PsiElement =
    createModule(text, project).firstChild!!

  fun createSpread(expr: PklExpr, project: Project): PklObjectSpread {
    val module = createModule("a { ...${expr.text} }", project)
    return module.properties.first().objectBodyList.single().members.first() as PklObjectSpread
  }

  fun createTodo(project: Project): PklExpr {
    val module = createModule("a = TODO()", project)
    return module.properties.first().expr!!
  }

  fun createTypedIdentifier(project: Project, name: String): PklTypedIdentifier {
    val module = createModule("function a($name) = 0", project)
    return module.methods.first().parameterList!!.elements.first()
  }

  fun createClassWithProperties(
    className: String,
    extends: String?,
    properties: List<PklClassProperty>,
    project: Project
  ): PklClass {
    val module =
      createModule(
        buildString {
          append("class $className")
          if (extends != null) {
            append(" extends $extends")
          }
          append(" {\n")
          var isFirst = true
          for (property in properties) {
            if (isFirst) {
              isFirst = false
            } else {
              append("\n\n")
            }
            append("  ${property.modifierList.text} ${property.propertyName.text} = TODO()")
          }
          append("\n}")
        },
        project
      )
    return module.typeDefs.first() as PklClass
  }

  fun createClassPropertyWithoutTypeAnnotation(
    property: PklClassProperty,
    project: Project
  ): PklClassProperty {
    val propertyText = buildString {
      append("${property.modifierList.text} ${property.nameIdentifier.text} = TODO()\n")
    }
    val module = createModule(propertyText, project)
    return module.properties.first()
  }

  fun createAmendsClause(moduleUri: String, project: Project): PklModuleExtendsAmendsClause {
    val module = createModule("amends \"$moduleUri\"", project)
    return module.extendsAmendsClause!!
  }
}
