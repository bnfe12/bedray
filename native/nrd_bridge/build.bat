@echo off
rem Builds vkb_nrd.dll and nrd_probe.exe into native\nrd_bridge\build\.
rem vkb_nrd.dll contains NO NRD code: NRD.dll (build_nrd.bat) is loaded at run time. Only the NRD headers are needed here.
rem Environment overrides (defaults in brackets):
rem   VKB_NRD_SRC     NRD checkout, headers from Include\          [required, no default]
rem   VKB_VK_HEADERS  Vulkan-Headers include dir                   [required, no default]
rem   VKB_VCVARS      vcvars64.bat                                 [VS2022 BuildTools]
rem Runtime library: /MT (static CRT) as for vkb_dlss, so the DLL needs no VC redistributable.
setlocal

if "%VKB_NRD_SRC%"=="" (echo ERROR: environment variable VKB_NRD_SRC is not set& exit /b 2)
if "%VKB_VK_HEADERS%"=="" (echo ERROR: environment variable VKB_VK_HEADERS is not set& exit /b 2)
if "%VKB_VCVARS%"=="" set "VKB_VCVARS=C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat"

set "HERE=%~dp0"
set "OUT=%HERE%build"

if not exist "%VKB_NRD_SRC%\Include\NRD.h" (echo ERROR: NRD headers not found in %VKB_NRD_SRC%\Include& exit /b 2)
if not exist "%VKB_VK_HEADERS%\vulkan\vulkan.h" (echo ERROR: Vulkan headers not found& exit /b 2)
if not exist "%VKB_VCVARS%" (echo ERROR: vcvars64.bat not found& exit /b 2)

call "%VKB_VCVARS%" >nul
if errorlevel 1 (echo ERROR: vcvars64 failed& exit /b 3)

if not exist "%OUT%" mkdir "%OUT%"
pushd "%OUT%"

set "COMMON=/nologo /O2 /MT /EHsc /std:c++17 /W3 /Zi"

echo == building vkb_nrd.dll
cl %COMMON% /I"%HERE%include" /I"%VKB_NRD_SRC%\Include" /I"%VKB_VK_HEADERS%" /LD "%HERE%src\vkb_nrd.cpp" /Fe:vkb_nrd.dll /link /NOLOGO /DEBUG user32.lib
if errorlevel 1 (echo ERROR: vkb_nrd.dll build failed& popd& exit /b 4)

echo == building nrd_probe.exe
cl %COMMON% /I"%HERE%include" /I"%VKB_VK_HEADERS%" "%HERE%probe\nrd_probe.cpp" /Fe:nrd_probe.exe /link /NOLOGO /DEBUG vkb_nrd.lib
if errorlevel 1 (echo ERROR: nrd_probe.exe build failed& popd& exit /b 5)

popd
echo == OK, output in build
exit /b 0
