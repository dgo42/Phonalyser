@echo off
rem ---------------------------------------------------------------------------
rem  Build the mock libusb-1.0 DLL with Visual Studio 2015 ONLY (toolset v140).
rem
rem  Usage:  build.cmd [Debug or Release]      default: Release
rem
rem  Uses "C:\Program Files (x86)\Microsoft Visual Studio 14.0\VC\vcvarsall.bat"
rem  and the MSBuild (14.0) that environment puts on PATH.  Never the VS2019 /
rem  VS2022 MSBuild, never a newer toolset.
rem ---------------------------------------------------------------------------
setlocal

set "CONFIG=%~1"
if "%CONFIG%"=="" set "CONFIG=Debug"

set "VCVARS=C:\Program Files (x86)\Microsoft Visual Studio 14.0\VC\vcvarsall.bat"
if not exist "%VCVARS%" goto :novs

call "%VCVARS%" x64
if errorlevel 1 goto :vcfail

pushd "%~dp0"
msbuild libusb-mock.sln /m /nologo /v:minimal /p:Configuration=%CONFIG% /p:Platform=x64
set "RC=%ERRORLEVEL%"
popd

if not "%RC%"=="0" goto :done
echo.
echo Built: %~dp0x64\%CONFIG%\libusb-1.0.dll
echo    and %~dp0x64\%CONFIG%\libusb-1.0_x64.dll
goto :done

:novs
echo ERROR: Visual Studio 2015 not found at:
echo   %VCVARS%
echo This mock builds with Visual Studio 2015, Platform Toolset v140, ONLY.
exit /b 1

:vcfail
echo ERROR: vcvarsall.bat x64 failed.
exit /b 1

:done
exit /b %RC%
