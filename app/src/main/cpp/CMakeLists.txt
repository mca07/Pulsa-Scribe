# Offline-Transkription: whisper.cpp (MIT-Lizenz, Version fest per Submodul) als native Bibliothek.
cmake_minimum_required(VERSION 3.22.1)
project(pulsascribe_whisper C CXX)

set(CMAKE_CXX_STANDARD 17)
set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp)

file(READ "${WHISPER_DIR}/CMakeLists.txt" MAIN_CMAKE_CONTENT)
string(REGEX MATCH "project\\(\"whisper\\.cpp\" VERSION ([0-9]+\\.[0-9]+\\.[0-9]+)\\)" VERSION_MATCH "${MAIN_CMAKE_CONTENT}")
set(WHISPER_VERSION "${CMAKE_MATCH_1}")

set(SOURCE_FILES
    ${WHISPER_DIR}/src/whisper.cpp
    ${WHISPER_DIR}/src/aneforge/whisper-aneforge.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/scribe_jni.c)

find_library(LOG_LIB log)

# ggml aus dem Submodul (kein Netz beim Bauen nötig)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
include(FetchContent)
FetchContent_Declare(ggml SOURCE_DIR ${WHISPER_DIR}/ggml)
FetchContent_MakeAvailable(ggml)

function(build_library target_name)
    add_library(${target_name} SHARED ${SOURCE_FILES})
    target_compile_definitions(${target_name} PUBLIC GGML_USE_CPU)
    target_compile_definitions(${target_name} PRIVATE WHISPER_VERSION="${WHISPER_VERSION}")
    if (${target_name} STREQUAL "scribe_whisper_fp16")
        target_compile_options(${target_name} PRIVATE -march=armv8.2-a+fp16)
    endif ()
    target_compile_options(${target_name} PRIVATE -O3 -fvisibility=hidden -fvisibility-inlines-hidden -ffunction-sections -fdata-sections)
    target_link_options(${target_name} PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384)
    target_include_directories(${target_name} PRIVATE
        ${WHISPER_DIR} ${WHISPER_DIR}/src ${WHISPER_DIR}/include
        ${WHISPER_DIR}/ggml/include ${WHISPER_DIR}/ggml/src ${WHISPER_DIR}/ggml/src/ggml-cpu)
    target_link_libraries(${target_name} ${LOG_LIB} android ggml)
endfunction()

build_library("scribe_whisper")
if (${ANDROID_ABI} STREQUAL "arm64-v8a")
    build_library("scribe_whisper_fp16")
endif ()
