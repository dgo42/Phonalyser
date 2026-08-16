@echo off
rem ===========================================================================
rem  Phonalyser headless server - console launcher (Windows).
rem
rem  What it does : starts the server from the phonalyser-server-*.jar that sits
rem                 next to this script, with the bundled natives\ folder on the
rem                 native library search path (PortAudio and libusb are loaded
rem                 by JNA from there, not from the class path).
rem  Requires     : a Java 17+ runtime on the PATH.
rem                 Nothing is installed: unpack the zip anywhere and run this.
rem  Arguments    : passed straight through to the server, for example
rem                   phonalyser-server.cmd --name bench1 --port 8377
rem                 Recognised: --name, --port, --bind,
rem                 -d / --daemon.
rem  Stop it      : Ctrl-C in this window - that runs the shutdown hook, which
rem                 closes the audio lines and parks a QA40x analyzer.
rem  Log file     : phonalyser-server.log under %%ProgramData%%\Phonalyser\logs
rem                 (the server's data - devices.yaml incl. - lives machine-scope
rem                 in %%ProgramData%%\Phonalyser; %%APPDATA%% belongs to the GUI)
rem ===========================================================================
setlocal enabledelayedexpansion
cd /d "%~dp0"

where java >nul 2>&1
if errorlevel 1 (
  echo Java 17+ was not found on the PATH.
  exit /b 1
)

set "JAR="
for %%f in (phonalyser-server-*.jar) do set "JAR=%%f"
if not defined JAR (
  echo No phonalyser-server-*.jar found next to this script.
  exit /b 1
)

set "NATIVES=%~dp0natives"

rem A 32-bit JVM defaults to a 256 MB max heap and cannot reserve much beyond
rem ~1.4 GB of contiguous address space on 32-bit Windows - cap the x86 bundle
rem explicitly at a safe 1200m, exactly as the desktop launcher does.  The x64
rem bundle must NOT be capped.
rem What says 32-bit here is the BUNDLE, not the jar: one server jar serves
rem every platform and carries no architecture in its name.  The staged natives
rem name theirs explicitly - csjsound_x86.dll against the x64 bundle's
rem csjsound_amd64.dll - so this test is true in the x86 bundle alone.
set "MEM="
if exist "%NATIVES%\csjsound_x86.dll" set "MEM=-Xmx1200m"

java "-Djava.library.path=%NATIVES%" "-Djna.library.path=%NATIVES%" "-Dlibusb.path=%NATIVES%" !MEM! -jar "!JAR!" %*
