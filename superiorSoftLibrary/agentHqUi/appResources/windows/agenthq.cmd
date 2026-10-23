@echo off
rem Infantry AI CLI launcher shipped with the INSTALLED app (CLIPATH-2).
rem jpackage layout: <install>\app\resources\agenthq.cmd (this file), <install>\app\*.jar (the app classpath,
rem :agentHq included), <install>\runtime (the bundled runtime). The daemon puts this directory first on every
rem session's PATH and exports it as AGENTHQ_BIN.
rem java: AGENTHQ_JAVA, then the bundled runtime's java.exe, then JAVA_HOME, then java on PATH.
setlocal
set "AGENTHQ_APP_DIR=%~dp0.."
set "AGENTHQ_CLI_JAVA="
if defined AGENTHQ_JAVA if exist "%AGENTHQ_JAVA%" set "AGENTHQ_CLI_JAVA=%AGENTHQ_JAVA%"
if not defined AGENTHQ_CLI_JAVA if exist "%~dp0..\..\runtime\bin\java.exe" set "AGENTHQ_CLI_JAVA=%~dp0..\..\runtime\bin\java.exe"
if not defined AGENTHQ_CLI_JAVA if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "AGENTHQ_CLI_JAVA=%JAVA_HOME%\bin\java.exe"
if not defined AGENTHQ_CLI_JAVA set "AGENTHQ_CLI_JAVA=java"
"%AGENTHQ_CLI_JAVA%" -cp "%AGENTHQ_APP_DIR%\*" com.superiorsoftllc.agenthq.cli.CliMainKt %*
exit /b %ERRORLEVEL%
