@echo off
rem ===========================================================================
rem  Phonalyser headless server - install the Windows service.
rem
rem  What it does : writes phonalyser-server-service.xml (from the echo block
rem                 below, with this folder's paths filled in) and registers
rem                 the service "PhonalyserServer" through WinSW.  Re-running
rem                 the script removes the old service first, so it is safe to
rem                 repeat.
rem
rem  Mechanism    : WinSW (phonalyser-server-service.exe) - a real Service
rem                 Control Manager service that supervises the JVM.  A plain
rem                 Java process cannot be an SCM service itself: it never calls
rem                 StartServiceCtrlDispatcher, so "sc create java.exe" is killed
rem                 at start-up with error 1053.  WinSW also STOPS the server by
rem                 sending Ctrl+C and waiting, so the shutdown hook runs and a
rem                 QA40x analyzer is parked - a scheduled task would terminate
rem                 the process and leave the analyzer at full sensitivity with
rem                 its output line open.
rem
rem  Requires     : an elevated command prompt ("Run as administrator") and a
rem                 Java 17+ runtime findable on the PATH.
rem
rem  Usage        : install-service.cmd [/user DOMAIN\name] [server arguments...]
rem
rem                   (default)     run as LocalSystem, and pin the data and log
rem                                 directories to %ProgramData%\Phonalyser -
rem                                 LocalSystem's own profile is buried under
rem                                 System32\config\systemprofile and nobody
rem                                 would find a log there.  Good for a bench
rem                                 whose only hardware is a QA40x.
rem                   /user D\name  run as a real account.  The account decides
rem                                 the AUDIO stack the service sees, not the
rem                                 paths: data and logs stay machine-scope in
rem                                 %ProgramData%\Phonalyser like every other
rem                                 launch form.  Needed for a SOUND-CARD
rem                                 bench - see README-server-windows.txt.
rem
rem                 Example: install-service.cmd /user BENCH\alex --name bench1
rem
rem  Uninstall    : uninstall-service.cmd
rem ===========================================================================
setlocal

set "SERVICE_ID=PhonalyserServer"
set "SVC_HERE=%~dp0"
if "%SVC_HERE:~-1%"=="\" set "SVC_HERE=%SVC_HERE:~0,-1%"

set "WINSW=%SVC_HERE%\phonalyser-server-service.exe"
set "SVC_XML=%SVC_HERE%\phonalyser-server-service.xml"
set "PROGDATA=%ProgramData%\Phonalyser"
set "SVC_LOGDIR=%PROGDATA%\logs"

net session >nul 2>&1
if errorlevel 1 (
  echo Installing a service needs an elevated command prompt.
  echo Right-click cmd.exe and choose "Run as administrator".
  exit /b 1
)

rem --- the optional account --------------------------------------------------
set "ACCOUNT="
if /i "%~1"=="/user" (
  if "%~2"=="" (
    echo /user needs an account name, for example: /user BENCH\alex
    exit /b 1
  )
  set "ACCOUNT=%~2"
  shift
  shift
)

rem %* ignores SHIFT, so the remaining arguments are collected by hand.
rem Seeded with -d: a SERVICE is a daemon by definition, and it has no console
rem to print to.  The flag only silences the console half - the log file is
rem written in every mode - so the operator loses nothing by it.
set "SVC_ARGS= -d"
:collect
if "%~1"=="" goto collected
set "SVC_ARGS=%SVC_ARGS% %1"
shift
goto collect
:collected

rem --- everything the generated config needs ---------------------------------
if not exist "%WINSW%" (
  echo phonalyser-server-service.exe is missing from %SVC_HERE%.
  echo That file is WinSW, and it ships inside the server zip - unpack the whole
  echo zip rather than copying the jar out of it.
  exit /b 1
)
set "SVC_JAR="
for %%f in ("%SVC_HERE%\phonalyser-server-*.jar") do set "SVC_JAR=%%~ff"
if not defined SVC_JAR (
  echo No phonalyser-server-*.jar found in %SVC_HERE%.
  exit /b 1
)

rem java.exe, not javaw.exe: WinSW stops the server with Ctrl+C, and that only
rem reaches a process that has a console.
set "SVC_JAVA="
for /f "delims=" %%j in ('where java 2^>nul') do if not defined SVC_JAVA set "SVC_JAVA=%%j"
if not defined SVC_JAVA (
  echo Java 17+ was not found on the PATH.
  echo The service needs an absolute path to java.exe, so it must be findable now.
  exit /b 1
)

rem The natives folder, and - only for LocalSystem - the data and log directory
rem pins.  A real user account gets neither: AppPaths decides for it, exactly as
rem it does for the console launcher.
set SVC_OPTS="-Djava.library.path=%SVC_HERE%\natives" "-Djna.library.path=%SVC_HERE%\natives" "-Dlibusb.path=%SVC_HERE%\natives"
if not defined ACCOUNT set SVC_OPTS=%SVC_OPTS% "-Dapp.data.dir=%PROGDATA%" "-Dapp.log.dir=%SVC_LOGDIR%"

set "SVC_ACCT="
if defined ACCOUNT set "SVC_ACCT=    <serviceaccount><username>%ACCOUNT%</username><allowservicelogon>true</allowservicelogon></serviceaccount>"

rem --- take any previous service down, with its OWN config --------------------
sc query "%SERVICE_ID%" >nul 2>&1
if not errorlevel 1 (
  echo Service "%SERVICE_ID%" is already installed - removing it first.
  if exist "%SVC_XML%" (
    "%WINSW%" stop
    "%WINSW%" uninstall
  ) else (
    sc stop "%SERVICE_ID%" >nul 2>&1
    sc delete "%SERVICE_ID%" >nul
  )
)

rem --- generate the live config -----------------------------------------------
rem Pure cmd, no child process: the XML is written by the echo block below.
rem Literal XML characters are caret-escaped; the VALUES go through DELAYED
rem expansion (!VAR!), which happens after the parser has done its work - a
rem path containing ampersands, parentheses or quotes cannot break or inject
rem into the block.  The one caveat: delayed expansion consumes literal
rem exclamation marks, so a path containing one would lose it.  Every
rem variable used below was validated above, and the block writes plain
rem ASCII bytes in the console code page - keep the server folder on an
rem ASCII-safe path.  A PowerShell child was dropped deliberately: it
rem restyled the launching console's font (bench, Far Manager, 2026-08-03).
setlocal enabledelayedexpansion
> "%SVC_XML%" echo ^<?xml version="1.0" encoding="UTF-8"?^>
>>"%SVC_XML%" echo ^<^^!-- Generated by install-service.cmd - do not edit, re-run the installer.
>>"%SVC_XML%" echo      WinSW reads this file; it must share the wrapper executable's base name.
>>"%SVC_XML%" echo      Stopping is a REQUEST, not a kill: WinSW sends Ctrl+C and waits, so the
>>"%SVC_XML%" echo      shutdown hook can close the audio lines and park a QA40x analyzer. --^>
>>"%SVC_XML%" echo ^<service^>
>>"%SVC_XML%" echo     ^<id^>%SERVICE_ID%^</id^>
>>"%SVC_XML%" echo     ^<name^>Phonalyser server^</name^>
>>"%SVC_XML%" echo     ^<description^>Serves this machine's sound cards and QA40x analyzer to Phonalyser clients over the network.^</description^>
>>"%SVC_XML%" echo(
>>"%SVC_XML%" echo     ^<executable^>!SVC_JAVA!^</executable^>
>>"%SVC_XML%" echo     ^<arguments^>!SVC_OPTS! -jar "!SVC_JAR!"!SVC_ARGS!^</arguments^>
>>"%SVC_XML%" echo     ^<workingdirectory^>!SVC_HERE!^</workingdirectory^>
>>"%SVC_XML%" echo(
>>"%SVC_XML%" echo     ^<startmode^>Automatic^</startmode^>
>>"%SVC_XML%" echo     ^<onfailure action="restart" delay="10 sec"/^>
>>"%SVC_XML%" echo     ^<^^!-- Longer than the server's own teardown budget: cutting the teardown
>>"%SVC_XML%" echo          short is exactly what leaves an analyzer unparked. --^>
>>"%SVC_XML%" echo     ^<stoptimeout^>20 sec^</stoptimeout^>
>>"%SVC_XML%" echo(
>>"%SVC_XML%" echo     ^<^^!-- WinSW's capture of the service console and its start/stop record.
>>"%SVC_XML%" echo          The application's own log4j file lands where AppPaths resolves. --^>
>>"%SVC_XML%" echo     ^<logpath^>!SVC_LOGDIR!^</logpath^>
>>"%SVC_XML%" echo     ^<log mode="roll-by-size"^>
>>"%SVC_XML%" echo         ^<sizeThreshold^>10240^</sizeThreshold^>
>>"%SVC_XML%" echo         ^<keepFiles^>4^</keepFiles^>
>>"%SVC_XML%" echo     ^</log^>
>>"%SVC_XML%" echo(!SVC_ACCT!
>>"%SVC_XML%" echo ^</service^>
endlocal
if not exist "%SVC_XML%" (
  echo Could not write %SVC_XML%.
  exit /b 1
)

if not exist "%PROGDATA%" mkdir "%PROGDATA%"
if not exist "%SVC_LOGDIR%" mkdir "%SVC_LOGDIR%"

rem WinSW writes its own log there whatever account it runs as, and a directory
rem an administrator created is not writable by an ordinary account by default.
if defined ACCOUNT icacls "%PROGDATA%" /grant "%ACCOUNT%":(OI)(CI)M >nul

rem --- install ----------------------------------------------------------------
"%WINSW%" install
if errorlevel 1 (
  echo WinSW could not install the service - nothing was registered.
  exit /b 1
)

echo Installed: service "%SERVICE_ID%" runs from %SVC_HERE%
echo Do not move or delete that folder - the service points straight at it.

if defined ACCOUNT (
  echo.
  echo The service is registered to run as %ACCOUNT%, but Windows still needs
  echo that account's PASSWORD before it can start:
  echo   1. run services.msc
  echo   2. Phonalyser server ^> Properties ^> Log On ^> This account
  echo   3. type the password, press OK
  echo   4. press Start
) else (
  "%WINSW%" start
  if errorlevel 1 (
    echo The service was installed but did not start - see %SVC_LOGDIR%.
  ) else (
    echo Started it now.  Open http://localhost:8377/info to check.
  )
)
echo.
echo   start it   : sc start %SERVICE_ID%
echo   stop it    : sc stop %SERVICE_ID%     ^(graceful - the analyzer is parked^)
echo   remove it  : uninstall-service.cmd
echo   logs       : %SVC_LOGDIR%
