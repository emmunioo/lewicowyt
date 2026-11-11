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
| MozJPEG 4.1.1 (libjpeg-turbo; własny moduł `liblewicowyt_mozjpeg.so`, zapis memów) | IJG License, BSD 3-Clause i zlib | <https://github.com/mozilla/mozjpeg> |
| libjxl 0.11.1 (własny moduł `liblewicowyt_jxl.so`) z Highway, Brotli i skcms | BSD 3-Clause (libjxl, skcms), Apache License 2.0 (Highway), MIT (Brotli) | <https://github.com/libjxl/libjxl>, <https://skia.googlesource.com/skcms> |
| libavif 1.1.1 (własny moduł `liblewicowyt_avif.so`, dekodowanie AVIF na Androidzie < 12) | BSD 2-Clause | <https://github.com/AOMediaCodec/libavif> |
| libaom 3.9.0 (dekoder AV1 dla libavif) | BSD 2-Clause + Alliance for Open Media Patent License 1.0 | <https://aomedia.googlesource.com/aom>, <https://aomedia.org/license/patent-license/> |
| Tor 0.4.9.x (`kmp-tor resource-noexec-tor` 409.5.0, wbudowany Tor) | BSD 3-Clause | <https://www.torproject.org> |
| Biblioteki dołączone do Tora: libevent, OpenSSL, zlib | BSD 3-Clause (libevent), Apache License 2.0 (OpenSSL 3), zlib | <https://libevent.org>, <https://www.openssl.org>, <https://zlib.net> |
| kmp-tor runtime 2.6.0 | Apache License 2.0 | <https://github.com/05nelsonm/kmp-tor> |
| UnifiedPush Android connector 2.5.0 | Apache License 2.0 | <https://codeberg.org/UnifiedPush/android-connector> |
| AndroidX Media3 / ExoPlayer 1.11.1 (odtwarzanie dźwięku i filmów) | Apache License 2.0 | <https://github.com/androidx/media> |
| Guava (zależność Media3) | Apache License 2.0 | <https://github.com/google/guava> |
| Little CMS 2 (zarządzanie kolorem w jxl-coder, `libjxlcoder.so`) | MIT | <https://github.com/mm2/Little-CMS> |
| libyuv (konwersja kolorów w libavif, `liblewicowyt_avif.so`) | BSD 3-Clause | <https://chromium.googlesource.com/libyuv/libyuv> |
| Public Suffix List (dołączona przez OkHttp, `assets/PublicSuffixDatabase.list`, bez zmian) | Mozilla Public License 2.0 | <https://publicsuffix.org/list/>, tekst licencji: <https://www.mozilla.org/MPL/2.0/> |
| Firebase Cloud Messaging (BoM 33.7.0; tylko awaryjny push, domyślnie wyłączony) | Apache License 2.0 (zależności Google Play services: Android Software Development Kit License) | <https://github.com/firebase/firebase-android-sdk> |

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
- MozJPEG / libjpeg-turbo: Copyright © D. R. Commander, Mozilla Corporation, Viktor Szathmáry i współtwórcy;
  „this software is based in part on the work of the Independent JPEG Group" (wymóg licencji IJG);
- libavif: Copyright © Joe Drago i współtwórcy; libaom: Copyright © Alliance for Open Media;
- skcms: Copyright © Google LLC;
- Tor: Copyright © The Tor Project, Inc., Roger Dingledine, Nick Mathewson i współtwórcy;
- libevent: Copyright © Niels Provos, Nick Mathewson i współtwórcy; OpenSSL: Copyright © The OpenSSL Project
  Authors; zlib: Copyright © Jean-loup Gailly i Mark Adler;
- kmp-tor: Copyright © Matthew Nelson; UnifiedPush connector: Copyright © UnifiedPush i współtwórcy;
- Firebase: Copyright © Google LLC.
- Media3: Copyright © The Android Open Source Project; Guava: Copyright © The Guava Authors;
- Little CMS 2: Copyright © Marti Maria Saguer; libyuv: Copyright © The LibYuv Project Authors;
- Public Suffix List: Copyright © Mozilla Foundation i współtwórcy (MPL 2.0, źródło: <https://publicsuffix.org/list/>).

Pełne teksty licencji znajdują się w katalogu [`LICENSES`](LICENSES) (Apache 2.0, BSD 2/3-Clause, MIT,
zlib) i — dla licencji wymagających powtórzenia noty w dystrybucji binarnej — w zasobie tekstowym
dołączanym do APK (`res/raw/third_party_notices.txt`, ekran „Licencje” w aplikacji). Prawa autorskie i znaki towarowe pozostają
przy ich właścicielach.
