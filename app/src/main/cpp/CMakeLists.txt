# Własne, wąskie moduły natywne dobrane pod ROZMIAR APK, bez SIMD/NASM i bez zbędnych części (HEIC,
# przykłady, testy). A-04: opis zgodny z tym, co faktycznie budujemy:
#   liblewicowyt_mozjpeg.so — MozJPEG (libjpeg-turbo + trellis/progresja), ENCODE-only (zapis mema)
#   liblewicowyt_avif.so    — AVIF DECODE-only (libavif + dekoder dav1d; tylko dla Androida < 12)
#   liblewicowyt_jxl.so     — JPEG XL decode-only (libjxl), nasz progresywny JNI (patrz jxl_jni.c)
cmake_minimum_required(VERSION 3.22.1)
# C ORAZ CXX — libjxl (dekoder JXL) i jego zależności (highway/skcms) są w C++, a nasz target lewicowyt_jxl
# linkujemy linkerem C++ (pusty jxl_cxx_link.cc). mozjpeg/aom/avif pozostają w C.
project(lewicowyt_codecs C CXX ASM)

include(FetchContent)

# ---------------------------------------------------------------------------------------------------
# Utwardzanie (dla audytorów): dekodery parsują NIEUFNE dane z sieci, więc wszystkie biblioteki (nasze JNI i
# zależności: libjxl, aom, libavif, mozjpeg) budujemy z ochroną przed typowymi skutkami błędów pamięci.
#   -fstack-protector-strong   kanarki stosu
#   -D_FORTIFY_SOURCE=2        kontrola granic w memcpy/strcpy itp. (tylko z optymalizacją; w Debug bez niego)
#   -fvisibility=hidden        symbole ukryte; eksportujemy jedynie JNI (JNIEXPORT = visibility default)
#   -mbranch-protection=standard  PAC/BTI na arm64
#   -Wl,-z,relro,-z,now        pełne RELRO (tablica GOT tylko do odczytu po starcie)
#   -Wl,--exclude-libs,ALL     nie eksportuj symboli bibliotek statycznych (brak kolizji symboli ELF)
#   -Wl,-z,max-page-size=16384 strony 16 KB (Android 15+) — ustawiane też per target niżej
# ---------------------------------------------------------------------------------------------------
add_compile_options(-fstack-protector-strong -fvisibility=hidden -fvisibility-inlines-hidden)
add_compile_options("$<$<NOT:$<CONFIG:Debug>>:-D_FORTIFY_SOURCE=2>")
if(ANDROID_ABI STREQUAL "arm64-v8a")
    add_compile_options(-mbranch-protection=standard)
endif()
add_link_options(-Wl,-z,relro,-z,now -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384)


# ---------------------------------------------------------------------------------------------------
# MozJPEG (libjpeg-turbo, fork mozilla) — statyczny, bez SIMD/NASM, bez TurboJPEG/PNG
# ---------------------------------------------------------------------------------------------------
set(WITH_SIMD OFF CACHE BOOL "" FORCE)
set(ENABLE_SHARED OFF CACHE BOOL "" FORCE)
set(ENABLE_STATIC ON CACHE BOOL "" FORCE)
set(WITH_TURBOJPEG OFF CACHE BOOL "" FORCE)
set(PNG_SUPPORTED OFF CACHE BOOL "" FORCE)
set(WITH_JPEG8 ON CACHE BOOL "" FORCE)

FetchContent_Declare(
    mozjpeg
    GIT_REPOSITORY https://github.com/mozilla/mozjpeg.git
    # F-18: przypięte do SHA commitu (niezmienne) zamiast ruchomego tagu v4.1.1. GIT_SHALLOW musi być
    # FALSE, bo płytki klon nie pobierze dowolnego SHA. Weryfikacja: ten commit = tag v4.1.1.
    GIT_TAG a2d2907ff023227e80c1e4efa809812410275a12
    GIT_SHALLOW FALSE
)
FetchContent_MakeAvailable(mozjpeg)

add_library(lewicowyt_mozjpeg SHARED mozjpeg_jni.c)
target_include_directories(lewicowyt_mozjpeg PRIVATE ${mozjpeg_SOURCE_DIR} ${mozjpeg_BINARY_DIR})
target_link_libraries(lewicowyt_mozjpeg jpeg-static jnigraphics log)
# 16 KB page size (Android 15+): wyrównaj segmenty LOAD do 16 KB
target_link_options(lewicowyt_mozjpeg PRIVATE "-Wl,-z,max-page-size=16384")

