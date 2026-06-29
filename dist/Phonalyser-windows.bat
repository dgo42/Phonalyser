@echo off
rem Run Phonalyser with your own Java 17+ runtime (no bundled JRE).
rem Place this next to phonalyser-<version>-windows.jar, then double-click it
rem or run it from a terminal.  Requires Java 17+ on the PATH.
setlocal enabledelayedexpansion
cd /d "%~dp0"

where java >nul 2>&1
if errorlevel 1 (
  echo Java 17+ was not found on the PATH.  Install it from https://adoptium.net/
  pause
  exit /b 1
)

set "JAR="
for %%f in (phonalyser-*-windows.jar) do set "JAR=%%f"
if not defined JAR (
  echo No phonalyser-*-windows.jar found next to this script.
  pause
  exit /b 1
)

java -jar "!JAR!" %*
