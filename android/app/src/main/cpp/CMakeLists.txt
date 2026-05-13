# Oak & Sparrow native build — wraps llama.cpp into liboaksparrow_llm.so
#
# Required: llama.cpp source must be cloned into ./llama.cpp/ first.
# The setup script does this:
#   bash android/setup-llama-cpp.sh
#
# The Gradle build invokes this CMakeLists via android.externalNativeBuild
# in android/app/build.gradle.kts.

cmake_minimum_required(VERSION 3.22.1)
project("oaksparrow_llm" LANGUAGES CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# ---------------------------------------------------------------------------
# Locate the llama.cpp source tree
# ---------------------------------------------------------------------------
set(LLAMA_CPP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp")
if(NOT EXISTS "${LLAMA_CPP_DIR}/CMakeLists.txt")
    message(FATAL_ERROR
        "\n"
        "  llama.cpp source not found at:\n"
        "    ${LLAMA_CPP_DIR}\n"
        "\n"
        "  Run from the repo root:\n"
        "    bash android/setup-llama-cpp.sh\n"
        "\n"
        "  See android/MODEL_SETUP.md for full instructions.\n")
endif()

# ---------------------------------------------------------------------------
# llama.cpp build options — tuned for Android phone
# ---------------------------------------------------------------------------
# Don't build llama.cpp's example binaries / tests / server — we only need
# the library targets.
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)

# Android NDK ships clang without libomp by default. Disabling OpenMP keeps
# the build self-contained.
set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)

# Disable accelerate / blas paths — not relevant on Android NDK.
set(GGML_BLAS            OFF CACHE BOOL "" FORCE)
set(GGML_ACCELERATE      OFF CACHE BOOL "" FORCE)

# Vulkan GPU support is opt-in. Off by default for the widest device support;
# bump to ON when targeting Pixel 8+ class GPUs and accept the larger .so.
set(GGML_VULKAN          OFF CACHE BOOL "" FORCE)

# ARM-side CPU optimizations. The NDK selects -march for us via ABI; these
# enable runtime detection paths inside ggml.
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_NATIVE      OFF CACHE BOOL "" FORCE)  # Android cross-compile
endif()

# ---------------------------------------------------------------------------
# Pull in llama.cpp as a subdirectory build
# ---------------------------------------------------------------------------
add_subdirectory(
    "${LLAMA_CPP_DIR}"
    "${CMAKE_CURRENT_BINARY_DIR}/llama.cpp_build"
    EXCLUDE_FROM_ALL)

# ---------------------------------------------------------------------------
# Our JNI shared library
# ---------------------------------------------------------------------------
add_library(oaksparrow_llm SHARED llama_jni.cpp)

target_include_directories(oaksparrow_llm PRIVATE
    "${LLAMA_CPP_DIR}/include"
    "${LLAMA_CPP_DIR}/ggml/include")

target_link_libraries(oaksparrow_llm PRIVATE
    llama
    android      # for __android_log_print
    log)

# Strip release builds for smaller APK
if(CMAKE_BUILD_TYPE STREQUAL "Release" OR CMAKE_BUILD_TYPE STREQUAL "MinSizeRel")
    target_link_options(oaksparrow_llm PRIVATE "-Wl,--strip-all")
endif()
