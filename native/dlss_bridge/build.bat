@echo off
rem Builds vkb_dlss.dll and dlss_probe.exe into native\dlss_bridge\build\.
rem Environment overrides (defaults in brackets):
rem   VKB_NGX_SDK     NVIDIA DLSS SDK root            [required, no default]
rem   VKB_VK_HEADERS  Vulkan-Headers include dir       [required, no default]
rem   VKB_VCVARS      vcvars64.bat                     [VS2022 BuildTools]
rem Runtime library: /MT (static CRT) because nvsdk_ngx_s.lib carries /FAILIFMISMATCH:RuntimeLibrary=MT_StaticRelease
rem (nvsdk_ngx_d.lib is the /MD variant). Static CRT also means the DLL needs no VC redistributable.
setlocal

if "%VKB_NGX_SDK%"=="" (echo ERROR: environment variable VKB_NGX_SDK is not set& exit /b 2)
if "%VKB_VK_HEADERS%"=="" (echo ERROR: environment variable VKB_VK_HEADERS is not set& exit /b 2)
if "%VKB_VCVARS%"=="" set "VKB_VCVARS=C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat"

set "HERE=%~dp0"
set "OUT=%HERE%build"

if not exist "%VKB_NGX_SDK%\include\nvsdk_ngx_vk.h" (echo ERROR: NGX SDK not found& exit /b 2)
if not exist "%VKB_NGX_SDK%\lib\Windows_x86_64\x64\nvsdk_ngx_s.lib" (echo ERROR: nvsdk_ngx_s.lib missing & exit /b 2)
if not exist "%VKB_VK_HEADERS%\vulkan\vulkan.h" (echo ERROR: Vulkan headers not found& exit /b 2)
if not exist "%VKB_VCVARS%" (echo ERROR: vcvars64.bat not found& exit /b 2)

call "%VKB_VCVARS%" >nul
if errorlevel 1 (echo ERROR: vcvars64 failed& exit /b 3)

if not exist "%OUT%" mkdir "%OUT%"
pushd "%OUT%"

set "COMMON=/nologo /O2 /MT /EHsc /std:c++17 /W3 /Zi"

echo == building vkb_dlss.dll
cl %COMMON% /I"%HERE%include" /I"%VKB_NGX_SDK%\include" /I"%VKB_VK_HEADERS%" /LD "%HERE%src\vkb_dlss.cpp" /Fe:vkb_dlss.dll /link /NOLOGO /DEBUG /LIBPATH:"%VKB_NGX_SDK%\lib\Windows_x86_64\x64" nvsdk_ngx_s.lib user32.lib advapi32.lib
if errorlevel 1 (echo ERROR: vkb_dlss.dll build failed& popd& exit /b 4)

echo == building dlss_probe.exe
cl %COMMON% /I"%HERE%include" /I"%VKB_NGX_SDK%\include" /I"%VKB_VK_HEADERS%" "%HERE%probe\dlss_probe.cpp" /Fe:dlss_probe.exe /link /NOLOGO /DEBUG vkb_dlss.lib
if errorlevel 1 (echo ERROR: dlss_probe.exe build failed& popd& exit /b 5)

popd
echo == OK, output in build
exit /b 0
