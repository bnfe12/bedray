cmake_minimum_required(VERSION 3.25)

project(bedray_core LANGUAGES CXX)

# Windows x64 is the validated target. The library itself is a plain shared
# object with a C ABI; a non-Windows build is allowed only as an explicit,
# untested portability opt-in and no support is claimed for it.
option(BEDRAY_ALLOW_UNSUPPORTED_PLATFORM
       "Attempt a build on a platform Bedray does not support yet (untested)" OFF)
if(NOT WIN32)
    if(NOT BEDRAY_ALLOW_UNSUPPORTED_PLATFORM)
        message(FATAL_ERROR
            "bedray_core currently supports Windows x64 only. "
            "Pass -DBEDRAY_ALLOW_UNSUPPORTED_PLATFORM=ON to attempt an untested build.")
    endif()
    message(WARNING
        "bedray_core: building on a non-Windows platform by explicit opt-in; "
        "this configuration is untested and unsupported.")
endif()

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Vulkan headers only: the host supplies vkGetInstanceProcAddr/vkGetDeviceProcAddr.
set(BEDRAY_VULKAN_HEADERS "" CACHE PATH "Path to the Vulkan-Headers include directory")
if(NOT BEDRAY_VULKAN_HEADERS)
    if(DEFINED ENV{VULKAN_SDK} AND EXISTS "$ENV{VULKAN_SDK}/include/vulkan/vulkan_core.h")
        set(BEDRAY_VULKAN_HEADERS "$ENV{VULKAN_SDK}/include")
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
    src/core_memory.cpp
    src/ray_generation_pipeline.cpp
    src/path_trace_pass.cpp src/rc_field_pass.cpp
    src/bloom_pass.cpp
    src/particles_pass.cpp
    src/velocity_pass.cpp
    src/nrd_pack_pass.cpp
    src/fg_prepare_pass.cpp
    src/spatial_pass.cpp
    src/volumetric_pass.cpp
)

# History exposes a C API implemented by the core DLL. The registry remains a
# native library; the renderer frame path has not yet adopted its protocol.
add_subdirectory("${CMAKE_CURRENT_LIST_DIR}/../bedray_history"
                 "${CMAKE_CURRENT_BINARY_DIR}/bedray_history")
target_sources(bedray_core PRIVATE "${CMAKE_CURRENT_LIST_DIR}/../bedray_history/src/history_c.cpp")
target_link_libraries(bedray_core PRIVATE bedray_history)

# Export the native resource owners' C APIs. Slang frame adapters supply the
# game-owned device and entry points; submission/completion proof remains
# with the host, allocation and deferred destruction with C++.
add_subdirectory("${CMAKE_CURRENT_LIST_DIR}/../bedray_gpu_resources"
                 "${CMAKE_CURRENT_BINARY_DIR}/bedray_gpu_resources")
target_sources(bedray_core PRIVATE "${CMAKE_CURRENT_LIST_DIR}/../bedray_gpu_resources/src/gpu_buffers_c.cpp")
target_sources(bedray_core PRIVATE "${CMAKE_CURRENT_LIST_DIR}/../bedray_gpu_resources/src/gpu_images_c.cpp")
target_compile_definitions(bedray_core PRIVATE BEDRAY_GPU_RESOURCES_EXPORTS)
target_link_libraries(bedray_core PRIVATE bedray_gpu_resources)

