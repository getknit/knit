# The translation spike's native library (ADR 2026-10.t2h6): slimt and CLD2 behind a small core. Built by the
# `:translate-native` Gradle module for Android (libknittranslate.so), or directly with CMake on a desktop for the
# bench (knit-translate-bench). Sources come from scripts/translate-native.sh; none of them is committed.
cmake_minimum_required(VERSION 3.22)
project(knit_translate C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(THIRD_PARTY ${CMAKE_CURRENT_SOURCE_DIR}/third_party)
if(NOT EXISTS ${THIRD_PARTY}/slimt/CMakeLists.txt OR NOT EXISTS ${THIRD_PARTY}/cld2/internal/compact_lang_det.cc)
  message(FATAL_ERROR "translate-native: run scripts/translate-native.sh first")
endif()

# Strip the build machine's paths from what the compiler embeds, and let the linker drop what the JNI never calls.
add_compile_options(-ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=. -ffunction-sections -fdata-sections)

# slimt, as a static library only, with its own SentencePiece and ruy.
set(BUILD_SHARED OFF CACHE BOOL "" FORCE)
set(BUILD_STATIC ON CACHE BOOL "" FORCE)
set(USE_BUILTIN_SENTENCEPIECE ON CACHE BOOL "" FORCE)
set(SPM_ENABLE_SHARED OFF CACHE BOOL "" FORCE)
set(SPM_ENABLE_TCMALLOC OFF CACHE BOOL "" FORCE)
set(RUY_MINIMAL_BUILD ON CACHE BOOL "" FORCE)
if(ANDROID)
  # slimt's NEON paths use A64-only intrinsics; armeabi-v7a runs ruy's own kernels.
  if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(USE_NEON ON CACHE BOOL "" FORCE)
  endif()
elseif(CMAKE_SYSTEM_PROCESSOR MATCHES "x86_64|AMD64" AND NOT KNIT_GENERIC)
  set(USE_AVX2 ON CACHE BOOL "" FORCE)
  add_compile_options(-march=x86-64-v3)
endif()
add_subdirectory(${THIRD_PARTY}/slimt ${CMAKE_BINARY_DIR}/slimt EXCLUDE_FROM_ALL)
# slimt names its include roots by CMAKE_SOURCE_DIR / CMAKE_BINARY_DIR, which are this project's here.
target_include_directories(slimt-static PUBLIC ${THIRD_PARTY}/slimt ${CMAKE_BINARY_DIR}/slimt)
target_compile_options(slimt-static PRIVATE -Wno-error)

# CLD2 with the small "chrome" tables, the set its own compile_libs.sh builds first.
set(CLD2 ${THIRD_PARTY}/cld2/internal)
add_library(cld2 STATIC
  ${CLD2}/cldutil.cc ${CLD2}/cldutil_shared.cc ${CLD2}/compact_lang_det.cc ${CLD2}/compact_lang_det_hint_code.cc
  ${CLD2}/compact_lang_det_impl.cc ${CLD2}/debug.cc ${CLD2}/fixunicodevalue.cc
  ${CLD2}/generated_entities.cc ${CLD2}/generated_language.cc ${CLD2}/generated_ulscript.cc
  ${CLD2}/getonescriptspan.cc ${CLD2}/lang_script.cc ${CLD2}/offsetmap.cc ${CLD2}/scoreonescriptspan.cc
  ${CLD2}/tote.cc ${CLD2}/utf8statetable.cc
  ${CLD2}/cld_generated_cjk_uni_prop_80.cc ${CLD2}/cld2_generated_cjk_compatible.cc
  ${CLD2}/cld_generated_cjk_delta_bi_4.cc ${CLD2}/generated_distinct_bi_0.cc
  ${CLD2}/cld2_generated_quadchrome_2.cc ${CLD2}/cld2_generated_deltaoctachrome.cc
  ${CLD2}/cld2_generated_distinctoctachrome.cc ${CLD2}/cld_generated_score_quad_octa_2.cc)
target_include_directories(cld2 PUBLIC ${THIRD_PARTY}/cld2)
# 2015 code: its warnings are not ours to fix, and C++20 drops the register keyword it still uses.
target_compile_options(cld2 PRIVATE -w)
set_target_properties(cld2 PROPERTIES CXX_STANDARD 98)

add_library(knit_translate_core STATIC src/main/cpp/knit_translate.cc)
target_link_libraries(knit_translate_core PUBLIC slimt-static cld2)

if(ANDROID)
  add_library(knittranslate SHARED src/main/cpp/knit_translate_jni.cc)
  target_link_libraries(knittranslate PRIVATE knit_translate_core)
  # 16 KB pages (Android 15+ devices), no build id, nothing the app does not call, and no symbols: :app packages its
  # native libraries as they come (packaging.jniLibs keepDebugSymbols), and unstripped this is 31 MB, stripped 4.
  target_link_options(knittranslate PRIVATE -Wl,-z,max-page-size=16384 -Wl,--build-id=none -Wl,--gc-sections
                      -Wl,--strip-all)
else()
  add_executable(knit-translate-bench bench/desktop_bench.cc)
  target_link_libraries(knit-translate-bench PRIVATE knit_translate_core)
endif()
