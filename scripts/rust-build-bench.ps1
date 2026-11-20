# SPDX-License-Identifier: Apache-2.0
# Copyright (c) SuperiorSoft LLC
# BUILD-SPEED-1 (#78): measures ONE build or test command and appends a CSV row: wall time, peak RAM of the processes
# the command started (working set and private bytes, summed over every new process, sampled), the machine's lowest
# available RAM during the run, and what a folder gained on disk. Use it to compare a build setting before and after.
#   scripts/rust-build-bench.ps1 -Name dev-clean -Command 'cargo build -p infantry-daemon' -Cwd rust -SizeDir <target>
# The command inherits this shell's environment (CARGO_TARGET_DIR, RUSTC_WRAPPER...): set those first.
# AGENTS.md rule 7: it first waits until no other cargo or rustc runs on the machine (-IdleWaitSeconds, default 1 h).
param(
    [Parameter(Mandatory)][string]$Name,
    [Parameter(Mandatory)][string]$Command,
    [string]$Cwd = (Get-Location).Path,
    [string]$SizeDir = '',
    [string]$Csv = (Join-Path $env:TEMP 'infantry-build-bench.csv'),
    [string]$Log = '',
    [int]$IdleWaitSeconds = 3600,
    [int]$SampleMs = 500
)
$ErrorActionPreference = 'Stop'
if (-not ('InfantryBenchSampler' -as [type])) {
    Add-Type -TypeDefinition @'
using System; using System.Collections.Generic; using System.Diagnostics; using System.Runtime.InteropServices; using System.Threading;
public class InfantryBenchSampler {
    [StructLayout(LayoutKind.Sequential)] struct MemStatus { public uint Length, Load; public ulong TotalPhys, AvailPhys, TotalPage, AvailPage, TotalVirt, AvailVirt, AvailExt; }
    [DllImport("kernel32.dll")] static extern bool GlobalMemoryStatusEx(ref MemStatus m);
    public static ulong AvailPhys() { var m = new MemStatus(); m.Length = (uint)Marshal.SizeOf(typeof(MemStatus)); GlobalMemoryStatusEx(ref m); return m.AvailPhys; }
    HashSet<int> before = new HashSet<int>(); Thread worker; volatile bool stop;
    public long PeakWs, PeakPriv; public int PeakProcs; public ulong StartAvail, MinAvail = ulong.MaxValue;
    public void Start(int ms) {
        foreach (var p in Process.GetProcesses()) { before.Add(p.Id); p.Dispose(); }
        StartAvail = AvailPhys();
        worker = new Thread(() => { while (!stop) { Sample(); Thread.Sleep(ms); } }); worker.IsBackground = true; worker.Start();
    }
    void Sample() {
        long ws = 0, pr = 0; int n = 0;
        foreach (var p in Process.GetProcesses()) {
            try { if (!before.Contains(p.Id)) { ws += p.WorkingSet64; pr += p.PrivateMemorySize64; n++; } } catch { } finally { p.Dispose(); }
        }
        if (ws > PeakWs) PeakWs = ws; if (pr > PeakPriv) PeakPriv = pr; if (n > PeakProcs) PeakProcs = n;
        var a = AvailPhys(); if (a < MinAvail) MinAvail = a;
    }
    public void Stop() { stop = true; worker.Join(); Sample(); }
}
'@
}
function Get-DirBytes([string]$p) {
    if (-not $p -or -not (Test-Path $p)) { return 0 }
    $s = [long]0
    foreach ($f in [IO.Directory]::EnumerateFiles($p, '*', [IO.SearchOption]::AllDirectories)) { try { $s += ([IO.FileInfo]$f).Length } catch { } }
    $s
}
function Test-BuildRunning { @(Get-Process -Name cargo, rustc -ErrorAction SilentlyContinue).Count -gt 0 }

$waited = 0
while ((Test-BuildRunning) -and $waited -lt $IdleWaitSeconds) {
    if ($waited % 60 -eq 0) { Write-Host "[$Name] another cargo/rustc is running; waiting (rule 7)" }
    Start-Sleep -Seconds 5; $waited += 5
}
if (Test-BuildRunning) { throw "[$Name] another build is still running after $IdleWaitSeconds s; not measured" }

if (-not $Log) { $Log = Join-Path $env:TEMP ("infantry-bench-{0}.log" -f ($Name -replace '[^\w.-]', '_')) }
$diskBefore = Get-DirBytes $SizeDir
$sampler = [InfantryBenchSampler]::new()
$sampler.Start($SampleMs)
$sw = [Diagnostics.Stopwatch]::StartNew()
Push-Location $Cwd
try {
    & cmd.exe /d /s /c "$Command > `"$Log`" 2>&1"
    $exit = $LASTEXITCODE
} finally { Pop-Location }
$sw.Stop()
$sampler.Stop()
$diskAfter = Get-DirBytes $SizeDir
$gb = { param($b) [math]::Round($b / 1GB, 2) }
$row = [pscustomobject]@{
    date          = (Get-Date).ToString('s')
    name          = $Name
    exit          = $exit
    wall_s        = [math]::Round($sw.Elapsed.TotalSeconds, 1)
    peak_ws_gb    = & $gb $sampler.PeakWs
    peak_priv_gb  = & $gb $sampler.PeakPriv
    peak_procs    = $sampler.PeakProcs
    start_avail_gb = & $gb $sampler.StartAvail
    min_avail_gb  = & $gb $sampler.MinAvail
    disk_added_gb = if ($SizeDir) { & $gb ($diskAfter - $diskBefore) } else { '' }
    disk_total_gb = if ($SizeDir) { & $gb $diskAfter } else { '' }
    waited_s      = $waited
    command       = $Command
}
$row | Export-Csv -Path $Csv -Append -NoTypeInformation
$row
if ($exit -ne 0) { Write-Host "[$Name] FAILED (exit $exit); last lines of $($Log):"; Get-Content $Log -Tail 15 }
