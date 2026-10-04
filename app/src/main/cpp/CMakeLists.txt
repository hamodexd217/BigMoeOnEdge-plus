cmake_minimum_required(VERSION 3.22.1)

project("onedge-engine" VERSION 0.28.0 LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# ---- llama.cpp build options (forced: this project owns them) ---------------------------------
# Static everything: one libonedge-engine.so, no extra libllama.so/libggml*.so to package.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

# The normal app build is CPU-only. A Hexagon-enabled native build is performed by
# scripts/build-hexagon-onedge-engine.sh inside the upstream Snapdragon toolchain image.
# That build switches the vendored ggml backend on before llama.cpp is configured.
option(BMOE_ENABLE_HEXAGON "Enable the optional Hexagon/NPU prefill path" OFF)
if(BMOE_ENABLE_HEXAGON)
    if(NOT ANDROID OR NOT CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a")
        message(FATAL_ERROR "BMOE_ENABLE_HEXAGON requires an Android arm64-v8a build")
    endif()
    if(NOT DEFINED HEXAGON_SDK_ROOT OR NOT IS_DIRECTORY "${HEXAGON_SDK_ROOT}")
        message(FATAL_ERROR "BMOE_ENABLE_HEXAGON requires HEXAGON_SDK_ROOT from the Snapdragon toolchain")
    endif()
    set(GGML_HEXAGON ON CACHE BOOL "" FORCE)
endif()
# bmoe_core needs llama-common for chat templates + reasoning parsing.
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
# Offline-safe: no OpenSSL, and above all no embedded Web UI (would fetch a prebuilt UI at build time).
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_UI OFF CACHE BOOL "" FORCE)
set(LLAMA_USE_PREBUILT_UI OFF CACHE BOOL "" FORCE)
# ggml's own thread pool is used (no libomp to package). The engine drives compute through
# llama.cpp's public eval callback, which does not depend on OpenMP.
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
# Would FetchContent-download KleidiAI at configure time; the build must work offline.
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)

# Multimodal (images/video frames) through llama.cpp's mtmd library + patches/0003. llama.cpp's own
# LLAMA_BUILD_MTMD hook builds libmtmd without the rest of tools/. Turn it off with
#   ./gradlew assembleDebug -PbmoeMultimodal=false
# if it ever breaks the native build; the app then runs text-only (the UI detects this at runtime).
option(BMOE_ENABLE_MULTIMODAL "Build libmtmd and the image path of bmoe::Session" ON)
if(BMOE_ENABLE_MULTIMODAL)
    set(LLAMA_BUILD_MTMD ON CACHE BOOL "" FORCE)
    set(MTMD_VIDEO OFF CACHE BOOL "" FORCE) # ffmpeg-based video decoding; frames are extracted in Kotlin instead
endif()
set(LLAMA_LLGUIDANCE OFF CACHE BOOL "" FORCE)
set(BMOE_BUILD_TESTS OFF CACHE BOOL "" FORCE)

# CPU features for arm64. Conservative default that every phone since ~2018 runs. Newer SoCs
# (Snapdragon 8 Gen 1+, Dimensity 9000+, Tensor G3+) also have i8mm; to use it build with
#   pass -DBMOE_ARM_ARCH=armv8.2-a+dotprod+fp16+i8mm (externalNativeBuild.cmake.arguments) or edit BMOE_ARM_ARCH to "armv8.2-a+dotprod+fp16+i8mm".
set(BMOE_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "-march value for arm64-v8a")
if(ANDROID AND CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_ARM_ARCH "${BMOE_ARM_ARCH}" CACHE STRING "" FORCE)
endif()

# ---- llama.cpp (vendored snapshot) -------------------------------------------------------------
# third_party/llama.cpp is upstream ggml-org/llama.cpp at 965f897 (2026-09-26, the commit BigMoeOnEdge 0.28.0
# pins; carries qwen4exp), pruned, plus patches/0002 (expert-ready hook). core/CMakeLists.txt detects the hook
# in ggml-cpu.h and defines BMOE_HAVE_EXPERT_READY_HOOK.
if(NOT EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/third_party/llama.cpp/CMakeLists.txt")
    message(FATAL_ERROR "third_party/llama.cpp is missing. See README.md (Native build).")
endif()
add_subdirectory(third_party/llama.cpp llama_build EXCLUDE_FROM_ALL)
set(BMOE_HAVE_LLAMA ON)

# ---- BigMoeOnEdge core (upstream 0.28.0 + local patches) --------------------------------------
if(NOT EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/core/CMakeLists.txt")
    message(FATAL_ERROR "core/ is missing. See README.md (Native build).")
endif()
add_subdirectory(core)

# The Android app stays CPU-only by default. The Hexagon build recipe enables GGML_HEXAGON
# before adding llama.cpp, which makes the HTP backend part of this native engine.
set(BMOE_HEXAGON_ENABLED OFF)
if(BMOE_ENABLE_HEXAGON)
    if(NOT GGML_HEXAGON)
        message(FATAL_ERROR "BMOE_ENABLE_HEXAGON was requested but GGML_HEXAGON did not configure")
    endif()
    set(BMOE_HEXAGON_ENABLED ON)
endif()

# ---- JNI library --------------------------------------------------------------------------------
add_library(onedge-engine SHARED native-bridge.cpp)

if(BMOE_HEXAGON_ENABLED)
    target_compile_definitions(bmoe_core PUBLIC BMOE_HAVE_HEXAGON=1)
    target_compile_definitions(onedge-engine PRIVATE BMOE_HAVE_HEXAGON=1)
endif()

find_library(log-lib log)

target_link_libraries(onedge-engine PRIVATE bmoe_core ${log-lib})

# 16 KiB page-size compatible ELF segments (required by Android 15+ devices with 16K pages).
target_link_options(onedge-engine PRIVATE "-Wl,-z,max-page-size=16384")
