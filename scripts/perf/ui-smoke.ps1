# SPDX-License-Identifier: Apache-2.0
# Copyright (c) SuperiorSoft LLC
# RUST-PERF-1: starts the Tauri app (clean env, temp state, a throwaway project folder, WebView2 DevTools on a 189xx
# port) and runs ui-smoke.mjs against it: new agent, turn, interrupt, a second agent, resume. Kills it by PID after.
param(
    [int]$DevtoolsPort = 18930,
    [string]$TauriExe = "$PSScriptRoot\..\..\app\src-tauri\target\release\infantry-tauri.exe",
    # RUST-DAEMON-2: `daemon` = the app starts its infantry-daemon sidecar on $DaemonPort; `embedded` = the in-app runner.
    [ValidateSet('embedded', 'daemon')][string]$Mode = 'daemon',
    [int]$DaemonPort = 18962,
    [string]$DaemonExe = "$PSScriptRoot\..\..\rust\target\release\infantry-daemon.exe"
)
$ErrorActionPreference = 'Stop'
foreach ($p in @($DevtoolsPort, $DaemonPort)) { if ($p -lt 18900 -or $p -gt 18999) { throw "test ports are 189xx only" } }
. "$PSScriptRoot\lib.ps1"
$root = Join-Path ([IO.Path]::GetTempPath()) ("infantry-ui-smoke-" + [Guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Force "$root\work", "$root\state" | Out-Null
$extra = @{
    INFANTRY_STATE_DIR = "$root\state"
    WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS = "--remote-debugging-port=$DevtoolsPort"
}
if ($Mode -eq 'embedded') { $extra.INFANTRY_UI_LOCAL = '1' } else {
    $extra.INFANTRY_UI_LOCAL = '0'; $extra.INFANTRY_UI_DAEMON_PORT = "$DaemonPort"; $extra.INFANTRY_DAEMON_BIN = (Resolve-Path $DaemonExe).Path
}
$app = Start-Clean -Exe (Resolve-Path $TauriExe).Path -Cwd "$root\work" -Extra $extra
try {
    node "$PSScriptRoot\ui-smoke.mjs" $DevtoolsPort "$root\work" $Mode
    $code = $LASTEXITCODE
} finally {
    Stop-Tree -Root $app.Id
    Start-Sleep -Seconds 2
    Remove-Item -Recurse -Force $root -ErrorAction SilentlyContinue
}
exit $code
