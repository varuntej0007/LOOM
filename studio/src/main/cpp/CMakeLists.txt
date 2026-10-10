# LOOM Studio — llama.cpp (GGUF chat models) for Android via JNI. CPU backend only:
# the iQOO's GPU paths (Vulkan / OpenCL) are not stable enough yet to be a default profile.
cmake_minimum_required(VERSION 3.22.1)
project(loomllama C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
# Static llama/ggml go into one shared JNI library: they must be position independent.
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp)
if (NOT EXISTS ${LLAMA_DIR}/CMakeLists.txt)
    message(FATAL_ERROR "llama.cpp sources missing at ${LLAMA_DIR}. build_studio.bat fetches them "
                        "(git submodule update --init studio/src/main/cpp/llama.cpp).")
endif()

# Static libllama inside one JNI library; nothing downloaded at build time.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
if (ANDROID_ABI STREQUAL "arm64-v8a")
    # dotprod + fp16 arithmetic: present on every Snapdragon since the 855.
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
endif()

add_subdirectory(${LLAMA_DIR} llama-build EXCLUDE_FROM_ALL)

add_library(loomllama SHARED llama_jni.cpp)
target_link_libraries(loomllama PRIVATE llama)
if (ANDROID)
    target_link_libraries(loomllama PRIVATE log android)
endif()
