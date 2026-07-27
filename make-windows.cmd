@echo off
REM ---------------------------------------------------------------------------
REM Windows build.
REM
REM   make-windows          x64: fat jar + jpackage APP_IMAGE (no installer wrap)
REM   make-windows x86      x86: fat jar ONLY
REM
REM APP_IMAGE, not EXE, is deliberate: the EXE type makes jpackage shell out to
REM the WiX toolset (candle.exe / light.exe), which this project does not use and
REM does not require anyone to install.  The release pipeline wraps the app-image
REM into an MSIX separately (see PACKAGING.md).  A bare `mvn package` WILL try to
REM build an EXE, because that is the pom default for this platform.
REM
REM x86 is fat-jar only on purpose: jpackage bundles the JDK it runs on, so an
REM x86 app-image would need an x86 JDK.  The 32-bit build exists for the SWT
REM x86 users who run it on their own JRE, and that is the jar.
REM ---------------------------------------------------------------------------
setlocal
REM Run from this script's own directory, so it works from anywhere.
cd /d "%~dp0"

set ARCH=%~1
if "%ARCH%"=="" set ARCH=x64
if /i "%ARCH%"=="x64" goto :arch_ok
if /i "%ARCH%"=="x86" goto :arch_ok
echo Usage: make-windows [x64^|x86]
exit /b 2
:arch_ok

if /i "%ARCH%"=="x86" (
    set PROFILE=windows-x86
    set PLATFORM_ID=windows-x86
    REM Fat jar only - see the header.
    set EXTRA=-Dskip.jpackage=true
) else (
    set PROFILE=windows-x64
    set PLATFORM_ID=windows
    set EXTRA=-Djpackage.type=APP_IMAGE
)

REM Project Nayuki's flac-library is not on Maven Central, but it is vendored as
REM the modules/flac-library-java module and the reactor builds it - no separate
REM install step is needed any more.

REM jpackage refuses to overwrite, and a previous app-image can hold
REM runtime\lib\modules open long enough to fail maven-clean.  Remove it first.
if exist "modules\phonalyser-app\target\installer" rmdir /s /q "modules\phonalyser-app\target\installer"

echo === Building Phonalyser (%PROFILE%) ===
call mvn -B -ntp clean "-P%PROFILE%" %EXTRA% -DskipTests package
if errorlevel 1 goto :error

echo.
for %%F in ("modules\phonalyser-app\target\phonalyser-*-%PLATFORM_ID%.jar") do echo Fat jar   : %%F
if /i not "%ARCH%"=="x86" echo App image : modules\phonalyser-app\target\installer\Phonalyser\Phonalyser.exe
exit /b 0

:error
echo.
echo BUILD FAILED
exit /b 1
