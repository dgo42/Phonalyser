@echo off
set JAVA_TOOL_OPTIONS=-Dlibusb.path="%~dp0."
start "" "%~dp0Phonalyser.exe" %*
