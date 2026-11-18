# SPDX-License-Identifier: Apache-2.0
# Copyright (c) SuperiorSoft LLC
# RUST-PERF-1: the per-agent MCP bridge, JVM vs native. Every Kotlin agent session starts its own `infantry mcp serve`
# (a JVM today). This starts a SEPARATE Kotlin instance (189xx port, temp state), then [Count] bridges of each kind
# against it, each doing an MCP initialize + tools/list, and reports time to the tools/list answer and memory per bridge.
#   pwsh scripts/perf/bridges.ps1 [-Count 3]
param(
    [int]$Count = 3,
    [int]$Port = 18912,
    [string]$KotlinDir = 'C:\Users\antho\agenthq-dist\builds\20261117-181728-6afaed5e',
    [string]$NativeExe = "$PSScriptRoot\..\..\rust\target\release\infantry.exe",
    [string]$Out = "$PSScriptRoot\bridges.json"
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\lib.ps1"
if ($Port -in 8787, 8788, 8797, 8798 -or $Port -lt 18900 -or $Port -gt 18999) { throw "test ports are 189xx only" }
$NativeExe = (Resolve-Path $NativeExe).Path
$jvmCmd = Join-Path $KotlinDir 'app\resources\infantry.cmd'

$root = Join-Path ([IO.Path]::GetTempPath()) ("infantry-perf-bridges-" + [Guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Force "$root\work", "$root\state" | Out-Null
$app = Start-Clean -Exe (Join-Path $KotlinDir 'InfantryAI.exe') -Cwd "$root\work" -Extra @{ INFANTRY_INSTALL_PORT = $Port; INFANTRY_INSTALL_STATE_DIR = "$root\state" }
$bridges = @()
$result = [ordered]@{}
try {
    $ok = $false
    for ($i = 0; $i -lt 120 -and -not $ok; $i++) {
        Start-Sleep -Milliseconds 500
        try { $ok = (Invoke-WebRequest "http://127.0.0.1:$Port/health" -TimeoutSec 1 -UseBasicParsing).StatusCode -eq 200 } catch {}
    }
    if (-not $ok) { throw "the test daemon did not come up" }
    $init = '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"perf","version":"1"}}}'
    $inited = '{"jsonrpc":"2.0","method":"notifications/initialized"}'
    $list = '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'
    foreach ($kind in 'native', 'jvm') {
        $times = @(); $mine = @()
        for ($k = 0; $k -lt $Count; $k++) {
            $psi = if ($kind -eq 'native') { [System.Diagnostics.ProcessStartInfo]::new($NativeExe) } else { [System.Diagnostics.ProcessStartInfo]::new('cmd.exe') }
            if ($kind -eq 'jvm') { foreach ($a in '/c', $jvmCmd) { $psi.ArgumentList.Add($a) } }
            foreach ($a in 'mcp', 'serve') { $psi.ArgumentList.Add($a) }
            $psi.UseShellExecute = $false; $psi.RedirectStandardInput = $true; $psi.RedirectStandardOutput = $true; $psi.RedirectStandardError = $true
            $psi.WorkingDirectory = "$root\work"
            foreach ($key in @($psi.Environment.Keys)) { if ($key -match '^(INFANTRY_|AGENTHQ_|CLAUDE)') { [void]$psi.Environment.Remove($key) } }
            $psi.Environment['INFANTRY_URL'] = "ws://127.0.0.1:$Port/ws"
            $psi.Environment['INFANTRY_STATE_DIR'] = "$root\state"
            $sw = [Diagnostics.Stopwatch]::StartNew()
            $p = [System.Diagnostics.Process]::Start($psi)
            $bridges += $p; $mine += $p
            $p.StandardInput.WriteLine($init); $p.StandardInput.WriteLine($inited); $p.StandardInput.WriteLine($list); $p.StandardInput.Flush()
            $answered = $null
            $task = $p.StandardOutput.ReadLineAsync()
            while ($sw.ElapsedMilliseconds -lt 60000) {
                if ($task.Wait(200)) {
                    $line = $task.Result
                    if ($null -eq $line) { break }
                    if ($line -match '"id":\s*2') { $answered = $sw.ElapsedMilliseconds; break }
                    $task = $p.StandardOutput.ReadLineAsync()
                }
            }
            $times += $answered
        }
        Start-Sleep -Seconds 5
        $ws = 0L; $priv = 0L; $n = 0
        foreach ($p in $mine) {
            foreach ($t in (Get-Tree -Root $p.Id)) {
                $gp = Get-Process -Id $t.ProcessId -ErrorAction SilentlyContinue
                if ($gp) { $ws += $gp.WorkingSet64; $priv += $gp.PrivateMemorySize64; $n++ }
            }
        }
        $result[$kind] = [ordered]@{
            bridges = $Count; processes = $n
            toolsListMs = $times
            wsMbPerBridge = [math]::Round($ws / $Count / 1MB, 1)
            privMbPerBridge = [math]::Round($priv / $Count / 1MB, 1)
        }
        Write-Host "$kind : $($result[$kind] | ConvertTo-Json -Compress)"
    }
} finally {
    foreach ($p in $bridges) { Stop-Tree -Root $p.Id }
    Stop-Tree -Root $app.Id
    Start-Sleep -Seconds 2
    Remove-Item -Recurse -Force $root -ErrorAction SilentlyContinue
}
$result | ConvertTo-Json -Depth 4 | Set-Content -Encoding utf8 $Out
Write-Host "results: $Out"
