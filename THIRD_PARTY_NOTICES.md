# Komponenty zewnętrzne

Licencja [Anti-Capitalist Software License 1.4](LICENSE) dotyczy wyłącznie
oryginalnego kodu i dokumentacji tego projektu. Nie zmienia licencji bibliotek,
narzędzi, znaków towarowych ani treści należących do osób trzecich.

W aplikacji są używane następujące główne komponenty:

| Komponent | Licencja | Projekt |
|---|---|---|
| AndroidX, Jetpack Compose, DataStore i Material Components | Apache License 2.0 | <https://developer.android.com/jetpack/androidx> |
| SQLite dołączony przez AndroidX SQLite Bundled | Domena publiczna | <https://www.sqlite.org/copyright.html> |
| Kotlin i biblioteki Kotlin | Apache License 2.0 | <https://github.com/JetBrains/kotlin> |
| OkHttp i Okio | Apache License 2.0 | <https://github.com/square/okhttp>, <https://github.com/square/okio> |
| jxl-coder 2.6.1 | Apache License 2.0 i BSD 3-Clause | <https://github.com/awxkee/jxl-coder> |
| zstd-jni i Zstandard | BSD 2-Clause i BSD 3-Clause | <https://github.com/luben/zstd-jni>, <https://github.com/facebook/zstd> |
| JPEG XL / libjxl dołączony przez jxl-coder | BSD 3-Clause | <https://github.com/libjxl/libjxl> |
| Brotli, używany przez libjxl | MIT | <https://github.com/google/brotli> |
| Highway, używany przez libjxl | Apache License 2.0 | <https://github.com/google/highway> |
| VCDIFF Java 0.2.0 (dekoder aktualizacji różnicowych w APK) | Apache License 2.0 | <https://github.com/ehrmann/vcdiff-java> |
| slf4j-api 2.0.17 (przechodnia zależność vcdiff-core, bez aktywnego providera w APK) | MIT | <https://www.slf4j.org> |
| Xdelta3 3.2.0 (wyłącznie narzędzie wydaniowe, niedołączane do APK) | Apache License 2.0 | <https://github.com/jmacd/xdelta> |

Informacje o prawach autorskich zachowane na potrzeby redystrybucji:

- jxl-coder: Copyright © Radzivon Bartoshyk i współtwórcy;
- zstd-jni i Zstandard: Copyright © Luben Karavelov, Meta Platforms i współtwórcy;
- JPEG XL / libjxl: Copyright © JPEG XL Project Authors;
- Brotli i Highway: Copyright © Google LLC i współtwórcy;
- OkHttp i Okio: Copyright © Square, Inc. i współtwórcy;
- Kotlin: Copyright © JetBrains s.r.o. i współtwórcy;
- AndroidX i Material Components: Copyright © The Android Open Source Project.
- VCDIFF Java: Copyright © Google Inc., David Ehrmann i współtwórcy;
- slf4j-api: Copyright © QOS.ch;
- Xdelta3: Copyright © Joshua P. MacDonald i współtwórcy (narzędzie wydaniowe).

Pełne teksty licencji znajdują się w katalogu [`LICENSES`](LICENSES) i w
zasobie tekstowym dołączanym do APK. Prawa autorskie i znaki towarowe pozostają
przy ich właścicielach.
