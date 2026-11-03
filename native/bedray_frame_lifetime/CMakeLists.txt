cmake_minimum_required(VERSION 3.25)
project(bedray_frame_lifetime LANGUAGES CXX)

add_library(bedray_frame_lifetime STATIC src/frame_lifetime.cpp)
target_include_directories(bedray_frame_lifetime PUBLIC include)
target_compile_features(bedray_frame_lifetime PUBLIC cxx_std_20)

include(CTest)
if(BUILD_TESTING)
    add_executable(frame_lifetime_test tests/frame_lifetime_test.cpp)
    target_link_libraries(frame_lifetime_test PRIVATE bedray_frame_lifetime)
    add_test(NAME frame_lifetime_contract COMMAND frame_lifetime_test)
endif()
