plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
}

val updateRepository: String = providers.gradleProperty("UPDATE_REPOSITORY").orElse("").get()
// Testy lokalne: adres manifestu lokalnego serwera testowego jako serwer domyślny (np. z emulatora
// `-Plewicowyt.localTestServer=http://10.0.2.2:2140/manifest.json`). Działa WYŁĄCZNIE w buildzie debug —
// release zawsze używa produkcyjnego serwera domyślnego, więc testowy adres nie może trafić do wydania.
val localTestServer: String = providers.gradleProperty("lewicowyt.localTestServer").orElse("").get()

fun String.asBuildConfigString(): String = "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""
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

    // wąskie moduły natywne (patrz app/src/main/cpp): enkoder MozJPEG, dekoder AVIF oraz własny dekoder
    // JPEG XL (progresywny, nad libjxl). NDK skonfigurowany lokalnie.
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "pl.lewicowyt.notifier"
        minSdk = 26
        targetSdk = 36
        versionCode = 20
        versionName = "1.9-beta"

        buildConfigField("String", "UPDATE_REPOSITORY", updateRepository.asBuildConfigString())
        // Samoaktualizacja z GitHub Releases. Wyłączona w wariancie `play` (patrz productFlavors) — aplikacja
        // z Google Play może się aktualizować wyłącznie przez Google Play.
        buildConfigField("boolean", "SELF_UPDATE", "true")
        // pusty = produkcyjny serwer domyślny (CommunityDefaults); nadpisywany tylko w debug (localTestServer)
        buildConfigField("String", "LOCAL_TEST_DEFAULT_ENDPOINT", "\"\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ABI ustawiamy PER FLAVOR (arm = v7a+v8a, x64 = x86_64), nie tutaj — patrz productFlavors niżej.
        externalNativeBuild {
            cmake {
                // libjxl (dekoder JXL) to C++ → potrzebuje STL; linkujemy go statycznie do liblewicowyt_jxl.so.
                // mozjpeg/avif są w C i STL nie używają. (Dawniej było "none", gdy był tylko kod C.)
                arguments += listOf("-DANDROID_STL=c++_static")
                // Budujemy TYLKO nasze biblioteki (nie fuzzery/testy/narzędzia libjxl/highway) — mniej czasu i APK.
                targets += listOf("lewicowyt_mozjpeg", "lewicowyt_avif", "lewicowyt_jxl")
            }
        }
    }

    // OSOBNY APK per ABI (O-1) — mniejsze pliki: arm64-v8a (prawie wszystkie dzisiejsze telefony),
    // armeabi-v7a (stare 32-bit), x86_64 (emulator/maszyny x86). Łączony „arm" (v7a+v8a) dawał +~7 MB
    // dla użytkowników arm64. Aktualizator dobiera właściwy plik po nazwie (…-arm64/…-armv7/…-x64), wg
    // SUPPORTED_ABIS urządzenia (GitHubUpdateChecker.selectApkForAbis) — nigdy nie wgra ABI spoza listy.
    flavorDimensions += "abi"
    productFlavors {
        create("arm64") {
            dimension = "abi"
            ndk { abiFilters += listOf("arm64-v8a") }
        }
        create("armv7") {
            dimension = "abi"
            ndk { abiFilters += listOf("armeabi-v7a") }
        }
        create("x64") {
            dimension = "abi"
            ndk { abiFilters += listOf("x86_64") }
        }
        // jeden wspólny APK „dla każdego” (największy): wszystkie ABI naraz — działa na każdym telefonie,
        // tablecie i emulatorze, bez wybierania pliku. Aktualizator traktuje go jak uniwersalny (brak tokenu
        // ABI w nazwie …-universal.apk), więc instalacja z niego zawsze dostanie pasującą aktualizację.
        create("universal") {
            dimension = "abi"
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        }
        // Wariant dla Google Play (AAB: `./gradlew :app:bundlePlayRelease`). Wszystkie ABI — Play sam dzieli
        // pakiet na urządzenia. Różnice względem wydań z GitHuba (zasady Google Play):
        //  - brak samoaktualizacji: bez REQUEST_INSTALL_PACKAGES, UpdateInstallActivity i FileProvidera
        //    aktualizacji (src/play/AndroidManifest.xml), sprawdzanie GitHub Releases wyłączone;
        //  - uzasadnienia dla recenzentów: docs/GOOGLE_PLAY_REVIEW.md + komentarze w AndroidManifest.xml.
        // Siedzi w wymiarze „abi", bo to po prostu kolejny pakiet „dla wszystkich ABI" — dzięki temu nazwy
        // i zadania wariantów GitHuba (arm64/armv7/x64/universal) zostają bez zmian.
        create("play") {
            dimension = "abi"
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
            buildConfigField("boolean", "SELF_UPDATE", "false")
            buildConfigField("String", "UPDATE_REPOSITORY", "\"\"")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
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
        debug {
            // DEBUG dokłada x86_64 do ABI flavora → build debug (także armDebug) działa na EMULATORZE
            // x86_64, bo natywny Tor (kmp-tor) musi być w ABI urządzenia; na emulatorze arm64 chodzi tylko
            // przez translację i Tor się nie uruchamia. Dzięki temu „Run" z Android Studio na emulatorze
            // zawsze wgrywa wersję z natywnym x86_64. RELEASE zostaje per-flavor (czysty, jeden ABI dla GitHuba).
            ndk { abiFilters += "x86_64" }
            buildConfigField("String", "LOCAL_TEST_DEFAULT_ENDPOINT", localTestServer.asBuildConfigString())
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

    androidResources {
        // O-4: dołączaj zasoby językowe tylko dla PL/EN. Biblioteki (AndroidX/Material) niosą teksty w ~80
        // językach, których apka i tak nie używa — to wycina je z resources.arsc (mniejszy APK, zero funkcji mniej).
        localeFilters += listOf("pl", "en")
    }

    // Q-01: geoip Tora (geoip.gz / geoip6.gz, ~4,5 MB) MUSI zostać w APK. kmp-tor (resource-noexec-tor)
    // przy każdym starcie bezwarunkowo wypakowuje go w TorConfigGenerator (noExecExtractGeoips → brak zasobu
    // = IOException), więc wykluczenie z O-2 sprawiało, że wbudowany Tor nigdy nie startował (cały .onion martwy).
    // NIE dodawać tu `packaging.resources.excludes` dla io/matthewnelson/kmp/tor/resource/geoip/*.

    // B-90: zdublowany blok `packaging { jniLibs { useLegacyPackaging = true } }` usunięty — to samo ustawia
    // blok niżej. Natywne .so są przechowywane SKOMPRESOWANE (mniejszy APK z GitHub Releases; dla AAB/Play
    // zwykle lepiej false).

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

// Wariant `play` (Google Play) jest na razie WYŁĄCZONY: nie pojawia się w Build Variants, w kreatorze
// podpisanego APK ani w zadaniach Gradle. Włączymy go po wyjściu z bety — wystarczy usunąć ten blok.
androidComponents {
    beforeVariants { variant ->
        if (variant.productFlavors.any { it.second == "play" }) variant.enable = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    // Fragment >= 1.3.0 wymagany przez ActivityResult API (lint: InvalidFragmentVersionForActivityResult). Dotąd podbijał go
    // przechodnio jxl-coder; po jego usunięciu graf spadał do 1.1.0, więc przypinamy jawnie wersję już zweryfikowaną w gradle/verification-metadata.xml.
    implementation("androidx.fragment:fragment:1.5.4")

    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
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
    // jxl-coder usunięty: JXL obsługuje wyłącznie własny most NativeJxl (libjxl z limitami, w tym enkoder)
    implementation("com.github.luben:zstd-jni:1.5.7-6@aar")
    implementation("com.davidehrmann.vcdiff:vcdiff-core:0.2.0")
    implementation("com.squareup.okhttp3:okhttp:5.3.0")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:5.3.0")

    // Firebase Cloud Messaging — odbiór powiadomień o nowych filmach z serwera 1.9
    // (temat broadcast "feed"). Wersje spięte przez BoM.
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging")

    // WorkManager — trwała obsługa powiadomienia z serwera po odebraniu FCM
    // (przetrwa ubicie procesu; ponawia sprawdzenie playlisty co minutę do 15 razy).
    implementation("androidx.work:work-runtime-ktx:2.10.1")

    // Dźwięk (Opus) z serwerów społeczności: odtwarzanie natywne w tle (sesja multimediów, powiadomienie,
    // ekran blokady), pętla i prędkość do 3×. Opus dekoduje systemowy MediaCodec — bez bibliotek natywnych.
    val media3Version = "1.11.1"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-session:$media3Version")

    // UnifiedPush — otwarty push bez Google: serwer społeczności wypycha "ping" o zmianie,
    // apka dociąga świeży manifest. Dystrybutor (np. ntfy) dostarcza push do apki.
    implementation("org.unifiedpush.android:connector:2.5.0")

    // Wbudowany Tor — kmp-tor w wariancie NOEXEC: natywny `tor` (0.4.9.x) ładowany WEWNĄTRZ procesu przez
    // mały shim libtorjni.so (~13 KB, 16 KB-aligned), bez osobnego procesu i bez trwałej ekstrakcji wielkiej
    // biblioteki na dysk (Tor trzymany skompresowany, wczytywany do pamięci). Mniejszy ślad na dysku telefonu
    // i w APK niż info.guardianproject:tor-android (libtor.so ~9.3 MB/ABI rozpakowywane na /data). Maven Central.
    // Wariant bez `-gpl` — apka nie jest GPL.
    implementation("io.matthewnelson.kmp-tor:runtime:2.6.0")
    implementation("io.matthewnelson.kmp-tor:resource-noexec-tor:409.5.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260522")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
