@echo off
rem Builds vkb_sl.dll (thin C ABI over Streamline headers, no NVIDIA binary linked) into native\sl_bridge\build\.
rem   VKB_SL_SDK  Streamline SDK root (include\) [required, no default]
rem   VKB_VCVARS  vcvars64.bat                    [VS2022 BuildTools]
setlocal
if "%VKB_SL_SDK%"=="" (echo ERROR: environment variable VKB_SL_SDK is not set& exit /b 2)
if "%VKB_VCVARS%"=="" set "VKB_VCVARS=C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat"
set "HERE=%~dp0"
set "OUT=%HERE%build"
if not exist "%VKB_SL_SDK%\include\sl.h" (echo ERROR: Streamline SDK not found& exit /b 2)
call "%VKB_VCVARS%" >nul
if errorlevel 1 (echo ERROR: vcvars64 failed& exit /b 3)
if not exist "%OUT%" mkdir "%OUT%"
pushd "%OUT%"
if "%VKB_VK_HEADERS%"=="" (echo ERROR: environment variable VKB_VK_HEADERS is not set& exit /b 2)
cl /nologo /O2 /MT /EHsc /std:c++17 /W3 /LD /I"%VKB_SL_SDK%\include" /I"%VKB_VK_HEADERS%" "%HERE%src\vkb_sl.cpp" "%HERE%src\vkb_sl_dlss.cpp" /Fe:vkb_sl.dll /link /NOLOGO wintrust.lib crypt32.lib user32.lib
set RC=%ERRORLEVEL%
popd
exit /b %RC%
