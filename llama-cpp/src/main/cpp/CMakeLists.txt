cmake_minimum_required(VERSION 3.22.1)
project(llamajni C CXX)

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
        if(ANDROID AND NOT Vulkan_LIBRARY)
            # Link the NDK stub; the device driver is loaded at runtime.
            find_library(_llamajni_vulkan_lib vulkan)
            if(_llamajni_vulkan_lib)
                set(Vulkan_LIBRARY "${_llamajni_vulkan_lib}" CACHE FILEPATH "" FORCE)
            endif()
        endif()
    endif()
endif()

add_subdirectory(${LLAMA_ROOT} ${CMAKE_CURRENT_BINARY_DIR}/llama.cpp)

if(TARGET ggml-vulkan)
    # The NDK ships vulkan.h but not the C++ bindings (vulkan.hpp). Stage the
    # host SDK's Vulkan headers into the build dir: they are platform-neutral
    # declarations, and copying only that subdirectory keeps the Android
    # sysroot headers first for everything else.
    find_path(LLAMAJNI_VULKAN_HPP_DIR vulkan/vulkan.hpp
        PATHS /usr/local/include /usr/include
        NO_CMAKE_FIND_ROOT_PATH)
    if(LLAMAJNI_VULKAN_HPP_DIR)
        set(_llamajni_vk_stage "${CMAKE_CURRENT_BINARY_DIR}/host-vulkan-include")
        # vulkan_core.h pulls in <vk_video/...>, so both directories are needed.
        foreach(_llamajni_vk_dir vulkan vk_video)
            if(EXISTS "${LLAMAJNI_VULKAN_HPP_DIR}/${_llamajni_vk_dir}")
                file(COPY "${LLAMAJNI_VULKAN_HPP_DIR}/${_llamajni_vk_dir}"
                    DESTINATION "${_llamajni_vk_stage}")
            endif()
        endforeach()
        # ggml-vulkan also needs the SPIR-V headers, which only exist on the host.
        find_path(LLAMAJNI_SPIRV_DIR spirv/unified1/spirv.hpp
            PATHS /usr/include /usr/local/include
            NO_CMAKE_FIND_ROOT_PATH)
        if(LLAMAJNI_SPIRV_DIR)
            file(COPY "${LLAMAJNI_SPIRV_DIR}/spirv" DESTINATION "${_llamajni_vk_stage}")
        endif()
        target_include_directories(ggml-vulkan PRIVATE "${_llamajni_vk_stage}")
    else()
        message(WARNING "vulkan/vulkan.hpp not found; Vulkan backend will not compile")
    endif()
endif()

add_library(llamajni SHARED llama_jni.cpp vulkan_android_shim.c)

target_include_directories(llamajni PRIVATE
    ${LLAMA_ROOT}/include
    ${LLAMA_ROOT}/ggml/include
    ${LLAMA_ROOT}/common
)

find_library(ANDROID_LOG_LIB log)

target_link_libraries(llamajni PRIVATE llama llama-common mtmd ${ANDROID_LOG_LIB})
