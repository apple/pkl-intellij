import org.jetbrains.grammarkit.tasks.*
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  `java-library`
  `maven-publish`
  idea

  alias(libs.plugins.kotlin)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.grammarKit)
  alias(libs.plugins.intelliJ)
  alias(libs.plugins.spotless)
}

repositories {
  mavenCentral()
  intellijPlatform {
    defaultRepositories()
  }
}

val isCiBuild = System.getenv("CI") != null
val isReleaseBuild = System.getProperty("releaseBuild") != null
if (!isReleaseBuild) {
  version = "$version-SNAPSHOT"
}
val pluginVersion by lazy {
  if (isReleaseBuild) project.version.toString()
  else {
    val commitId =
        Runtime.getRuntime().exec(arrayOf("git", "rev-parse", "--short", "HEAD")).inputStream.reader().readText().trim()
    project.version.toString().replace("-SNAPSHOT", "-dev+$commitId")
  }
}

java {
  sourceCompatibility = JavaVersion.VERSION_17
}

sourceSets {
  main {
    java {
      srcDir("generated")
    }
  }
}

val pklCli: Configuration by configurations.creating

dependencies {
  // put stdlib ZIP on plugin class path instead of exploding it into plugin JAR
  // (saves work and `Class(Loader).getResource()` doesn't care)
  implementation(libs.pklStdLib) {
    attributes {
      attribute(Attribute.of("intellijPlatformCollected", Boolean::class.javaObjectType), false)
    }
  }
  implementation(libs.kotlinxJson)
  // needed for kotlin ui dsl: https://plugins.jetbrains.com/docs/intellij/kotlin-ui-dsl-version-2.html
  implementation(libs.kotlinReflect)

  implementation(libs.pklFormatter)

  testImplementation(libs.assertj)
  pklCli(libs.pklCli)

  testImplementation(libs.junit)

  // IntelliJ Platform dependencies
  intellijPlatform {
    create("IC", libs.versions.intellij.get())
    pluginVerifier()
    bundledPlugin("org.intellij.intelliLang")
    bundledPlugin("org.intellij.plugins.markdown")
    testFramework(TestFrameworkType.Platform)
  }
}

idea {
  module {
    sourceDirs = sourceDirs + listOf(file("src/main/grammar"), file("generated"))
    generatedSourceDirs = generatedSourceDirs + listOf(file("generated"))
  }
}

intellijPlatform {
  pluginConfiguration {
    name.set("Pkl")
    version.set(pluginVersion)
    ideaVersion {
      sinceBuild.set(libs.versions.intellijSinceBuild)
      untilBuild.set(libs.versions.intellijUntilBuild)
    }
  }

  instrumentCode.set(false)

  pluginVerification {
    ides {
      create("IC", libs.versions.intellij.get())
      create("IC", libs.versions.intellijRunIde.get())
      create("GO", libs.versions.goLand.get())
    }
    subsystemsToCheck.set(VerifyPluginTask.Subsystems.WITHOUT_ANDROID)
    failureLevel.set(setOf(
      VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
      VerifyPluginTask.FailureLevel.MISSING_DEPENDENCIES,
      VerifyPluginTask.FailureLevel.INVALID_PLUGIN
    ))
  }
}

grammarKit {
  jflexRelease.set(libs.versions.jflex.get())
  grammarKitRelease.set(libs.versions.grammarKit.get())
}

idea {
  module {
    excludeDirs = excludeDirs + setOf(file("ides"))
  }
}

tasks.runIde {
//  version = libs.versions.intellijRunIde.get()
//  ideDir.set(file("$projectDir/ides/IC-${libs.versions.intellijRunIde.get()}"))
  jvmArgs = listOf(
      "-XX:+UnlockDiagnosticVMOptions",
      "--add-exports=java.base/jdk.internal.vm=ALL-UNNAMED"
  )
}

