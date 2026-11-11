# dav1d 1.5.4 — dekoder AV1 (zastępuje libaom jako dekoder dla libavif; szybszy, mniejszy, NEON na ARM).
#
# NOTA DLA AUDYTORÓW: upstream buduje dav1d Mesonem, którego środowisko budowy aplikacji nie ma (a wymaganie go
# wprowadzałoby kolejne narzędzie do łańcucha dostaw). Ten plik odtwarza konfigurację z meson.build/src/meson.build
# (tag 1.5.4, commit przypięty w CMakeLists.txt) w czystym CMake:
#   - bitdepths 8 i 16 (tmpl-źródła kompilowane dwukrotnie z -DBITDEPTH=N), tylko biblioteka (bez narzędzi/testów),
#   - ARM (arm64-v8a, armeabi-v7a): asembler NEON przez clang z NDK (pliki .S); rozszerzenia dotprod/i8mm/sve/sve2
#     WYŁĄCZONE (HAVE_*=0) — dostępne na nowszych CPU, ale wymagają dyrektyw asemblera, których nie wymuszamy,
#   - x86_64: sam C (bez NASM; x86_64 używane głównie w emulatorze), brak asm => wolniej, ale poprawnie,
#   - CONFIG_LOG=0 (brak logów dekodera), TRIM_DSP_FUNCTIONS=1 (mniejszy plik; funkcje DSP dobrane do trybu budowy),
#   - wątki: pthread (Android ma w libc); dekoder w aplikacji i tak działa z maxThreads=1.
# Dane wejściowe są NIEUFNE: dav1d jest fuzzowany w OSS-Fuzz, a limity rozmiaru wymuszamy w avif_jni.c (libavif).
set(_D1 "${dav1d_SOURCE_DIR}")
set(_D1_GEN "${CMAKE_CURRENT_BINARY_DIR}/dav1d-gen")
file(MAKE_DIRECTORY "${_D1_GEN}")

if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(_D1_AARCH64 1)
    set(_D1_ARM 0)
    set(_D1_ASM 1)
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(_D1_AARCH64 0)
    set(_D1_ARM 1)
    set(_D1_ASM 1)
else()
    set(_D1_AARCH64 0)
    set(_D1_ARM 0)
    set(_D1_ASM 0)
endif()
if(ANDROID_ABI STREQUAL "x86_64")
    set(_D1_X86_64 1)
else()
    set(_D1_X86_64 0)
endif()