# ---------------------------------------------------------------------------------------------------
# AVIF decode-only: dav1d (dekoder AV1, własny CMake w dav1d.cmake — upstream używa Mesona) + libavif (kontener)
# Potrzebne tylko na Androidzie < 12 (API < 31) — wyżej AVIF dekoduje platforma. dav1d zastąpił libaom:
# szybszy, mniejszy, asembler NEON na ARM. Tag 1.5.4 jest ADNOTOWANY: przypinamy commit (git ls-remote ...^{}).
# ---------------------------------------------------------------------------------------------------
find_package(Threads REQUIRED)
FetchContent_Declare(
    dav1d
    GIT_REPOSITORY https://code.videolan.org/videolan/dav1d.git
    # F-18: SHA commitu = tag 1.5.4 (niezmienne). GIT_SHALLOW FALSE — jw.
    GIT_TAG 54706fc6bc0cdecab7e9593974a4039cc038fca7
    GIT_SHALLOW FALSE
)
FetchContent_GetProperties(dav1d)
if(NOT dav1d_POPULATED)
    FetchContent_Populate(dav1d)
endif()
include("${CMAKE_CURRENT_LIST_DIR}/dav1d.cmake")

# libavif: dekoder AVIF; używa ISTNIEJĄCEGO targetu dav1d::dav1d (AVIF_CODEC_DAV1D=SYSTEM), bez aom,
# tylko decode, bez libyuv/sharpyuv/apps/testów.
set(AVIF_CODEC_DAV1D SYSTEM CACHE STRING "" FORCE)
set(AVIF_CODEC_AOM OFF CACHE STRING "" FORCE)
set(AVIF_LIBYUV OFF CACHE STRING "" FORCE)
set(AVIF_LIBSHARPYUV OFF CACHE STRING "" FORCE)
set(AVIF_JPEG OFF CACHE STRING "" FORCE)
set(AVIF_ZLIBPNG OFF CACHE STRING "" FORCE)
set(AVIF_BUILD_APPS OFF CACHE BOOL "" FORCE)
set(AVIF_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(AVIF_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    libavif
    GIT_REPOSITORY https://github.com/AOMediaCodec/libavif.git
    # F-18: SHA commitu = tag v1.4.2 (niezmienne). GIT_SHALLOW FALSE — jw.
    GIT_TAG c5240fc79fe5c2407e10afd35f5505ef6333ea49
    GIT_SHALLOW FALSE
)
FetchContent_MakeAvailable(libavif)

add_library(lewicowyt_avif SHARED avif_jni.c)
target_include_directories(lewicowyt_avif PRIVATE ${libavif_SOURCE_DIR}/include)
target_link_libraries(lewicowyt_avif avif jnigraphics log)
# 16 KB page size (Android 15+): wyrównaj segmenty LOAD do 16 KB
target_link_options(lewicowyt_avif PRIVATE "-Wl,-z,max-page-size=16384")

# ---------------------------------------------------------------------------------------------------
# libjxl: DEKODER JPEG XL (decode-only) + własne JNI z obsługą PROGRESYWNEGO dekodowania
#   liblewicowyt_jxl.so — nasze JNI nad JxlDecoder (pełny dekodek + kolejne przebiegi progresywne)
# Budujemy TYLKO dekoder, statycznie, bez narzędzi/testów/benchmarków/enkodera/transkodowania JPEG.
# Zależności (highway, brotli, skcms) bierzemy z submodułów libjxl — tylko te trzy (NIE testdata ~setki MB).
# ---------------------------------------------------------------------------------------------------
set(JPEGXL_STATIC OFF CACHE BOOL "" FORCE)            # linkujemy statyczne targety do naszego .so, ale nie w pełni-static binarkę
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(JPEGXL_ENABLE_TOOLS OFF CACHE BOOL "" FORCE)
set(JPEGXL_ENABLE_VIEWERS OFF CACHE BOOL "" FORCE)
set(JPEGXL_ENABLE_EXAMPLES OFF CACHE BOOL "" FORCE)
set(JPEGXL_ENABLE_BENCHMARK OFF CACHE BOOL "" FORCE)
set(JPEGXL_ENABLE_MANPAGES OFF CACHE BOOL "" FORCE)
set(JPEGXL_ENABLE_DOXYGEN OFF CACHE BOOL "" FORCE)
set(JPEGXL_ENABLE_JNI OFF CACHE BOOL "" FORCE)        # piszemy WŁASNE JNI (jxl_jni.c)
set(JPEGXL_ENABLE_SJPEG OFF CACHE BOOL "" FORCE)
set(JPEGXL_ENABLE_OPENEXR OFF CACHE BOOL "" FORCE)
set(JPEGXL_ENABLE_TRANSCODE_JPEG OFF CACHE BOOL "" FORCE) # nie potrzebujemy rekonstrukcji JPEG
set(JPEGXL_ENABLE_FUZZERS OFF CACHE BOOL "" FORCE)
set(JPEGXL_ENABLE_DEVTOOLS OFF CACHE BOOL "" FORCE)
set(JPEGXL_ENABLE_PLUGINS OFF CACHE BOOL "" FORCE)
set(JPEGXL_ENABLE_SKCMS ON CACHE BOOL "" FORCE)       # zarządzanie kolorem (bundled skcms) — bez systemowego lcms
set(JPEGXL_BUNDLE_LIBPNG OFF CACHE BOOL "" FORCE)
set(JPEGXL_ENABLE_COVERAGE OFF CACHE BOOL "" FORCE)
set(JPEGXL_WARNINGS_AS_ERRORS OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

# libjxl/cmake/FindAtomics.cmake: próbny link testu atomics zawodzi pod NDK (m.in. -nostdlib++ oraz
# -Wl,--fatal-warnings w próbce), mimo że atomics są dostępne — przez to konfiguracja pada. Pomijamy
# detekcję (wyniki w cache = check_cxx_source_compiles ich nie uruchamia) i każemy linkować -latomic:
# w NDK to stub na arm64/x86_64, a realna biblioteka na armeabi-v7a (gdzie 64-bit atomics jej wymagają).
set(ATOMICS_LOCK_FREE_INSTRUCTIONS FALSE CACHE INTERNAL "lewicowyt: pomijamy test atomics libjxl")
set(ATOMICS_IN_LIBRARY TRUE CACHE INTERNAL "lewicowyt: linkuj -latomic zamiast testu")

FetchContent_Declare(
    libjxl
    GIT_REPOSITORY https://github.com/libjxl/libjxl.git
    # F-18/R-18: SHA commitu = tag v0.12.0 (niezmienne, odporne na przestawienie tagu). GIT_SHALLOW FALSE — jak reszta.
    GIT_TAG a7a9c787341cf703dede03c2009fa460cae5e5df
    GIT_SHALLOW FALSE
    # TYLKO potrzebne submoduły — bez third_party/testdata (~setki MB) i reszty
    GIT_SUBMODULES "third_party/highway;third_party/brotli;third_party/skcms"
)
FetchContent_MakeAvailable(libjxl)

# jxl_jni.c zostaje w C (JNI), a pusty jxl_cxx_link.cc sprawia, że CMake linkuje target linkerem C++
# (clang++) i wciąga libc++ — wymagane, bo jxl_dec-static to C++.
add_library(lewicowyt_jxl SHARED jxl_jni.c jxl_cxx_link.cc)
target_include_directories(lewicowyt_jxl PRIVATE
    ${libjxl_SOURCE_DIR}/lib/include
    ${libjxl_BINARY_DIR}/lib/include
)
# headers libjxl przy linkowaniu statycznym wymagają JXL_STATIC_DEFINE (inaczej dllimport/visibility)
target_compile_definitions(lewicowyt_jxl PRIVATE JXL_STATIC_DEFINE)
# libjxl: target `jxl` (dekoder + enkoder; enkoder używany tylko na ZAUFANYCH, już zdekodowanych pikselach — rekompresja/eksport,
# zastępuje enkoder z jxl-coder). Stary komentarz o jxl_dec: dekoder-only nie zawiera enkodera.
target_link_libraries(lewicowyt_jxl jxl jnigraphics log)
target_link_options(lewicowyt_jxl PRIVATE "-Wl,-z,max-page-size=16384")
