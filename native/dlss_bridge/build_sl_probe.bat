@echo off
rem Builds sl_probe.exe (Streamline requirements probe) into native\dlss_bridge\build\.
rem   VKB_SL_SDK  Streamline SDK root (include\) [required, no default]
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
cl /nologo /O2 /MT /EHsc /std:c++17 /W3 /I"%VKB_SL_SDK%\include" "%HERE%probe\sl_probe.cpp" /Fe:sl_probe.exe /link /NOLOGO
set RC=%ERRORLEVEL%
popd
exit /b %RC%
