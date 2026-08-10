@echo off
set JAVA_TOOL_OPTIONS=-Dlibusb.path="%~dp0."
cd /d "%LOCALAPPDATA%"
start "" "%~dp0Phonalyser.exe" %*