val generateLexer by tasks.existing(GenerateLexerTask::class) {
  val inputFile = "src/main/grammar/pkl.flex"
  val outputDir = "generated/org/pkl/intellij/lexer"

  inputs.file(inputFile)
  outputs.dir(outputDir)

  sourceFile.set(file(inputFile))
  targetOutputDir.set(file(outputDir))
  purgeOldFiles.set(true)
}

val generateParser by tasks.existing(GenerateParserTask::class) {
  val inputFile = "src/main/grammar/pkl.bnf"
  val outputDir = "generated/org/pkl/intellij/parser"

  inputs.file(inputFile)
  outputs.dir(outputDir)

  sourceFile.set(file(inputFile))
  targetRootOutputDir.set(file("generated"))
  pathToParser.set("/org/pkl/intellij/parser/PklParser.java")
  pathToPsiRoot.set("/org/pkl/intellij/psi")
  purgeOldFiles.set(true)
}

tasks.check {
  dependsOn(tasks.named("verifyPlugin"))
}

tasks.clean {
  delete("generated")
}

tasks.compileKotlin {
  dependsOn(generateLexer, generateParser)
}

val configurePklCliExecutable by tasks.registering {
  doLast {
    pklCli.singleFile.setExecutable(true)
  }
}

tasks.test {
  dependsOn(configurePklCliExecutable)
  systemProperties["pklExecutable"] = pklCli.singleFile.absolutePath
  System.getProperty("testReportsDir")?.let { reportsDir ->
    reports.junitXml.outputLocation.set(file(reportsDir).resolve(project.name).resolve(name))
  }
}

tasks.withType<KotlinCompile>().configureEach {
  compilerOptions {
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
    apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
    freeCompilerArgs.add("-Xjsr305=strict")
    freeCompilerArgs.add("-opt-in=kotlin.RequiresOptIn")
  }
}

tasks.publishPlugin {
  // disable this task (publishes to plugins.jetbrains.com)
  enabled = false
}

publishing {
  repositories {
    maven {
      name = "projectLocal" // affects task names
      url = uri("file:///${layout.buildDirectory.get()}/m2")
    }
  }

  publications {
    create<MavenPublication>("pluginZip") {
      artifactId = "pkl-intellij"

      artifact(tasks.buildPlugin.map { it.outputs.files.singleFile }) {
        classifier = null
        extension = "zip"
        builtBy(tasks.buildPlugin)
      }

      pom {
        description.set("Pkl plugin for JetBrains IDEs.")
        licenses {
          name.set("Apache License 2.0")
          url.set("https://github.com/apple/pkl-intellij/blob/main/LICENSE.txt")
        }
        developers {
          developer {
            id.set("pkl-authors")
            name.set("The Pkl Authors")
            email.set("pkl-oss@group.apple.com")
          }
        }
        scm {
          connection.set("scm:git:git://github.com/apple/pkl-intellij.git")
          developerConnection.set("scm:git:ssh://github.com/apple/pkl-intellij.git")
          url.set("https://github.com/apple/pkl/tree/${if (isReleaseBuild) version else "main"}")
        }
        issueManagement {
          system.set("GitHub Issues")
          url.set("https://github.com/apple/pkl-intellij/issues")
        }
        ciManagement {
          system.set("Circle CI")
          url.set("https://app.circleci.com/pipelines/github/apple/pkl-intellij")
        }
      }
    }
  }
}

val printVersion by tasks.registering {
  doFirst { println(version) }
}

private val licenseHeader = """
  /**
   * Copyright © ${'$'}YEAR Apple Inc. and the Pkl project authors. All rights reserved.
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
""".trimIndent()

val originalRemoteName = System.getenv("PKL_ORIGINAL_REMOTE_NAME") ?: "origin"

spotless {
  ratchetFrom = "$originalRemoteName/main"
  kotlin {
    ktfmt("0.44").googleStyle()
    licenseHeader(licenseHeader)
  }
}
