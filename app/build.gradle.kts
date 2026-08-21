plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val updateRepository: String = providers.gradleProperty("UPDATE_REPOSITORY").orElse("").get()
val isWindowsHost = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
val avatarScript = rootProject.file("tools/Prepare-BundledAvatars.ps1")
val creatorCatalog = project.file("src/main/assets/creators.json")
val bundledAvatarDirectory = project.file("src/main/assets/bundled_avatars")
val ytDlpPath = providers.gradleProperty("LEWICOWYT_YTDLP_PATH").orElse("")
val cjxlPath = providers.gradleProperty("LEWICOWYT_CJXL_PATH").orElse("")
val avatarCommand = mutableListOf(
    "powershell.exe",
    "-NoProfile",
    "-ExecutionPolicy",
    "Bypass",
    "-File",
    avatarScript.absolutePath,
    "-ProjectRoot",
    rootProject.projectDir.absolutePath,
    "-AllowExistingOnFailure",
).apply {
    ytDlpPath.get().takeIf(String::isNotBlank)?.let {
        addAll(listOf("-YtDlpPath", it))
    }
    cjxlPath.get().takeIf(String::isNotBlank)?.let {
        addAll(listOf("-CjxlPath", it))
    }
}

tasks.register<Exec>("refreshBundledAvatars") {
    group = "release tooling"
    description = "Jawnie odświeża zamrożony pakiet awatarów JXL 176x176 (sieć)"
    enabled = isWindowsHost
    inputs.files(avatarScript, creatorCatalog)
    inputs.property("ytDlpPath", ytDlpPath)
    inputs.property("cjxlPath", cjxlPath)
    outputs.dir(bundledAvatarDirectory)
    // Wynik zależy od żywych profili YouTube, których Gradle nie potrafi
    // zahaszować jako wejścia. Jawne wywołanie ma więc zawsze robić refresh.
    outputs.upToDateWhen { false }
    commandLine(avatarCommand)
}

android {
    namespace = "pl.lewicowyt.notifier"

    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "pl.lewicowyt.notifier"
        minSdk = 26
        targetSdk = 36
        versionCode = 18
        versionName = "1.7-beta"

        buildConfigField(
            "String",
            "UPDATE_REPOSITORY",
            "\"${updateRepository.replace("\\", "\\\\").replace("\"", "\\\"")}\"",
        )

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets.getByName("main").assets.srcDir(rootProject.file("LICENSES"))

    packaging {
        // Bezpośrednio generowany APK zapisuje pliki DEX i biblioteki natywne
        // jako skompresowane wpisy ZIP (DEFLATE). Android musi je wtedy
        // wyodrębnić podczas instalacji, więc mniejszy APK nie musi oznaczać
        // mniejszego zużycia miejsca przez zainstalowaną aplikację.
        dex {
            useLegacyPackaging = true
        }
        jniLibs {
            useLegacyPackaging = true
            // preferencesDataStore działa w jednym procesie. Natywny licznik
            // DataStore jest potrzebny wyłącznie wariantowi wieloprocesowemu,
            // a jego prekompilowany plik nie ma stack canary.
            excludes += "**/libdatastore_shared_counter.so"
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-compose:1.13.0")

    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    val lifecycleVersion = "2.10.0"
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:$lifecycleVersion")

    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("androidx.sqlite:sqlite-bundled:2.7.0")
    implementation("io.github.awxkee:jxl-coder:2.6.1")
    implementation("com.github.luben:zstd-jni:1.5.7-6@aar")
    implementation("com.davidehrmann.vcdiff:vcdiff-core:0.2.0")
    implementation("com.squareup.okhttp3:okhttp:5.3.0")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:5.3.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260522")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
