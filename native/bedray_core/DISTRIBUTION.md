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

`bedray_core.dll` has been built from this checkout with the same CMake
configure line the Gradle task uses, and its contract tests pass (2026-10-28).
`fabricBedrayDistribution`/`neoforgeBedrayDistribution` have been executed and
both DLLs plus the loader JAR were staged into the test instance on 2026-10-28;
the C++/Slang renderer is still not verified in game. Build and installation
evidence does not prove live rendering correctness.

The GPU scene (`native/bedray_scene`) is a separate CMake project and a second
DLL. `buildBedrayScene` configures and builds it, and both distribution tasks
copy `bedray_scene.dll` next to `bedray_core.dll`; `BedraySceneLocator` resolves
it from `<gameDir>/bedray/natives`, so the pair is installed together.
