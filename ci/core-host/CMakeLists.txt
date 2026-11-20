# Host build of the engine core's pure-policy and I/O layers, for CI on Linux, Windows and macOS.
#
# The Android app builds app/src/main/cpp (NDK, with llama.cpp). This project builds only what needs no
# llama.cpp: configuration validation, expert-layout discovery, tensor-name matching, the n-gram draft,
# the cache brake, the file reader and platform I/O, the mapping release and the metrics sink, together
# with the tests that exercise them. It proves those compile and behave the same on every OS the
# upstream engine supports; it does not build or test the Android app.
cmake_minimum_required(VERSION 3.21)
project(bmoe_core_host VERSION 0.39.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
if(NOT CMAKE_BUILD_TYPE AND NOT CMAKE_CONFIGURATION_TYPES)
    set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
endif()

set(CORE ${CMAKE_CURRENT_LIST_DIR}/../../app/src/main/cpp/core)

add_library(bmoe_core_host STATIC
    ${CORE}/src/config.cpp
    ${CORE}/src/log.cpp
    ${CORE}/src/moe/expert_layout.cpp
    ${CORE}/src/moe/tensor_names.cpp
    ${CORE}/src/engine/ngram_draft.cpp
    ${CORE}/src/engine/cache_brake.cpp
    ${CORE}/src/io/file_reader.cpp
    ${CORE}/src/io/platform_io.cpp
    ${CORE}/src/io/mapping_release.cpp
    ${CORE}/src/metrics/csv_metrics_sink.cpp
    ${CORE}/src/metrics/memory_snapshot.cpp
)
target_include_directories(bmoe_core_host PUBLIC
    ${CORE}/include ${CORE}/src ${CORE}/src/engine ${CORE}/src/moe ${CORE}/src/io ${CORE}/src/metrics)
target_compile_definitions(bmoe_core_host PUBLIC BMOE_VERSION="${PROJECT_VERSION}")
# As upstream's core/CMakeLists.txt links them: the engine starts threads, and on Windows the dense residency
# sensor calls QueryWorkingSetEx, which older SDKs only resolve through the psapi import library.
find_package(Threads REQUIRED)
target_link_libraries(bmoe_core_host PUBLIC Threads::Threads)
if(WIN32)
    target_link_libraries(bmoe_core_host PUBLIC psapi)
endif()
if(MSVC)
    target_compile_options(bmoe_core_host PUBLIC /utf-8 /EHsc /permissive-)
    target_compile_definitions(bmoe_core_host PUBLIC _CRT_SECURE_NO_WARNINGS NOMINMAX)
endif()

enable_testing()
foreach(t expert_layout_test tensor_names_test ngram_test cache_brake_test stall_union_test
          file_reader_test mapping_release_test csv_memory_test csv_summary_test)
    add_executable(${t} ${CORE}/tests/${t}.cpp)
    target_link_libraries(${t} PRIVATE bmoe_core_host)
    add_test(NAME ${t} COMMAND ${t})
endforeach()
