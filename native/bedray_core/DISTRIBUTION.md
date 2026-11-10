# Windows distribution source

The root Gradle tasks `fabricBedrayDistribution` and
`neoforgeBedrayDistribution` build the corresponding combined loader JAR and
the native renderer DLL from this checkout. Their output is under
`build/bedray-distribution/<loader>/`. They do not install or alter an instance.

Copy `bedray_core.dll` into the instance's `bedray/natives/` directory and the matching
loader JAR into `mods/`. Close Minecraft before replacing either file.

Ray tracing and path tracing require the GPU scene exports embedded in
`bedray_core.dll`. The Slang passes consume its native scene table by default.
`buildBedrayScene` and the standalone CMake project remain developer ABI targets.

Windows x64, CMake, the MSVC C++ toolchain and Vulkan headers are required.
Set `-PbedrayCmake=<path-to-cmake.exe>` when CMake is not on PATH and
`-PbedrayVulkanHeaders=<include-directory>` when the headers cannot be found
by `native/bedray_core/CMakeLists.txt`. Slang and launcher dependencies have
their own build requirements.
