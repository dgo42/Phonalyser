@echo off
rem ===========================================================================
rem  Phonalyser headless server - remove the Windows service.
rem
rem  What it does : stops the service "PhonalyserServer", unregisters it through
rem                 WinSW and deletes the generated phonalyser-server-service.xml.
rem                 Running it when the service is not installed is not an error.
rem
rem  Requires     : an elevated command prompt ("Run as administrator").
rem
rem  What is kept : the unpacked server folder and %ProgramData%\Phonalyser
rem                 (device cards, calibration, the server id and the logs -
rem                 every launch form's home).  Calibration is data, not
rem                 packaging - delete it by hand if you really mean to.
rem
rem  Graceful     : the stop below is a service-control request, so WinSW sends
rem                 the server a Ctrl+C and waits for it.  The shutdown hook
rem                 closes the audio lines and parks a QA40x analyzer before the
rem                 process leaves.
rem ===========================================================================
setlocal

set "SERVICE_ID=PhonalyserServer"
set "SVC_HERE=%~dp0"
if "%SVC_HERE:~-1%"=="\" set "SVC_HERE=%SVC_HERE:~0,-1%"

set "WINSW=%SVC_HERE%\phonalyser-server-service.exe"
set "SVC_XML=%SVC_HERE%\phonalyser-server-service.xml"

net session >nul 2>&1
if errorlevel 1 (
  echo Removing a service needs an elevated command prompt.
  echo Right-click cmd.exe and choose "Run as administrator".
  exit /b 1
)

sc query "%SERVICE_ID%" >nul 2>&1
if errorlevel 1 (
  echo No service "%SERVICE_ID%" is installed - nothing to do.
  if exist "%SVC_XML%" del "%SVC_XML%"
  exit /b 0
)

rem WinSW needs both itself and its config; without either, fall back to sc.exe.
rem Decided once, into a flag: chaining two "if exist" before an else would bind
rem the else to the second test only, and skip both branches when WinSW is gone.
set "USE_WINSW="
if exist "%WINSW%" if exist "%SVC_XML%" set "USE_WINSW=1"

echo Stopping "%SERVICE_ID%" ...
if defined USE_WINSW (
  "%WINSW%" stop
) else (
  sc stop "%SERVICE_ID%" >nul 2>&1
)

echo Removing "%SERVICE_ID%" ...
if defined USE_WINSW (
  "%WINSW%" uninstall
) else (
  sc delete "%SERVICE_ID%"
)
if errorlevel 1 (
  echo Could not remove the service.  If it is still marked for deletion, close
  echo services.msc and run this script again.
  exit /b 1
)

if exist "%SVC_XML%" del "%SVC_XML%"

echo Removed.  %ProgramData%\Phonalyser and the server folder were left alone.
