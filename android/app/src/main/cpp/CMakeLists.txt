cmake_minimum_required(VERSION 3.22)
project(axis_engine LANGUAGES C CXX)

# ---------------------------------------------------------------------------
# Axis Translate native inference engine
#
# Links against llama.cpp (pinned release, fetched at configure time via
# FetchContent) and exposes a small JNI surface consumed by
# com.axis.translate.inference.LlamaEngine.
#
# The tag is fixed for reproducible CI builds; bump deliberately.
# ---------------------------------------------------------------------------
set(AXIS_LLAMA_TAG "v0.4.1" CACHE STRING "llama.cpp release tag to build against")

include(FetchContent)

# Static libs get folded into our single JNI shared library.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

# Only the CPU backend is built for Android; every other backend is disabled
# to keep native build times fast and the APK small.
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
set(GGML_BLAS OFF CACHE BOOL "" FORCE)
set(GGML_RPC OFF CACHE BOOL "" FORCE)

# No tests/examples/tools/server from the vendored tree.
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)

# llamafile sgemm kernels rely on FP16 NEON intrinsics that do not exist on
# 32-bit ARM (vld1q_f16 etc.), and are tuned for 64-bit targets. Disable the
# kernel for 32-bit ABIs; arm64-v8a and x86_64 keep it for GEMM performance.
if (ANDROID_ABI STREQUAL "armeabi-v7a" OR ANDROID_ABI STREQUAL "x86")
    set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
endif()

FetchContent_Declare(
    llama
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp
    GIT_TAG ${AXIS_LLAMA_TAG}
    GIT_SHALLOW TRUE
    GIT_PROGRESS TRUE
)
FetchContent_MakeAvailable(llama)

add_library(axis_engine SHARED
    axis_engine_jni.cpp
)

target_compile_features(axis_engine PRIVATE cxx_std_17)
target_compile_options(axis_engine PRIVATE -O2 -fvisibility=hidden -fexceptions -frtti)
set_target_properties(axis_engine PROPERTIES
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED ON
    CXX_EXTENSIONS OFF
)

find_library(log-lib log)
target_link_libraries(axis_engine
    PRIVATE
    llama
    ${log-lib}
)
