cmake_minimum_required(VERSION 3.22.1)
project(llamajni CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

option(LLAMAJNI_ENABLE_OPENCL "Build the OpenCL backend for Adreno GPUs" OFF)
option(LLAMAJNI_ENABLE_VULKAN "Build the Vulkan backend for Adreno and Mali GPUs" ON)

set(LLAMA_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../../native/llama.cpp)

# Everything we do not need. Each of these adds build time or binary size.
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD     ON CACHE BOOL "" FORCE)
set(MTMD_VIDEO           OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)
# common carries chat templates, grammar-constrained tool calling and parsing.
set(LLAMA_BUILD_COMMON   ON  CACHE BOOL "" FORCE)

set(GGML_OPENMP    OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)

if(LLAMAJNI_ENABLE_OPENCL)
    set(GGML_OPENCL ON CACHE BOOL "" FORCE)
endif()

if(LLAMAJNI_ENABLE_VULKAN)
    # Shaders are compiled on the CI host. The APK links the NDK Vulkan stub and
    # loads the device driver at runtime. If the host SDK is missing, keep a
    # working CPU build instead of failing configuration.
    find_program(LLAMAJNI_GLSLC glslc)
    if(NOT LLAMAJNI_GLSLC)
        message(WARNING "glslc not found; building llama.cpp without Vulkan")
        set(LLAMAJNI_ENABLE_VULKAN OFF)
    else()
        set(GGML_VULKAN ON CACHE BOOL "" FORCE)
        if(ANDROID)
            set(_llamajni_vulkan_lib
                "${ANDROID_NDK}/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/${CMAKE_LIBRARY_ARCHITECTURE}/libvulkan.so")
            if(EXISTS "${_llamajni_vulkan_lib}")
                set(Vulkan_LIBRARY "${_llamajni_vulkan_lib}" CACHE FILEPATH "" FORCE)
            endif()
        endif()
    endif()
endif()

add_subdirectory(${LLAMA_ROOT} ${CMAKE_CURRENT_BINARY_DIR}/llama.cpp)

add_library(llamajni SHARED llama_jni.cpp)

target_include_directories(llamajni PRIVATE
    ${LLAMA_ROOT}/include
    ${LLAMA_ROOT}/ggml/include
    ${LLAMA_ROOT}/common
)

find_library(ANDROID_LOG_LIB log)

target_link_libraries(llamajni PRIVATE llama llama-common mtmd ${ANDROID_LOG_LIB})
