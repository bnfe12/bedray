cmake_minimum_required(VERSION 3.25)

project(bedray_scene LANGUAGES CXX)

# Windows x64 is the validated target. A non-Windows build is an explicit,
# untested portability opt-in; no support is claimed for it.
option(BEDRAY_ALLOW_UNSUPPORTED_PLATFORM
       "Attempt a build on a platform Bedray does not support yet (untested)" OFF)
if(NOT WIN32)
    if(NOT BEDRAY_ALLOW_UNSUPPORTED_PLATFORM)
        message(FATAL_ERROR
            "bedray_scene currently supports Windows x64 only. "
            "Pass -DBEDRAY_ALLOW_UNSUPPORTED_PLATFORM=ON to attempt an untested build.")
    endif()
    message(WARNING
        "bedray_scene: building on a non-Windows platform by explicit opt-in; "
        "this configuration is untested and unsupported.")
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

add_library(bedray_scene_objects OBJECT
    src/scene.cpp
    src/acceleration_scene.cpp
)

target_include_directories(bedray_scene_objects
    PUBLIC  include
    PRIVATE "${BEDRAY_VULKAN_HEADERS}"
)

target_compile_definitions(bedray_scene_objects PRIVATE
    BEDRAY_SCENE_BUILD
    WIN32_LEAN_AND_MEAN
    NOMINMAX
)

if(MSVC)
    target_compile_options(bedray_scene_objects PRIVATE /W4 /permissive- /EHsc /utf-8)
    set_property(TARGET bedray_scene_objects PROPERTY MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
endif()

# Embed the same implementation in core; keep the standalone DLL for ABI tests
# and old consumers. No Vulkan loader or second device is linked or created.
add_library(bedray_scene SHARED)
target_link_libraries(bedray_scene PUBLIC bedray_scene_objects)
if(MSVC)
    set_property(TARGET bedray_scene PROPERTY MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
endif()

set_target_properties(bedray_scene PROPERTIES
    OUTPUT_NAME "bedray_scene"
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN ON
)
# Windows has no "lib" prefix. Linux and macOS keep CMake's default prefix so
# the shared object matches System.mapLibraryName("bedray_scene"), which is what
# the Java locator asks NativeLibraries for.
if(WIN32)
    set_target_properties(bedray_scene PROPERTIES PREFIX "")
endif()

enable_testing()

add_executable(bedray_scene_contract_test tests/scene_contract_test.cpp)
target_link_libraries(bedray_scene_contract_test PRIVATE bedray_scene)
target_include_directories(bedray_scene_contract_test PRIVATE include "${BEDRAY_VULKAN_HEADERS}")
if(MSVC)
    target_compile_options(bedray_scene_contract_test PRIVATE /W4 /permissive- /EHsc /utf-8)
    set_property(TARGET bedray_scene_contract_test PROPERTY MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
endif()

add_test(NAME bedray_scene_contract COMMAND bedray_scene_contract_test)
