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
