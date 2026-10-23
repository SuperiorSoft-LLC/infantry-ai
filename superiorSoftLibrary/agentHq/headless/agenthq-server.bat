@echo off
rem Infantry AI headless daemon (no UI). Java: the bundled runtime\ first, then JAVA_HOME, then java on PATH (21+).
rem Configure with AGENTHQ_* environment variables (README.txt next to bin\); extra JVM flags go in AGENTHQ_JAVA_OPTS.
setlocal
set "APP_HOME=%~dp0.."
if exist "%APP_HOME%\runtime\bin\java.exe" (
    set "JAVA=%APP_HOME%\runtime\bin\java.exe"
) else if defined JAVA_HOME (
    set "JAVA=%JAVA_HOME%\bin\java.exe"
) else (
    set "JAVA=java.exe"
)
rem Sessions run `agenthq ...`: the daemon puts this bin dir on their PATH.
if not defined AGENTHQ_BIN set "AGENTHQ_BIN=%APP_HOME%\bin"
"%JAVA%" -Xms32m -Xmx512m -Djava.awt.headless=true %AGENTHQ_JAVA_OPTS% -cp "%APP_HOME%\lib\*" com.superiorsoftllc.agenthq.daemon.DaemonMainKt %*
exit /b %ERRORLEVEL%