# config.h (odpowiednik cdata z meson.build)
file(WRITE "${_D1_GEN}/config.h" "
#define CONFIG_8BPC 1
#define CONFIG_16BPC 1
#define CONFIG_LOG 0
#define TRIM_DSP_FUNCTIONS 1
#define HAVE_ASM ${_D1_ASM}
#define ARCH_AARCH64 ${_D1_AARCH64}
#define ARCH_ARM ${_D1_ARM}
#define ARCH_X86 ${_D1_X86_64}
#define ARCH_X86_64 ${_D1_X86_64}
#define ARCH_X86_32 0
#define ARCH_PPC64LE 0
#define ARCH_RISCV 0
#define ARCH_RV32 0
#define ARCH_RV64 0
#define ARCH_LOONGARCH 0
#define ARCH_LOONGARCH32 0
#define ARCH_LOONGARCH64 0
#define ENDIANNESS_BIG 0
#define HAVE_AS_FUNC 0
#define HAVE_AS_ARCH_DIRECTIVE 0
#define HAVE_AS_ARCHEXT_DOTPROD_DIRECTIVE 0
#define HAVE_AS_ARCHEXT_I8MM_DIRECTIVE 0
#define HAVE_AS_ARCHEXT_SVE_DIRECTIVE 0
#define HAVE_AS_ARCHEXT_SVE2_DIRECTIVE 0
#define HAVE_DOTPROD 0
#define HAVE_I8MM 0
#define HAVE_SVE 0
#define HAVE_SVE2 0
#define HAVE_CLOCK_GETTIME 1
#define HAVE_POSIX_MEMALIGN 1
#define HAVE_MEMALIGN 1
#define HAVE_ALIGNED_ALLOC 1
#define HAVE_SIGACTION 0
#define HAVE_DLSYM 0
#define HAVE_UNISTD_H 1
#define HAVE_IO_H 0
#define HAVE_SYS_TYPES_H 1
#define HAVE_PTHREAD_NP_H 0
#define HAVE_GETAUXVAL 1
#define HAVE_ELF_AUX_INFO 0
#define HAVE_PTHREAD_GETAFFINITY_NP 0
#define HAVE_PTHREAD_SETAFFINITY_NP 0
#define HAVE_PTHREAD_SETNAME_NP 0
#define HAVE_PTHREAD_SET_NAME_NP 0
#define HAVE_C11_GENERIC 1
#define STACK_ALIGNMENT 16
#define _GNU_SOURCE 1
")
# vcs_version.h (tag przypięty w CMakeLists.txt)
file(WRITE "${_D1_GEN}/vcs_version.h" "/* auto-generated */\n#define DAV1D_VERSION \"1.5.4\"\n")

set(_D1_SRC
    cdf.c cpu.c ctx.c data.c decode.c dequant_tables.c getbits.c intra_edge.c itx_1d.c lf_mask.c lib.c log.c mem.c
    msac.c obu.c pal.c picture.c qm.c ref.c refmvs.c scan.c tables.c thread_task.c warpmv.c wedge.c)
set(_D1_TMPL
    cdef_apply_tmpl.c cdef_tmpl.c fg_apply_tmpl.c filmgrain_tmpl.c ipred_prepare_tmpl.c ipred_tmpl.c itx_tmpl.c
    lf_apply_tmpl.c loopfilter_tmpl.c looprestoration_tmpl.c lr_apply_tmpl.c mc_tmpl.c recon_tmpl.c)

set(_D1_ALL "")
foreach(f ${_D1_SRC})
    list(APPEND _D1_ALL "${_D1}/src/${f}")
endforeach()

if(_D1_AARCH64)
    list(APPEND _D1_ALL "${_D1}/src/arm/cpu.c")
    foreach(f itx looprestoration_common msac refmvs cdef filmgrain ipred loopfilter looprestoration mc mc_dotprod
              cdef16 filmgrain16 ipred16 itx16 loopfilter16 looprestoration16 mc16 mc16_sve)
        list(APPEND _D1_ALL "${_D1}/src/arm/64/${f}.S")
    endforeach()
elseif(_D1_ARM)
    list(APPEND _D1_ALL "${_D1}/src/arm/cpu.c")
    foreach(f itx looprestoration_common msac refmvs cdef filmgrain ipred loopfilter looprestoration mc
              cdef16 filmgrain16 ipred16 itx16 loopfilter16 looprestoration16 mc16)
        list(APPEND _D1_ALL "${_D1}/src/arm/32/${f}.S")
    endforeach()
elseif(_D1_X86_64)
    # bez NASM: tylko C — pomijamy x86/cpu.c (wymaga asemblera cpuid), dav1d użyje wersji C
endif()

add_library(dav1d_bpc8 OBJECT)
add_library(dav1d_bpc16 OBJECT)
foreach(bpc 8 16)
    foreach(f ${_D1_TMPL})
        target_sources(dav1d_bpc${bpc} PRIVATE "${_D1}/src/${f}")
    endforeach()
    target_compile_definitions(dav1d_bpc${bpc} PRIVATE BITDEPTH=${bpc})
endforeach()

add_library(dav1d STATIC ${_D1_ALL} $<TARGET_OBJECTS:dav1d_bpc8> $<TARGET_OBJECTS:dav1d_bpc16>)
foreach(t dav1d dav1d_bpc8 dav1d_bpc16)
    target_include_directories(${t} PRIVATE "${_D1_GEN}" "${_D1}" "${_D1}/include" "${_D1}/include/dav1d" "${_D1}/src")
    target_compile_options(${t} PRIVATE -std=c11 -w)
    set_target_properties(${t} PROPERTIES POSITION_INDEPENDENT_CODE ON)
endforeach()
target_include_directories(dav1d PUBLIC "${_D1}/include")
target_link_libraries(dav1d PUBLIC Threads::Threads)
# libavif oczekuje celu dav1d::dav1d i sam dopisuje do niego linkowanie (dlsym), więc nie może to być ALIAS.
# Nazwa z "::" jest dozwolona dla celu IMPORTED — cienki INTERFACE przekazujący naszą bibliotekę statyczną i nagłówki.
add_library(dav1d::dav1d INTERFACE IMPORTED GLOBAL)
set_target_properties(dav1d::dav1d PROPERTIES INTERFACE_INCLUDE_DIRECTORIES "${_D1}/include")
target_link_libraries(dav1d::dav1d INTERFACE dav1d)
