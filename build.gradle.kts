plugins {
    id("com.android.application") version "9.3.1" apply false
    id("org.jetbrains.kotlin.android") version "2.3.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
}

// Wszystkie odtwarzalne wyniki Gradle zapisujemy poza katalogiem źródeł.
// Dzięki temu kompilacja nie zaśmieca projektu ani kopii przeznaczonej do Git.
val externalBuildRoot = layout.projectDirectory.dir("../KOMPILACJA")

layout.buildDirectory.set(externalBuildRoot.dir("root"))

subprojects {
    layout.buildDirectory.set(externalBuildRoot.dir(name))
}
