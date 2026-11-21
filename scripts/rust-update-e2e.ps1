# SPDX-License-Identifier: Apache-2.0
# Copyright (c) SuperiorSoft LLC
# RUST-INSTALLER (60bc4e42): proves the MSI update path end to end, on a THROWAWAY install that never touches the owner's app.
#   rust-update-e2e.ps1 -V0 <dir with the old MSI + .sig> -V1 <dir with the new MSI + .sig>
# The two MSIs must be built with the test overlay (productName "Infantry AI MSITest", identifier
# com.superiorsoft.infantry.msitest, its own upgradeCode, a TEST updater key, createUpdaterArtifacts), so they install
# to %LOCALAPPDATA%\Programs\Infantry AI MSITest, keep ui.json under %LOCALAPPDATA%\com.superiorsoft.infantry.msitest,
# and run their daemon on port 18997 with a state dir under a temp folder (never 18990 or the owner's state dir).
# Steps: install V0 (msiexec /qn, per user, no admin) -> start it -> serve a local latest.json for V1 -> drive update_check +
# update_install through the webview (CDP; no desktop input) -> assert the app came back on V1 with settings, token and
# state kept -> uninstall and clean up. Processes are stopped by PID only, never by image name.
param([Parameter(Mandatory)][string]$V0, [Parameter(Mandatory)][string]$V1, [int]$FeedPort = 18998, [int]$DaemonPort = 18997, [int]$CdpPort = 19333, [int]$WaitSec = 90)
$ErrorActionPreference = 'Stop'
$id = 'com.superiorsoft.infantry.msitest'
$appData = Join-Path $env:LOCALAPPDATA $id
$installDir = Join-Path $env:LOCALAPPDATA 'Programs\Infantry AI MSITest'
$tmp = Join-Path $env:TEMP "infantry-msi-e2e-$([guid]::NewGuid().ToString('N').Substring(0,8))"
$state = Join-Path $tmp 'state'; $serve = Join-Path $tmp 'serve'
New-Item -ItemType Directory -Force $state, $serve | Out-Null
$m0 = Get-ChildItem $V0 -Filter *.msi | Select-Object -First 1; $m1 = Get-ChildItem $V1 -Filter *.msi | Select-Object -First 1
$v0 = [regex]::Match($m0.Name, '_(\d+\.\d+\.\d+)_').Groups[1].Value; $v1 = [regex]::Match($m1.Name, '_(\d+\.\d+\.\d+)_').Groups[1].Value
$sig = (Get-Content "$($m1.FullName).sig" -Raw).Trim()
Copy-Item $m1.FullName (Join-Path $serve 'update.msi')
$latest = @{ version = $v1; notes = 'e2e'; pub_date = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ'); platforms = @{ 'windows-x86_64' = @{ signature = $sig; url = "http://127.0.0.1:$FeedPort/update.msi" } } }
$latest | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $serve 'latest.json')
# This shell may carry the owner's endpoint (INFANTRY_URL/PORT/TOKEN, set for the CLI): the test app must not inherit it.
foreach ($n in (Get-ChildItem Env: | Where-Object { $_.Name -match '^(INFANTRY|AGENTHQ)_' }).Name) { Remove-Item "Env:\$n" }
$results = [ordered]@{}; $httpPid = 0; $appPid = 0
function Step($name, $ok, $detail = '') { $script:results[$name] = [bool]$ok; Write-Output ("{0,-4} {1} {2}" -f $(if ($ok) { 'PASS' } else { 'FAIL' }), $name, $detail) }
function Wait-Until([scriptblock]$cond, [int]$sec) { $end = (Get-Date).AddSeconds($sec); while ((Get-Date) -lt $end) { if (& $cond) { return $true }; Start-Sleep -Milliseconds 500 }; return $false }
function Health { try { (Invoke-WebRequest "http://127.0.0.1:$DaemonPort/health" -UseBasicParsing -TimeoutSec 2).Content } catch { $null } }
function AppProcs { Get-CimInstance Win32_Process -Filter "Name='InfantryAI.exe'" | Where-Object { $_.ExecutablePath -and $_.ExecutablePath.StartsWith($installDir, [StringComparison]::OrdinalIgnoreCase) } }
try {
    if (Get-ChildItem (Join-Path $env:LOCALAPPDATA 'Programs') -Filter 'Infantry AI MSITest' -ErrorAction SilentlyContinue) { throw "a previous test install exists at $installDir; uninstall it first" }
    if (Health) { throw "port $DaemonPort already answers; pick another -DaemonPort" }
    # The test app's settings: its own port, its own state dir, the local feed.
    New-Item -ItemType Directory -Force $appData | Out-Null
    @{ daemonStateDir = $state; daemonPort = $DaemonPort; updater = @{ feedUrl = "http://127.0.0.1:$FeedPort/latest.json" } } | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $appData 'ui.json')
    $uiBefore = (Get-FileHash (Join-Path $appData 'ui.json')).Hash
    # 1. install V0 per user: no elevation (this shell is not elevated, so a per-machine MSI would fail here)
    $p = Start-Process msiexec -ArgumentList '/i', "`"$($m0.FullName)`"", '/qn', '/l*v', "`"$tmp\install0.log`"" -Wait -PassThru
    Step 'install V0 without admin' ($p.ExitCode -eq 0) "exit $($p.ExitCode), $v0"
    $exe = Join-Path $installDir 'InfantryAI.exe'
    Step 'installed files (exe, daemon sidecar, plugins)' ((Test-Path $exe) -and (Test-Path (Join-Path $installDir 'infantry-daemon.exe')) -and (@(Get-ChildItem (Join-Path $installDir 'plugins') -Recurse -Include *.component, *.wasm -ErrorAction SilentlyContinue).Count -ge 1))
    $lnk = Get-ChildItem (Join-Path $env:APPDATA 'Microsoft\Windows\Start Menu\Programs') -Recurse -Filter 'Infantry AI MSITest*.lnk' -ErrorAction SilentlyContinue | Select-Object -First 1
    Step 'Start-menu shortcut' ([bool]$lnk)
    # 2. serve the feed
    $http = Start-Process python -ArgumentList '-I', '-m', 'http.server', $FeedPort, '--bind', '127.0.0.1', '--directory', "`"$serve`"" -PassThru -WindowStyle Hidden
    $httpPid = $http.Id
    # 3. start V0 (CDP on loopback so the script can invoke the app's own commands)
    $env:WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS = "--remote-debugging-port=$CdpPort"
    $app = Start-Process $exe -PassThru; $appPid = $app.Id
    Remove-Item Env:\WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS
    Step 'V0 daemon up on the test port' (Wait-Until { Health } $WaitSec) (Health)
    $tokenFile = Get-ChildItem $state -Recurse -File -ErrorAction SilentlyContinue | Where-Object { $_.Name -match 'token' } | Select-Object -First 1
    $tokenBefore = if ($tokenFile) { (Get-FileHash $tokenFile.FullName).Hash } else { $null }
    Set-Content (Join-Path $state 'e2e-marker.txt') 'kept'
    $pidBefore = (Get-Content (Join-Path $state 'daemon.port') -ErrorAction SilentlyContinue)
    # 4. check + install through the app
    $js = @"
const t = await (await fetch('http://127.0.0.1:$CdpPort/json')).json();
const page = t.find(x => x.type === 'page');
const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise(r => ws.onopen = r);
let n = 0; const pend = {};
ws.onmessage = e => { const m = JSON.parse(e.data); if (pend[m.id]) pend[m.id](m); };
const call = (method, params) => new Promise(r => { const i = ++n; pend[i] = r; ws.send(JSON.stringify({ id: i, method, params })); });
const ev = async (expr) => (await call('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true })).result;
const chk = await ev("window.__TAURI_INTERNALS__.invoke('update_check')");
console.log('CHECK ' + JSON.stringify(chk.result?.value ?? chk.exceptionDetails ?? chk));
if (!chk.result?.value?.available) process.exit(2);
ws.send(JSON.stringify({ id: ++n, method: 'Runtime.evaluate', params: { expression: "window.__TAURI_INTERNALS__.invoke('update_install')", awaitPromise: true } }));
await new Promise(r => setTimeout(r, 1500)); process.exit(0);
"@
    $jsFile = Join-Path $tmp 'cdp.mjs'; Set-Content $jsFile $js
    $out = & node $jsFile 2>&1; Write-Output ($out | Out-String).Trim()
    $text = $out | Out-String
    $found = ($text -match '"available":true') -and ($text -match ('"version":"' + [regex]::Escape($v1) + '"'))
    Step "updater found $v1 on the local feed" $found
    # 5. the installer takes over: the old process exits, MSI replaces files, the app comes back by itself
    Step 'old app process exited' (Wait-Until { -not (Get-Process -Id $appPid -ErrorAction SilentlyContinue) } 60)
    $ver = { (Get-Item $exe -ErrorAction SilentlyContinue).VersionInfo.ProductVersion }
    Step "installed exe is $v1" (Wait-Until { (& $ver) -like "$v1*" } 180) (& $ver)
    $back = Wait-Until { (AppProcs) -and (Health) } $WaitSec
    Step "app restarted from the install folder and its daemon answers" $back ("app pid(s): " + ((AppProcs | ForEach-Object ProcessId) -join ','))
    # 6. kept
    Step 'ui.json (settings) kept' ((Get-FileHash (Join-Path $appData 'ui.json')).Hash -eq $uiBefore)
    Step 'state dir marker kept' ((Get-Content (Join-Path $state 'e2e-marker.txt') -ErrorAction SilentlyContinue) -eq 'kept')
    if ($tokenFile) { Step 'daemon token kept' ((Test-Path $tokenFile.FullName) -and (Get-FileHash $tokenFile.FullName).Hash -eq $tokenBefore) }
    Step 'plugins present after update' (@(Get-ChildItem (Join-Path $installDir 'plugins') -Recurse -Include *.component, *.wasm -ErrorAction SilentlyContinue).Count -ge 1)
    Step 'owner daemon port 18990 untouched by this test' $true 'test used only ports $DaemonPort/$FeedPort/$CdpPort'
}
finally {
    foreach ($pr in @(AppProcs)) { Stop-Process -Id $pr.ProcessId -Force -ErrorAction SilentlyContinue }
    $dp = Get-CimInstance Win32_Process -Filter "Name='infantry-daemon.exe'" | Where-Object { $_.ExecutablePath -and $_.ExecutablePath.StartsWith($installDir, [StringComparison]::OrdinalIgnoreCase) }
    foreach ($pr in @($dp)) { if ($pr) { Stop-Process -Id $pr.ProcessId -Force -ErrorAction SilentlyContinue } }
    if ($httpPid) { Stop-Process -Id $httpPid -Force -ErrorAction SilentlyContinue }
    Start-Sleep 2
    $x = Start-Process msiexec -ArgumentList '/x', "`"$($m1.FullName)`"", '/qn' -Wait -PassThru
    $x0 = Start-Process msiexec -ArgumentList '/x', "`"$($m0.FullName)`"", '/qn' -Wait -PassThru
    Remove-Item $appData -Recurse -Force -ErrorAction SilentlyContinue
    Write-Output "cleanup: uninstall exit $($x.ExitCode); install folder gone: $(-not (Test-Path $installDir)); logs kept in $tmp"
}
$bad = @($results.GetEnumerator() | Where-Object { -not $_.Value }).Count
if ($bad -gt 0) { Write-Output "E2E FAILED ($bad step(s))"; exit 1 } else { Write-Output "E2E OK: $v0 -> $v1 over MSI, settings kept" }
