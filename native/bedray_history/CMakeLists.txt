cmake_minimum_required(VERSION 3.25)
project(bedray_history LANGUAGES CXX)

add_library(bedray_history STATIC
    src/history_state.cpp
    src/history_slots.cpp
    src/history_registry.cpp)
set_property(TARGET bedray_history PROPERTY POSITION_INDEPENDENT_CODE ON)
target_include_directories(bedray_history PUBLIC include)
target_compile_features(bedray_history PUBLIC cxx_std_20)
if(MSVC)
    set_property(TARGET bedray_history PROPERTY MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
endif()

option(BEDRAY_HISTORY_BUILD_TESTS "Build focused history C ABI tests" OFF)

if(BEDRAY_HISTORY_BUILD_TESTS)
    # The C wrapper itself is compiled into bedray_core.dll, so the test builds
    # the same translation unit over the static registry.
    add_executable(bedray_history_c_test tests/history_c_test.cpp src/history_c.cpp)
    target_link_libraries(bedray_history_c_test PRIVATE bedray_history)
    # The header declares __declspec(dllimport) for consumers; this test links
    # the wrapper statically, so it must compile against the exporting declaration.
    target_compile_definitions(bedray_history_c_test PRIVATE BEDRAY_CORE_BUILD)
    if(MSVC)
        target_compile_options(bedray_history_c_test PRIVATE /W4 /permissive- /EHsc /utf-8)
        set_property(TARGET bedray_history_c_test PROPERTY MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
    endif()
    add_test(NAME bedray_history_c COMMAND bedray_history_c_test)
endif()
