cmake_minimum_required(VERSION 3.25)

project(bedray_core LANGUAGES CXX)

# Windows x64 is the first target. The library is a plain shared object with a
# C ABI; it links no Vulkan loader and creates no Vulkan object of its own.
if(NOT WIN32)
    message(FATAL_ERROR "bedray_core currently supports Windows only")
endif()

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Vulkan headers only: the host supplies vkGetInstanceProcAddr/vkGetDeviceProcAddr.
set(BEDRAY_VULKAN_HEADERS "" CACHE PATH "Path to the Vulkan-Headers include directory")
if(NOT BEDRAY_VULKAN_HEADERS)
    if(DEFINED ENV{VULKAN_SDK} AND EXISTS "$ENV{VULKAN_SDK}/include/vulkan/vulkan_core.h")
        set(BEDRAY_VULKAN_HEADERS "$ENV{VULKAN_SDK}/include")
    elseif(EXISTS "C:/Dev/Sky/tools/sdk/Vulkan-Headers/include/vulkan/vulkan_core.h")
        set(BEDRAY_VULKAN_HEADERS "C:/Dev/Sky/tools/sdk/Vulkan-Headers/include")
    endif()
endif()
if(NOT EXISTS "${BEDRAY_VULKAN_HEADERS}/vulkan/vulkan_core.h")
    message(FATAL_ERROR "Vulkan headers not found; pass -DBEDRAY_VULKAN_HEADERS=<include dir>")
endif()

# Test targets are registered by subdirectories; enable discovery at the root
# so `ctest --test-dir build` finds them.
enable_testing()

add_library(bedray_core SHARED
    src/core.cpp
)

# History exposes a C API implemented by the core DLL. The registry remains a
# native library; the renderer frame path has not yet adopted its protocol.
add_subdirectory("${CMAKE_CURRENT_LIST_DIR}/../bedray_history"
                 "${CMAKE_CURRENT_BINARY_DIR}/bedray_history")
target_sources(bedray_core PRIVATE "${CMAKE_CURRENT_LIST_DIR}/../bedray_history/src/history_c.cpp")
target_link_libraries(bedray_core PRIVATE bedray_history)

# Compile the graph with the core; frame execution still needs integration.
# No second Vulkan loader or device is introduced.
option(BEDRAY_ENABLE_RENDER_GRAPH "Build the renderer graph with the native core" ON)
if(BEDRAY_ENABLE_RENDER_GRAPH)
    set(BEDRAY_GRAPH_VULKAN ON)
    add_subdirectory("${CMAKE_CURRENT_LIST_DIR}/../bedray_render_graph"
                     "${CMAKE_CURRENT_BINARY_DIR}/bedray_render_graph")
    target_link_libraries(bedray_core PRIVATE bedray_graph_vulkan)
    target_link_libraries(bedray_core PRIVATE bedray_graph_history)
endif()

target_include_directories(bedray_core
    PUBLIC  include
    PRIVATE "${BEDRAY_VULKAN_HEADERS}"
)

target_compile_definitions(bedray_core PRIVATE
    BEDRAY_CORE_BUILD
    WIN32_LEAN_AND_MEAN
    NOMINMAX
)

if(MSVC)
    target_compile_options(bedray_core PRIVATE /W4 /permissive- /EHsc /utf-8)
    # Static CRT: the DLL must load without a Visual C++ redistributable.
    set_property(TARGET bedray_core PROPERTY MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
endif()

set_target_properties(bedray_core PROPERTIES
    OUTPUT_NAME "bedray_core"
    PREFIX ""
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN ON
)

# ABI probe: prints the struct sizes the Java FFM layer must mirror.
add_executable(bedray_abi_probe tools/abi_probe.cpp)
target_link_libraries(bedray_abi_probe PRIVATE bedray_core)
target_include_directories(bedray_abi_probe PRIVATE include)
if(MSVC)
    set_property(TARGET bedray_abi_probe PROPERTY MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
endif()
