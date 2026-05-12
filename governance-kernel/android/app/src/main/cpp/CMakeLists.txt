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

# Per-ABI tuning.
if(ANDROID_ABI STREQUAL "arm64-v8a")
    # Cross-compile — let the NDK pick -march for the target.
    set(GGML_NATIVE      OFF CACHE BOOL "" FORCE)
elseif(ANDROID_ABI STREQUAL "x86_64")
    # The Android emulator runs x86_64 code natively on the host CPU — no
    # translation layer. Enable AVX2 + FMA so ggml uses wide SIMD paths
    # instead of the generic SSE4.2 baseline the NDK targets by default.
    # This makes emulator inference competitive with real-phone performance.
    set(GGML_NATIVE      OFF CACHE BOOL "" FORCE)  # still cross-compile
    add_compile_options(-mavx2 -mfma -mf16c -mavx)
endif()

# ---------------------------------------------------------------------------
# Android/Bionic pthreads fix — pthreads is baked into libc, no separate
# -lpthread exists. Tell CMake's FindThreads that pthreads is already
# available so llama.cpp's cmake doesn't fail looking for the library.
# ---------------------------------------------------------------------------
set(CMAKE_THREAD_LIBS_INIT ""      CACHE STRING "" FORCE)
set(CMAKE_HAVE_THREADS_LIBRARY 1   CACHE BOOL   "" FORCE)
set(CMAKE_USE_WIN32_THREADS_INIT 0 CACHE BOOL   "" FORCE)
set(CMAKE_USE_PTHREADS_INIT 1      CACHE BOOL   "" FORCE)
set(THREADS_PREFER_PTHREAD_FLAG ON  CACHE BOOL   "" FORCE)

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
