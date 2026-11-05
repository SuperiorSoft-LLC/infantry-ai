@echo off
rem SPDX-License-Identifier: Apache-2.0
rem SHELL-SHOW-1: double-click to see the empty shell. Pass "root" for the shell + the plugin manager only.
rem   show-empty-shell.cmd          -> no plugins
rem   show-empty-shell.cmd root     -> shell + Infantry_Settings (the plugin manager screen)
set MODE=%1
if "%MODE%"=="" set MODE=empty
set PS=powershell
where pwsh >nul 2>nul
if not errorlevel 1 set PS=pwsh
%PS% -NoProfile -ExecutionPolicy Bypass -File "%~dp0show-empty-shell.ps1" -Mode %MODE%
pause
