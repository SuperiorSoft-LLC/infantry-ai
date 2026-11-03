@echo off
rem Infantry AI CLI launcher shipped with the INSTALLED app (CLIPATH-2).
rem jpackage layout: <install>\app\resources\infantry.cmd (this file), <install>\app\*.jar (the app classpath,
rem :infantry included), <install>\runtime (the bundled runtime). The daemon puts this directory first on every
rem session's PATH and exports it as INFANTRY_BIN.
rem java: INFANTRY_JAVA, then the bundled runtime's java.exe, then JAVA_HOME, then java on PATH.
setlocal
set "INFANTRY_APP_DIR=%~dp0.."
set "INFANTRY_CLI_JAVA="
if defined INFANTRY_JAVA if exist "%INFANTRY_JAVA%" set "INFANTRY_CLI_JAVA=%INFANTRY_JAVA%"
if not defined INFANTRY_CLI_JAVA if exist "%~dp0..\..\runtime\bin\java.exe" set "INFANTRY_CLI_JAVA=%~dp0..\..\runtime\bin\java.exe"
if not defined INFANTRY_CLI_JAVA if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "INFANTRY_CLI_JAVA=%JAVA_HOME%\bin\java.exe"
if not defined INFANTRY_CLI_JAVA set "INFANTRY_CLI_JAVA=java"
"%INFANTRY_CLI_JAVA%" --add-opens=java.base/java.util=ALL-UNNAMED -cp "%INFANTRY_APP_DIR%\*" com.superiorsoftllc.infantry.cli.CliMainKt %*
exit /b %ERRORLEVEL%
