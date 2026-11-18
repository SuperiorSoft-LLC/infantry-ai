# SPDX-License-Identifier: Apache-2.0
# Copyright (c) SuperiorSoft LLC
# RUST-PERF-1: starts the Tauri app (clean env, temp state, a throwaway project folder, WebView2 DevTools on a 189xx
# port) and runs ui-smoke.mjs against it: new agent, turn, interrupt, a second agent, resume. Kills it by PID after.
param(
    [int]$DevtoolsPort = 18930,
    [string]$TauriExe = "$PSScriptRoot\..\..\app\src-tauri\target\release\infantry-tauri.exe"
)
$ErrorActionPreference = 'Stop'
if ($DevtoolsPort -lt 18900 -or $DevtoolsPort -gt 18999) { throw "test ports are 189xx only" }
. "$PSScriptRoot\lib.ps1"
$root = Join-Path ([IO.Path]::GetTempPath()) ("infantry-ui-smoke-" + [Guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Force "$root\work", "$root\state" | Out-Null
$app = Start-Clean -Exe (Resolve-Path $TauriExe).Path -Cwd "$root\work" -Extra @{
    INFANTRY_UI_LOCAL = '1'; INFANTRY_STATE_DIR = "$root\state"
    WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS = "--remote-debugging-port=$DevtoolsPort"
}
try {
    node "$PSScriptRoot\ui-smoke.mjs" $DevtoolsPort "$root\work"
    $code = $LASTEXITCODE
} finally {
    Stop-Tree -Root $app.Id
    Start-Sleep -Seconds 2
    Remove-Item -Recurse -Force $root -ErrorAction SilentlyContinue
}
exit $code
