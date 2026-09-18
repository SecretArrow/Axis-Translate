# =============================================================================
# Axis Translate Desktop — pure C application (Windows + Linux)
#
#   - App layer: C11 (src/*.c)
#   - Inference: llama.cpp pinned to v0.4.1 (FetchContent, same pin as the
#     Android app) — a C++ library consumed through its C API, exactly like
#     the Android app consumes it through JNI.
#   - UI: Nuklear 4.13.3 (vendored single header, MIT) + SDL2
#   - Downloads: WinHTTP (Windows) / libcurl (Linux)
#   - JSON: jsmn (vendored single header, MIT)
#
# Ubuntu deps:  sudo apt install -y libsdl2-dev libcurl4-openssl-dev
# Windows:      SDL2 is fetched and built from source (release-2.32.8);
#               WinHTTP ships with the OS.
# =============================================================================

cmake_minimum_required(VERSION 3.16)
project(axis_translate_desktop VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_C_EXTENSIONS OFF)

if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()

# ---------------------------------------------------------------------------
# Dependencies
# ---------------------------------------------------------------------------
include(FetchContent)

# ---- SDL2 ------------------------------------------------------------------
if(WIN32)
    set(AXIS_SDL2_TAG "release-2.32.8" CACHE STRING "SDL2 release tag")
    FetchContent_Declare(
        sdl2
        GIT_REPOSITORY https://github.com/libsdl-org/SDL
        GIT_TAG ${AXIS_SDL2_TAG}
        GIT_SHALLOW TRUE
    )
    set(SDL_SHARED OFF CACHE BOOL "" FORCE)
    set(SDL_STATIC ON CACHE BOOL "" FORCE)
    set(SDL_TEST OFF CACHE BOOL "" FORCE)
    FetchContent_MakeAvailable(sdl2)
    set(AXIS_SDL2_TARGET SDL2::SDL2-static)
else()
    find_package(SDL2 REQUIRED)
    if(TARGET SDL2::SDL2)
        set(AXIS_SDL2_TARGET SDL2::SDL2)
    elseif(TARGET SDL2::SDL2-static)
        set(AXIS_SDL2_TARGET SDL2::SDL2-static)
    else()
        set(AXIS_SDL2_TARGET SDL2::SDL2)
    endif()
endif()

# ---- llama.cpp (inference runtime) ----------------------------------------
set(AXIS_LLAMA_TAG "v0.4.1" CACHE STRING "llama.cpp release tag (must match android/ pin)")

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
set(GGML_BLAS OFF CACHE BOOL "" FORCE)
set(GGML_RPC OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    llama
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp
    GIT_TAG ${AXIS_LLAMA_TAG}
    GIT_SHALLOW TRUE
    GIT_PROGRESS TRUE
)
FetchContent_MakeAvailable(llama)

# ---- libcurl (Linux downloads) ----------------------------------------------
if(NOT WIN32)
    find_package(CURL REQUIRED)
endif()

# ---------------------------------------------------------------------------
# Application (pure C)
# ---------------------------------------------------------------------------
set(AXIS_APP_SOURCES
    src/main.c
    src/app.c
    src/backend.c
    src/theme.c
    src/ui_translate.c
    src/ui_models.c
    src/ui_history.c
    src/ui_settings.c
    src/ui_browser.c
    src/engine.c
    src/models.c
    src/download_curl.c      # no-op on Windows
    src/download_winhttp.c   # no-op on Linux
    src/manifest.c
    src/chunker.c
    src/prompt.c
    src/langs.c
    src/store.c
    src/fs.c
    src/sha256.c
    src/str.c
)
if(WIN32)
    list(REMOVE_ITEM AXIS_APP_SOURCES src/download_curl.c)
else()
    list(REMOVE_ITEM AXIS_APP_SOURCES src/download_winhttp.c)
endif()

add_executable(axis-translate ${AXIS_APP_SOURCES})

# Link with the C++ driver so libstdc++/libc++ (needed by llama.cpp) is pulled
# in — all application sources stay plain C.
set_target_properties(axis-translate PROPERTIES LINKER_LANGUAGE CXX)

target_include_directories(axis-translate PRIVATE src)

target_link_libraries(axis-translate PRIVATE
    llama
    ${AXIS_SDL2_TARGET}
)
if(WIN32)
    target_link_libraries(axis-translate PRIVATE winhttp shlwapi shell32 user32 gdi32)
else()
    target_link_libraries(axis-translate PRIVATE CURL::libcurl)
    target_compile_options(axis-translate PRIVATE -Wall -Wextra -Wno-unused-parameter)
endif()

if(MSVC)
    target_compile_options(axis-translate PRIVATE /W4 /utf-8)
endif()

# ---------------------------------------------------------------------------
# Tests (pure C logic — no GUI, no llama)
# ---------------------------------------------------------------------------
enable_testing()

add_executable(axis_tests
    tests/test_main.c
    src/str.c
    src/fs.c
    src/sha256.c
    src/langs.c
    src/chunker.c
    src/prompt.c
    src/manifest.c
    src/models.c
    src/store.c
    src/platform_win.c
    src/platform_posix.c
)
target_include_directories(axis_tests PRIVATE src)
if(WIN32)
    target_link_libraries(axis_tests PRIVATE shell32 user32)
    if(MSVC)
        target_compile_options(axis_tests PRIVATE /utf-8)
    endif()
else()
    target_compile_options(axis_tests PRIVATE -Wall -Wextra)
    # platform_posix needs pthreads
    target_compile_options(axis_tests PRIVATE -pthread)
    target_link_options(axis_tests PRIVATE -pthread)
endif()

add_test(NAME axis-unit COMMAND axis_tests)
