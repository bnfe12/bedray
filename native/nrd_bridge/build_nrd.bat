@echo off
rem Builds NRD.dll (NVIDIA Real-Time Denoisers) from sources that live OUTSIDE this repository.
rem NRD is under the "NVIDIA RTX SDKs LICENSE": its sources/binaries must not be committed or redistributed
rem (see docs/LICENSING.md). Everything is written to native\nrd_bridge\build\ (gitignored).
rem
rem Environment overrides (defaults in brackets):
rem   VKB_NRD_SRC   NRD 4.17.x or 4.18.x source checkout [required, no default]
rem   VKB_VCVARS    vcvars64.bat                                                   [VS2022 BuildTools]
rem   VKB_CMAKE     cmake.exe                                                      [CMake bundled with VS2022 BuildTools]
rem   VKB_NINJA     ninja.exe                                                      [Ninja bundled with VS2022 BuildTools]
rem
rem Options (same as used in the research build):
rem   SPIR-V shaders only (DXIL/DXBC OFF), static CRT (/MT => no VC redistributable),
rem   NRD_SUPPORTS_QUAD_INTRINSICS=OFF (our VkDevice does not enable VK_KHR_compute_shader_derivatives),
rem   NRD_NORMAL_ENCODING stays at the NRD default (2 = R10G10B10A2); the format is read at runtime from LibraryDesc.
rem CMake FetchContent downloads ShaderMake, MathLib and DXC (~35 MB) on the first run; needs network access.
setlocal

if "%VKB_NRD_SRC%"=="" (echo ERROR: environment variable VKB_NRD_SRC is not set& exit /b 2)
if "%VKB_VCVARS%"=="" set "VKB_VCVARS=C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat"
set "VS_CMAKE_ROOT=C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\Common7\IDE\CommonExtensions\Microsoft\CMake"
if "%VKB_CMAKE%"=="" set "VKB_CMAKE=%VS_CMAKE_ROOT%\CMake\bin\cmake.exe"
if "%VKB_NINJA%"=="" set "VKB_NINJA=%VS_CMAKE_ROOT%\Ninja\ninja.exe"

set "HERE=%~dp0"
set "OUT=%HERE%build"
for %%I in ("%VKB_NRD_SRC%") do set "NRD_SOURCE_NAME=%%~nxI"
set "CMAKE_BUILD=%OUT%\nrd_cmake_%NRD_SOURCE_NAME%"
set "SHADERS=%OUT%\nrd_shaders"

if not exist "%VKB_NRD_SRC%\Include\NRD.h" (echo ERROR: NRD sources not found in %VKB_NRD_SRC% ^(use a 4.17.x or 4.18.x checkout from github.com/NVIDIA-RTX/NRD^)& exit /b 2)
if not exist "%VKB_VCVARS%" (echo ERROR: vcvars64.bat not found& exit /b 2)
if not exist "%VKB_CMAKE%" (echo ERROR: cmake.exe not found& exit /b 2)
if not exist "%VKB_NINJA%" (echo ERROR: ninja.exe not found& exit /b 2)

call "%VKB_VCVARS%" >nul
if errorlevel 1 (echo ERROR: vcvars64 failed& exit /b 3)

if not exist "%OUT%" mkdir "%OUT%"
rem CMake wants forward slashes in -D paths
set "OUT_FS=%OUT:\=/%"
set "SHADERS_FS=%SHADERS:\=/%"

echo == configuring NRD (source %VKB_NRD_SRC%)
"%VKB_CMAKE%" -S "%VKB_NRD_SRC%" -B "%CMAKE_BUILD%" -G Ninja -DCMAKE_MAKE_PROGRAM="%VKB_NINJA%" ^
  -DCMAKE_BUILD_TYPE=Release ^
  -DNRD_EMBEDS_DXIL_SHADERS=OFF -DNRD_EMBEDS_DXBC_SHADERS=OFF ^
  -DNRD_SUPPORTS_QUAD_INTRINSICS=OFF ^
  -DNRD_SHADERS_PATH="%SHADERS_FS%" ^
  -DCMAKE_RUNTIME_OUTPUT_DIRECTORY="%OUT_FS%" -DCMAKE_LIBRARY_OUTPUT_DIRECTORY="%OUT_FS%" ^
  -DCMAKE_MSVC_RUNTIME_LIBRARY=MultiThreaded
if errorlevel 1 (echo ERROR: cmake configure failed& exit /b 4)

echo == building NRD.dll
"%VKB_CMAKE%" --build "%CMAKE_BUILD%" -j 8
if errorlevel 1 (echo ERROR: NRD build failed& exit /b 5)

if not exist "%OUT%\NRD.dll" (echo ERROR: build reported success but NRD.dll is missing& exit /b 6)
echo == OK: %OUT%\NRD.dll
exit /b 0
