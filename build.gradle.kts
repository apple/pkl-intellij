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
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.*
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  `java-library`
  `maven-publish`
  idea

  alias(libs.plugins.kotlin)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.intelliJ)
  alias(libs.plugins.intelliJGrammarKit)
  alias(libs.plugins.spotless)
}

repositories {
  mavenCentral()
  intellijPlatform { defaultRepositories() }
}

configurations { all { resolutionStrategy { failOnDynamicVersions() } } }

val isCiBuild = System.getenv("CI") != null
val isReleaseBuild = System.getProperty("releaseBuild") != null

if (!isReleaseBuild) {
  version = "$version-SNAPSHOT"
}

val pluginVersion by lazy {
  if (isReleaseBuild) project.version.toString()
  else {
    val commitId =
      Runtime.getRuntime()
        .exec(arrayOf("git", "rev-parse", "--short", "HEAD"))
        .inputStream
        .reader()
        .readText()
        .trim()
    project.version.toString().replace("-SNAPSHOT", "-dev+$commitId")
  }
}

java {
  sourceCompatibility = JavaVersion.VERSION_21
  targetCompatibility = JavaVersion.VERSION_21
}

sourceSets { main { java { srcDir("generated") } } }

val externalReaderFixture: SourceSet =
  sourceSets.create("externalReaderFixture") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
  }

configurations.named(externalReaderFixture.implementationConfigurationName) {
  // msgpack, the Kotlin standard library, etc.
  extendsFrom(configurations.implementation.get())
}

val externalReaderFixtureArgfile = layout.buildDirectory.file("externalReaderFixture/java.argfile")

val buildExternalReaderFixture =
  tasks.register("buildExternalReaderFixture") {
    group = "build"
    description = "Builds the external reader fixture, and writes the java argfile that runs it."
    dependsOn(externalReaderFixture.classesTaskName)
    inputs.files(externalReaderFixture.runtimeClasspath)
    outputs.file(externalReaderFixtureArgfile)
    doLast {
      // Argfile syntax: arguments are separated by whitespace; quoted values can contain spaces,
      // and use `\` as the escape character.
      fun quote(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
      val argfile = externalReaderFixtureArgfile.get().asFile
      argfile.parentFile.mkdirs()
      argfile.writeText(
        listOf(
            "-cp",
            quote(externalReaderFixture.runtimeClasspath.asPath),
            "org.pkl.intellij.externalreader.fixture.ExternalReaderFixtureKt",
          )
          .joinToString("\n", postfix = "\n")
      )
    }
  }

val pklCli: Configuration = configurations.create("pklCli")

val nativePklCli: Pair<Provider<MinimalExternalModuleDependency>, String>? = run {
  val os = System.getProperty("os.name").lowercase()
  val arch =
    when (System.getProperty("os.arch").lowercase()) {
      "aarch64",
      "arm64" -> "aarch64"
      "amd64",
      "x86_64" -> "amd64"
      else -> null
    }
  when {
    os.startsWith("mac") && arch == "aarch64" -> libs.pklCliMacOsAarch64 to "bin"
    os.startsWith("linux") && arch == "aarch64" -> libs.pklCliLinuxAarch64 to "bin"
    os.startsWith("linux") && arch == "amd64" -> libs.pklCliLinuxAmd64 to "bin"
    os.startsWith("windows") && arch == "amd64" -> libs.pklCliWindowsAmd64 to "exe"
    else -> null
  }
}

fun pklExecutable(): File {
  if (nativePklCli == null) {
    throw GradleException(
      "There is no native Pkl CLI for this machine (os.name=${System.getProperty("os.name")}, " +
        "os.arch=${System.getProperty("os.arch")}); tests need one. Supported: macOS aarch64, " +
        "Linux aarch64, Linux amd64, Windows amd64."
    )
  }
  return pklCli.singleFile
}

val pklStdlib: Configuration = configurations.create("pklStdlib")

dependencies {
  pklStdlib(libs.pklStdLib)
  implementation(libs.kotlinxJson)
  // needed for kotlin ui dsl:
  // https://plugins.jetbrains.com/docs/intellij/kotlin-ui-dsl-version-2.html
  implementation(libs.kotlinReflect)
  implementation(libs.pklFormatter)

  // dependencies of pkl-messaging (delete me after pkl-messaging is published as its own artifact)
  implementation(libs.msgpack)
  compileOnly(libs.errorProneAnnotations)

  testImplementation(libs.assertj)
  nativePklCli?.let { (library, extension) ->
    pklCli(variantOf(library) { artifactType(extension) })
  }

  testImplementation(libs.junit)

  // IntelliJ Platform dependencies
  intellijPlatform {
    create("IC", libs.versions.intellij.get())
    pluginVerifier()
    bundledPlugin("org.intellij.intelliLang")
    bundledPlugin("org.intellij.plugins.markdown")
    testFramework(TestFrameworkType.Platform, libs.versions.intellijTestPlatform.get())
  }
}

tasks.jar {
  // Either Gradle or the IDEA plugin doesn't copy the `.zip` file into the plugin classpath
  // directly, so we need to wire this up ourselves.
  from(zipTree(pklStdlib.singleFile))
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
    failureLevel.set(
      setOf(
        VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
        VerifyPluginTask.FailureLevel.MISSING_DEPENDENCIES,
        VerifyPluginTask.FailureLevel.INVALID_PLUGIN,
      )
    )
  }
}