add_executable(bedray_memory_allocator_test ../bedray_gpu_resources/tests/memory_allocator_test.cpp)
target_link_libraries(bedray_memory_allocator_test PRIVATE bedray_gpu_resources)
if(MSVC)
    set_property(TARGET bedray_memory_allocator_test PROPERTY MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
endif()
add_test(NAME bedray_memory_allocator COMMAND bedray_memory_allocator_test)

add_subdirectory("${CMAKE_CURRENT_LIST_DIR}/../bedray_scene"
                 "${CMAKE_CURRENT_BINARY_DIR}/bedray_scene")
target_link_libraries(bedray_core PRIVATE bedray_scene_objects)

# Compile the graph with the core; frame execution still needs integration.
# No second Vulkan loader or device is introduced.
option(BEDRAY_ENABLE_RENDER_GRAPH "Build the renderer graph with the native core" ON)
if(BEDRAY_ENABLE_RENDER_GRAPH)
    set(BEDRAY_GRAPH_VULKAN ON)
    add_subdirectory("${CMAKE_CURRENT_LIST_DIR}/../bedray_render_graph"
                     "${CMAKE_CURRENT_BINARY_DIR}/bedray_render_graph")
    target_link_libraries(bedray_core PRIVATE bedray_graph_vulkan)
    target_link_libraries(bedray_core PRIVATE bedray_graph_history)
    target_link_libraries(bedray_core PRIVATE bedray_graph_frame)
    target_sources(bedray_core PRIVATE src/frame_graph_owner.cpp)
    target_compile_definitions(bedray_core PRIVATE BEDRAY_CORE_FRAME_GRAPH)

    add_executable(bedray_frame_graph_owner_test tests/frame_graph_owner_test.cpp src/frame_graph_owner.cpp)
    target_include_directories(bedray_frame_graph_owner_test PRIVATE include src)
    target_link_libraries(bedray_frame_graph_owner_test PRIVATE bedray_graph_frame)
    if(MSVC)
        target_compile_options(bedray_frame_graph_owner_test PRIVATE /W4 /permissive- /EHsc /utf-8)
        set_property(TARGET bedray_frame_graph_owner_test PROPERTY MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
    endif()
    add_test(NAME bedray_frame_graph_owner COMMAND bedray_frame_graph_owner_test)
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
add_test(NAME bedray_abi_probe COMMAND bedray_abi_probe)
if(MSVC)
    set_property(TARGET bedray_abi_probe PROPERTY MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
endif()

add_executable(bedray_descriptor_cache_test tests/descriptor_cache_test.cpp src/path_trace_pass.cpp src/rc_field_pass.cpp src/bloom_pass.cpp src/particles_pass.cpp src/velocity_pass.cpp src/nrd_pack_pass.cpp src/fg_prepare_pass.cpp src/spatial_pass.cpp src/volumetric_pass.cpp)
target_link_libraries(bedray_descriptor_cache_test PRIVATE bedray_gpu_resources)
target_include_directories(bedray_descriptor_cache_test PRIVATE
    include
    "${CMAKE_CURRENT_LIST_DIR}/../bedray_gpu_resources/include"
    "${BEDRAY_VULKAN_HEADERS}"
)
target_compile_definitions(bedray_descriptor_cache_test PRIVATE BEDRAY_CORE_BUILD WIN32_LEAN_AND_MEAN NOMINMAX)
if(MSVC)
    set_property(TARGET bedray_descriptor_cache_test PROPERTY MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
endif()
add_test(NAME bedray_descriptor_cache_test COMMAND bedray_descriptor_cache_test)

add_executable(bedray_pipeline_cache_test tests/pipeline_cache_test.cpp src/path_trace_pass.cpp src/rc_field_pass.cpp src/bloom_pass.cpp src/particles_pass.cpp src/velocity_pass.cpp src/nrd_pack_pass.cpp src/fg_prepare_pass.cpp src/spatial_pass.cpp src/volumetric_pass.cpp)
target_link_libraries(bedray_pipeline_cache_test PRIVATE bedray_gpu_resources)
target_include_directories(bedray_pipeline_cache_test PRIVATE include "${BEDRAY_VULKAN_HEADERS}")
target_compile_definitions(bedray_pipeline_cache_test PRIVATE BEDRAY_CORE_BUILD WIN32_LEAN_AND_MEAN NOMINMAX)
if(MSVC)
    target_compile_options(bedray_pipeline_cache_test PRIVATE /W4 /permissive- /EHsc /utf-8)
    set_property(TARGET bedray_pipeline_cache_test PROPERTY MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
endif()
add_test(NAME bedray_pipeline_cache COMMAND bedray_pipeline_cache_test)

add_executable(bedray_ray_generation_pipeline_test tests/ray_generation_pipeline_test.cpp src/ray_generation_pipeline.cpp)
target_link_libraries(bedray_ray_generation_pipeline_test PRIVATE bedray_gpu_resources)
target_include_directories(bedray_ray_generation_pipeline_test PRIVATE include "${BEDRAY_VULKAN_HEADERS}")
target_compile_definitions(bedray_ray_generation_pipeline_test PRIVATE BEDRAY_CORE_BUILD WIN32_LEAN_AND_MEAN NOMINMAX)
if(MSVC)
    target_compile_options(bedray_ray_generation_pipeline_test PRIVATE /W4 /permissive- /EHsc /utf-8)
    set_property(TARGET bedray_ray_generation_pipeline_test PROPERTY MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
endif()
add_test(NAME bedray_ray_generation_pipeline COMMAND bedray_ray_generation_pipeline_test)

add_executable(bedray_sampler_ownership_test tests/sampler_ownership_test.cpp src/path_trace_pass.cpp src/rc_field_pass.cpp src/bloom_pass.cpp src/particles_pass.cpp src/velocity_pass.cpp src/nrd_pack_pass.cpp src/fg_prepare_pass.cpp src/spatial_pass.cpp src/volumetric_pass.cpp)
target_link_libraries(bedray_sampler_ownership_test PRIVATE bedray_gpu_resources)
target_include_directories(bedray_sampler_ownership_test PRIVATE
    include
    "${CMAKE_CURRENT_LIST_DIR}/../bedray_gpu_resources/include"
    "${BEDRAY_VULKAN_HEADERS}"
)
target_compile_definitions(bedray_sampler_ownership_test PRIVATE BEDRAY_CORE_BUILD WIN32_LEAN_AND_MEAN NOMINMAX)
if(MSVC)
    set_property(TARGET bedray_sampler_ownership_test PROPERTY MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
endif()
add_test(NAME bedray_sampler_ownership_test COMMAND bedray_sampler_ownership_test)

# These white-box targets include core.cpp and its optional pipeline owner.
foreach(core_test bedray_descriptor_cache_test bedray_pipeline_cache_test bedray_sampler_ownership_test)
    target_sources(${core_test} PRIVATE src/ray_generation_pipeline.cpp)
endforeach()

# White-box test: includes src/path_trace_pass.cpp directly, stubs the core's
# render entry points and program registry, and drives the pass through a fake
# Vulkan driver supplied by bedray_gpu_resources' function tables.
add_executable(bedray_path_trace_pass_test tests/path_trace_pass_test.cpp src/rc_field_pass.cpp)
target_link_libraries(bedray_path_trace_pass_test PRIVATE bedray_gpu_resources)
target_include_directories(bedray_path_trace_pass_test PRIVATE
    include
    "${CMAKE_CURRENT_LIST_DIR}/../bedray_gpu_resources/include"
    "${BEDRAY_VULKAN_HEADERS}"
)
target_compile_definitions(bedray_path_trace_pass_test PRIVATE BEDRAY_CORE_BUILD WIN32_LEAN_AND_MEAN NOMINMAX)
if(MSVC)
    target_compile_options(bedray_path_trace_pass_test PRIVATE /W4 /permissive- /EHsc /utf-8)
    set_property(TARGET bedray_path_trace_pass_test PROPERTY MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
endif()
add_test(NAME bedray_path_trace_pass_test COMMAND bedray_path_trace_pass_test)

add_executable(bedray_bloom_pass_test tests/bloom_pass_test.cpp src/bloom_pass.cpp)
target_link_libraries(bedray_bloom_pass_test PRIVATE bedray_gpu_resources)
target_include_directories(bedray_bloom_pass_test PRIVATE include "${BEDRAY_VULKAN_HEADERS}")
target_compile_definitions(bedray_bloom_pass_test PRIVATE BEDRAY_CORE_BUILD WIN32_LEAN_AND_MEAN NOMINMAX)
if(MSVC)
    target_compile_options(bedray_bloom_pass_test PRIVATE /W4 /permissive- /EHsc /utf-8)
    set_property(TARGET bedray_bloom_pass_test PROPERTY MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
endif()
add_test(NAME bedray_bloom_pass_test COMMAND bedray_bloom_pass_test)

add_executable(bedray_particles_pass_test tests/particles_pass_test.cpp src/particles_pass.cpp)
target_link_libraries(bedray_particles_pass_test PRIVATE bedray_gpu_resources)
target_include_directories(bedray_particles_pass_test PRIVATE include "${BEDRAY_VULKAN_HEADERS}")
target_compile_definitions(bedray_particles_pass_test PRIVATE BEDRAY_CORE_BUILD WIN32_LEAN_AND_MEAN NOMINMAX)
if(MSVC)
    target_compile_options(bedray_particles_pass_test PRIVATE /W4 /permissive- /EHsc /utf-8)
    set_property(TARGET bedray_particles_pass_test PROPERTY MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
endif()
add_test(NAME bedray_particles_pass_test COMMAND bedray_particles_pass_test)

foreach(pass IN ITEMS velocity nrd_pack rc_field fg_prepare spatial)
    add_executable(bedray_${pass}_pass_test tests/${pass}_pass_test.cpp src/${pass}_pass.cpp)
    target_link_libraries(bedray_${pass}_pass_test PRIVATE bedray_gpu_resources)
    target_include_directories(bedray_${pass}_pass_test PRIVATE include "${BEDRAY_VULKAN_HEADERS}")
    target_compile_definitions(bedray_${pass}_pass_test PRIVATE BEDRAY_CORE_BUILD WIN32_LEAN_AND_MEAN NOMINMAX)
    if(MSVC)
        target_compile_options(bedray_${pass}_pass_test PRIVATE /W4 /permissive- /EHsc /utf-8)
        set_property(TARGET bedray_${pass}_pass_test PROPERTY MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
    endif()
    add_test(NAME bedray_${pass}_pass_test COMMAND bedray_${pass}_pass_test)
endforeach()

add_executable(bedray_volumetric_pass_test tests/volumetric_pass_test.cpp src/volumetric_pass.cpp)
target_link_libraries(bedray_volumetric_pass_test PRIVATE bedray_gpu_resources)
target_include_directories(bedray_volumetric_pass_test PRIVATE include "${BEDRAY_VULKAN_HEADERS}")
target_compile_definitions(bedray_volumetric_pass_test PRIVATE BEDRAY_CORE_BUILD WIN32_LEAN_AND_MEAN NOMINMAX)
if(MSVC)
    target_compile_options(bedray_volumetric_pass_test PRIVATE /W4 /permissive- /EHsc /utf-8)
    set_property(TARGET bedray_volumetric_pass_test PROPERTY MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
endif()
add_test(NAME bedray_volumetric_pass_test COMMAND bedray_volumetric_pass_test)

# Compile the real C wrappers into the test process so the allocation fault
# injector controls their operator new (the DLL has its own static CRT).
add_executable(bedray_abi_exception_test tests/abi_exception_test.cpp
    "${CMAKE_CURRENT_LIST_DIR}/../bedray_history/src/history_c.cpp")
target_link_libraries(bedray_abi_exception_test PRIVATE bedray_gpu_resources_c bedray_history)
target_compile_definitions(bedray_abi_exception_test PRIVATE BEDRAY_CORE_BUILD)
if(MSVC)
    target_compile_options(bedray_abi_exception_test PRIVATE /W4 /permissive- /EHsc /utf-8)
    set_property(TARGET bedray_abi_exception_test PROPERTY MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
endif()
add_test(NAME bedray_abi_exception COMMAND bedray_abi_exception_test)

add_executable(bedray_core_scene_exports_test tests/core_scene_exports_test.cpp)
target_include_directories(bedray_core_scene_exports_test PRIVATE
    include
    "${CMAKE_CURRENT_LIST_DIR}/../bedray_scene/include")
target_compile_definitions(bedray_core_scene_exports_test PRIVATE WIN32_LEAN_AND_MEAN NOMINMAX)
if(MSVC)
    target_compile_options(bedray_core_scene_exports_test PRIVATE /W4 /permissive- /EHsc /utf-8)
endif()
add_test(NAME bedray_core_scene_exports COMMAND bedray_core_scene_exports_test $<TARGET_FILE:bedray_core>)

# CPU execution of production shader helpers. CI supplies the pinned SDK; the
# ordinary native build remains independent of a local shader compiler.
set(BEDRAY_SLANGC "" CACHE FILEPATH "Slang compiler for CPU shader contract tests")
if(BEDRAY_SLANGC)
    get_filename_component(shader_root "${CMAKE_CURRENT_LIST_DIR}/../../common/src/main/resources/shaders/slang" ABSOLUTE)
    file(GLOB_RECURSE signal_dependencies CONFIGURE_DEPENDS "${shader_root}/*.slang")
    foreach(contract IN ITEMS denoiser_signals triangle_intersection restir_di)
        set(shader_test "${CMAKE_CURRENT_LIST_DIR}/../../common/src/test/resources/shaders/slang/verify_${contract}.slang")
        set(generated_signals "${CMAKE_CURRENT_BINARY_DIR}/${contract}.generated.cpp")
        add_custom_command(OUTPUT "${generated_signals}"
            COMMAND "${BEDRAY_SLANGC}" "${shader_test}" -target cpp -entry main
                -matrix-layout-column-major -I "${shader_root}" -o "${generated_signals}"
            DEPENDS "${shader_test}" ${signal_dependencies} VERBATIM)
        set_source_files_properties("${generated_signals}" PROPERTIES HEADER_FILE_ONLY TRUE)
        add_executable(bedray_${contract}_test tests/${contract}_test.cpp "${generated_signals}")
        target_include_directories(bedray_${contract}_test PRIVATE "${CMAKE_CURRENT_BINARY_DIR}")
        if(MSVC)
            set_property(TARGET bedray_${contract}_test PROPERTY MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
        endif()
        add_test(NAME bedray_${contract} COMMAND bedray_${contract}_test)
    endforeach()
endif()
