@echo off
rem Infantry AI server launcher and updater (SERVER-UPDATE-2); the Windows twin of the `infantry` shell script.
rem   infantry [serve] [daemon args]   run the daemon under a supervisor: an update restarts it into the new version
rem   infantry update [--check-only] [--yes]   check for a newer release, show the notes, install it
rem   infantry rollback                switch back to the version before the current one
rem   infantry version
rem The install is <root>\versions\<x.y.z>\ plus <root>\current (the version to start) and <root>\previous (for rollback).
rem This file is also copied to <root>\infantry.cmd; there it only hands over to the current version's copy.
setlocal EnableDelayedExpansion
set "SELF_DIR=%~dp0"
if "%SELF_DIR:~-1%"=="\" set "SELF_DIR=%SELF_DIR:~0,-1%"

rem Root stub: <root>\infantry.cmd reads <root>\current and hands over.
if exist "%SELF_DIR%\versions\" if exist "%SELF_DIR%\current" (
    set /p CUR=<"%SELF_DIR%\current"
    if not exist "%SELF_DIR%\versions\!CUR!\bin\infantry.cmd" (
        echo infantry: version !CUR! named in %SELF_DIR%\current is not installed 1>&2
        exit /b 1
    )
    set "AGENTHQ_INSTALL_ROOT=%SELF_DIR%"
    call "%SELF_DIR%\versions\!CUR!\bin\infantry.cmd" %*
    exit /b !ERRORLEVEL!
)

for %%I in ("%SELF_DIR%\..") do set "APP_HOME=%%~fI"
if exist "%APP_HOME%\runtime\bin\java.exe" (
    set "JAVA=%APP_HOME%\runtime\bin\java.exe"
) else if defined JAVA_HOME (
    set "JAVA=%JAVA_HOME%\bin\java.exe"
) else (
    set "JAVA=java.exe"
)
set CLI="%JAVA%" -Xmx256m -Djava.awt.headless=true -cp "%APP_HOME%\lib\*" com.superiorsoftllc.agenthq.update.ServerUpdateCliKt

rem A freshly unpacked archive: copy it to <parent>\versions\<v>, then run from there.
for %%I in ("%APP_HOME%\..") do set "PARENT=%%~fI"
for %%I in ("%PARENT%") do set "PARENT_NAME=%%~nxI"
if /i not "%PARENT_NAME%"=="versions" (
    set "AGENTHQ_APP_HOME=%APP_HOME%"
    %CLI% adopt "%APP_HOME%"
    if errorlevel 1 exit /b 1
    call "%PARENT%\infantry.cmd" %*
    exit /b !ERRORLEVEL!
)

for %%I in ("%PARENT%\..") do set "ROOT=%%~fI"
set "AGENTHQ_INSTALL_ROOT=%ROOT%"
set "AGENTHQ_APP_HOME=%APP_HOME%"

if /i "%~1"=="update" goto :cli
if /i "%~1"=="rollback" goto :cli
if /i "%~1"=="version" ( type "%APP_HOME%\VERSION" & exit /b 0 )
if /i "%~1"=="--version" ( type "%APP_HOME%\VERSION" & exit /b 0 )
set "ARGS=%*"
if /i "%~1"=="serve" call :dropfirst
goto :serve

:cli
%CLI% %*
exit /b %ERRORLEVEL%

:dropfirst
set "ARGS="
shift
:dropnext
if "%~1"=="" exit /b 0
set "ARGS=!ARGS! %1"
shift
goto :dropnext

rem Supervisor: run the version named by <root>\current; exit code 75 means "start again" (an update was staged).
rem A new version that dies within 30 seconds of the switch is rolled back, once.
:serve
set "LAST="
set "ROLLED_BACK=0"
:again
set /p CUR=<"%ROOT%\current"
set "DIR=%ROOT%\versions\!CUR!"
call :now START
set "AGENTHQ_SUPERVISED=1"
set "AGENTHQ_APP_HOME=!DIR!"
call "!DIR!\bin\agenthq-server.bat" !ARGS!
set "CODE=!ERRORLEVEL!"
if "!CODE!"=="75" (
    echo [infantry] restarting into the staged version 1>&2
    set "LAST=!CUR!"
    goto :again
)
call :now STOP
set /a ELAPSED=STOP-START
if not "!CODE!"=="0" if !ELAPSED! LSS 30 if "!ROLLED_BACK!"=="0" if defined LAST if not "!LAST!"=="!CUR!" if exist "%ROOT%\previous" (
    echo [infantry] !CUR! exited !CODE! within 30s of the update; rolling back 1>&2
    %CLI% rollback
    if not errorlevel 1 (
        set "ROLLED_BACK=1"
        set "LAST=!CUR!"
        goto :again
    )
)
exit /b !CODE!

:now
for /f %%t in ('powershell -NoLogo -NoProfile -Command "[DateTimeOffset]::UtcNow.ToUnixTimeSeconds()"') do set "%~1=%%t"
exit /b 0
