plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.android") version "2.3.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
    // Przetwarza app/google-services.json i wstrzykuje konfigurację Firebase
    // (FCM). Wymagane, by aplikacja odbierała powiadomienia z serwera 1.9.
    id("com.google.gms.google-services") version "4.4.2" apply false
}

// Wszystkie odtwarzalne wyniki Gradle zapisujemy poza katalogiem źródeł.
// Dzięki temu kompilacja nie zaśmieca projektu ani kopii przeznaczonej do Git.
val externalBuildRoot = layout.projectDirectory.dir("../KOMPILACJA")

layout.buildDirectory.set(externalBuildRoot.dir("root"))

subprojects {
    layout.buildDirectory.set(externalBuildRoot.dir(name))
}
