// Celowo pusty plik C++. Jest w źródłach targetu liblewicowyt_jxl TYLKO po to, by CMake wybrał linker
// C++ (clang++) i wciągnął libc++ — bo linkujemy statyczny dekoder libjxl (C++). Samo jxl_jni.c zostaje
// w C (z JNI o linkowaniu C). Prościej i pewniej niż set_target_properties(LINKER_LANGUAGE CXX), które na
// targecie z samym .c generowało pustą regułę linku (cd . && cd .).
