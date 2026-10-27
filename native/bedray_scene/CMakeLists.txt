cmake_minimum_required(VERSION 3.25)

project(bedray_scene LANGUAGES CXX)

if(NOT WIN32)
    message(FATAL_ERROR "bedray_scene currently supports Windows only")
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

add_library(bedray_scene SHARED
    src/scene.cpp
    src/acceleration_scene.cpp
)

target_include_directories(bedray_scene
    PUBLIC  include
    PRIVATE "${BEDRAY_VULKAN_HEADERS}"
)

target_compile_definitions(bedray_scene PRIVATE
    BEDRAY_SCENE_BUILD
    WIN32_LEAN_AND_MEAN
    NOMINMAX
)

if(MSVC)
    target_compile_options(bedray_scene PRIVATE /W4 /permissive- /EHsc /utf-8)
    set_property(TARGET bedray_scene PROPERTY MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
endif()

set_target_properties(bedray_scene PROPERTIES
    OUTPUT_NAME "bedray_scene"
    PREFIX ""
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN ON
)

enable_testing()

add_executable(bedray_scene_contract_test tests/scene_contract_test.cpp)
target_link_libraries(bedray_scene_contract_test PRIVATE bedray_scene)
target_include_directories(bedray_scene_contract_test PRIVATE include "${BEDRAY_VULKAN_HEADERS}")
if(MSVC)
    target_compile_options(bedray_scene_contract_test PRIVATE /W4 /permissive- /EHsc /utf-8)
    set_property(TARGET bedray_scene_contract_test PROPERTY MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
endif()

add_test(NAME bedray_scene_contract COMMAND bedray_scene_contract_test)