idea { module { excludeDirs = excludeDirs + setOf(file("ides")) } }

tasks.runIde {
  //  version = libs.versions.intellijRunIde.get()
  //  ideDir.set(file("$projectDir/ides/IC-${libs.versions.intellijRunIde.get()}"))
  jvmArgs =
    listOf("-XX:+UnlockDiagnosticVMOptions", "--add-exports=java.base/jdk.internal.vm=ALL-UNNAMED")
}

val generateLexer =
  tasks.getByName<GenerateLexerTask>("generateLexer") {
    val inputFile = "src/main/grammar/pkl.flex"
    val outputDir = "generated/org/pkl/intellij/lexer"

    inputs.file(inputFile)
    outputs.dir(outputDir)

    sourceFile.set(file(inputFile))
    targetRootOutputDir.set(file("generated"))
    packageName.set("org.pkl.intellij.lexer")
    purgeOldFiles.set(true)
  }

val generateParser =
  tasks.getByName<GenerateParserTask>("generateParser") {
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

tasks.check { dependsOn(tasks.named("verifyPlugin")) }

tasks.clean { delete("generated") }

tasks.compileKotlin { dependsOn(generateLexer, generateParser) }

val configurePklCliExecutable =
  tasks.register("configurePklCliExecutable") { doLast { pklExecutable().setExecutable(true) } }

tasks.test {
  dependsOn(configurePklCliExecutable)
  dependsOn(buildExternalReaderFixture)
  val pklCacheDir = layout.buildDirectory.dir("pklTestCache").get()
  systemProperties["org.pkl.intellij.pklCacheDir"] = pklCacheDir.asFile.absolutePath
  doFirst {
    // (resolved here, not when configuring, so that other tasks work on machines without a native
    // CLI)
    systemProperty("org.pkl.intellij.pklExecutable", pklExecutable().absolutePath)
    pklCacheDir.asFile.deleteRecursively()
  }
}

tasks.withType<KotlinCompile>().configureEach {
  compilerOptions {
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
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
          system.set("GitHub Actions")
          url.set("https://github.com/apple/pkl-intellij/actions")
        }
      }
    }
  }
}

val printVersion = tasks.register("printVersion") { doFirst { println(version) } }

private val licenseHeader =
  """
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
  """
    .trimIndent()

val originalRemoteName = System.getenv("PKL_ORIGINAL_REMOTE_NAME") ?: "origin"

spotless {
  ratchetFrom = "$originalRemoteName/main"
  kotlinGradle {
    ktfmt(libs.versions.ktfmt.get()).googleStyle()
    targetExclude("**/generated/**", "**/build/**")
    licenseHeader(licenseHeader, "([a-zA-Z]|@file|//)")
    target("*.kts", "buildSrc/**/*.kts")
  }
  kotlin {
    ktfmt(libs.versions.ktfmt.get()).googleStyle()
    licenseHeader(licenseHeader)
  }
}
