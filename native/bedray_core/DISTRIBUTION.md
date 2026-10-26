# Windows distribution source

The root Gradle tasks `fabricBedrayDistribution` and
`neoforgeBedrayDistribution` build the corresponding combined loader JAR and
the native renderer DLL from this checkout. Their output is under
`build/bedray-distribution/<loader>/`. They do not install or alter an instance.

The current native loader expects `bedray_core.dll` in the instance's
`bedray/natives/` directory; the matching loader JAR belongs in `mods/`.
The distribution contains two files, not an automatically extracted DLL.
Never replace an instance's files while the game is running.

Windows x64, CMake, the MSVC C++ toolchain and Vulkan headers are required.
Set `-PbedrayCmake=<path-to-cmake.exe>` when CMake is not on PATH and
`-PbedrayVulkanHeaders=<include-directory>` when the headers cannot be found
by `native/bedray_core/CMakeLists.txt`. Slang and launcher dependencies have
their own build requirements.

These tasks are source-only preparation. The presence of build instructions
does not prove that migration, compilation, installation or game rendering is
complete. The C++/Slang renderer still has to be integrated and verified.
