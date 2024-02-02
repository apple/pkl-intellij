pluginManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
  }
}

private val javaVersion = JavaVersion.current()

require(javaVersion.isCompatibleWith(JavaVersion.VERSION_17)) {
  "Project requires Java 17 or higher, but found ${javaVersion.majorVersion}."
}

rootProject.name = "pkl-intellij"
