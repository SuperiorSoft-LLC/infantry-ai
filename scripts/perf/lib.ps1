# SPDX-License-Identifier: Apache-2.0
# Copyright (c) SuperiorSoft LLC
# RUST-PERF-1 helpers: clean launches, the process tree, memory/CPU samples by role.

function Start-Clean {
    # Starts [exe] with every INFANTRY_* / AGENTHQ_* / CLAUDE* variable removed, then [extra] added. Returns the Process.
    param([string]$Exe, [string[]]$ArgList = @(), [string]$Cwd, [hashtable]$Extra = @{})
    $psi = [System.Diagnostics.ProcessStartInfo]::new($Exe)
    foreach ($a in $ArgList) { $psi.ArgumentList.Add($a) }
    $psi.UseShellExecute = $false
    $psi.WorkingDirectory = $Cwd
    foreach ($k in @($psi.Environment.Keys)) {
        if ($k -match '^(INFANTRY_|AGENTHQ_|CLAUDE)') { [void]$psi.Environment.Remove($k) }
    }
    foreach ($k in $Extra.Keys) { $psi.Environment[$k] = [string]$Extra[$k] }
    return [System.Diagnostics.Process]::Start($psi)
}

function Get-Tree {
    # Every live process under [root] (inclusive), with its parent.
    param([int]$Root)
    $all = Get-CimInstance Win32_Process -Property ProcessId, ParentProcessId, Name, CreationDate
    $kids = @{}
    foreach ($p in $all) { $kids[[int]$p.ParentProcessId] += @($p) }
    $out = [System.Collections.Generic.List[object]]::new()
    $me = $all | Where-Object ProcessId -eq $Root
    if (-not $me) { return @() }
    $queue = [System.Collections.Generic.Queue[object]]::new()
    $queue.Enqueue($me)
    while ($queue.Count -gt 0) {
        $p = $queue.Dequeue()
        $out.Add($p)
        foreach ($c in @($kids[[int]$p.ProcessId])) {
            # A recycled pid can look like a child: a child must be younger than its parent.
            if ($c -and $c.CreationDate -ge $p.CreationDate) { $queue.Enqueue($c) }
        }
    }
    return $out
}

function Get-Role {
    # Which bucket a process counts in. [parentName] tells a JVM MCP bridge (java under cmd/claude) from the app JVM.
    param($Proc, [int]$Root, $ByPid)
    $n = $Proc.Name.ToLower()
    if ($Proc.ProcessId -eq $Root) { return 'app' }
    if ($n -like 'msedgewebview2*') { return 'webview' }
    if ($n -eq 'claude.exe' -or $n -eq 'node.exe') {
        # node under claude = an MCP server; claude itself = the agent CLI
        $parent = $ByPid[[int]$Proc.ParentProcessId]
        if ($n -eq 'node.exe' -and $parent -and $parent.Name -ieq 'claude.exe') { return 'mcp' }
        return 'agents'
    }
    if ($n -eq 'java.exe' -or $n -eq 'javaw.exe' -or $n -eq 'infantry.exe') {
        # Any JVM/native bridge below an agent CLI is an MCP bridge; a JVM directly under the app is the daemon half.
        $p = $ByPid[[int]$Proc.ParentProcessId]
        while ($p) {
            if ($p.Name -ieq 'claude.exe') { return 'mcp' }
            if ($p.ProcessId -eq $Root) { break }
            $p = $ByPid[[int]$p.ParentProcessId]
        }
        return 'app'
    }
    if ($n -eq 'cmd.exe') {
        # cmd between an agent CLI and a JVM bridge (`infantry.cmd mcp serve`) belongs to the bridge; a hook's
        # short `echo >> file` shell does not.
        $parent = $ByPid[[int]$Proc.ParentProcessId]
        $bridge = @($ByPid.Values | Where-Object { $_.ParentProcessId -eq $Proc.ProcessId -and $_.Name -match '^(java|javaw|infantry)\.exe$' }).Count -gt 0
        if ($parent -and $parent.Name -ieq 'claude.exe' -and $bridge) { return 'mcp' }
        return 'other'
    }
    if ($n -eq 'conhost.exe' -or $n -eq 'openconsole.exe') { return 'console' }
    return 'other'
}

function Get-Sample {
    # One sample of the tree: every process with its role, working set, private bytes and total CPU seconds.
    param([int]$Root)
    $tree = Get-Tree -Root $Root
    $byPid = @{}
    foreach ($p in $tree) { $byPid[[int]$p.ProcessId] = $p }
    $procs = foreach ($p in $tree) {
        $gp = Get-Process -Id $p.ProcessId -ErrorAction SilentlyContinue
        if (-not $gp) { continue }
        $cpu = 0.0
        try { $cpu = $gp.TotalProcessorTime.TotalSeconds } catch {}
        [pscustomobject]@{ pid = [int]$p.ProcessId; name = $p.Name; role = (Get-Role -Proc $p -Root $Root -ByPid $byPid); ws = $gp.WorkingSet64; priv = $gp.PrivateMemorySize64; cpu = $cpu }
    }
    return [pscustomobject]@{ t = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds(); procs = @($procs); pids = @($tree | ForEach-Object { [int]$_.ProcessId }) }
}

function Get-RoleMem {
    # role -> { ws, priv, n, names } of one sample.
    param($Sample)
    $roles = @{}
    foreach ($p in $Sample.procs) {
        if (-not $roles[$p.role]) { $roles[$p.role] = @{ ws = 0L; priv = 0L; n = 0; names = @{} } }
        $roles[$p.role].ws += $p.ws; $roles[$p.role].priv += $p.priv; $roles[$p.role].n += 1
        $roles[$p.role].names[$p.name] = 1
    }
    return $roles
}

function Get-RoleCpu {
    # role -> % of one core between two samples, per pid (a pid new in [B] counts from 0; one gone by [B] is lost).
    param($A, $B)
    $start = @{}; foreach ($p in $A.procs) { $start[$p.pid] = $p.cpu }
    $secs = ($B.t - $A.t) / 1000.0
    $out = @{}
    foreach ($p in $B.procs) {
        $d = $p.cpu - $(if ($start.ContainsKey($p.pid)) { $start[$p.pid] } else { 0.0 })
        if ($d -lt 0) { $d = 0 }
        $out[$p.role] = $(if ($out.ContainsKey($p.role)) { $out[$p.role] } else { 0.0 }) + $d
    }
    $r = @{}; foreach ($k in $out.Keys) { $r[$k] = [math]::Round(100.0 * $out[$k] / $secs, 1) }
    return $r
}

function Stop-Tree {
    # Kills [root] and everything under it, by PID only.
    param([int]$Root)
    $pids = @(Get-Tree -Root $Root | ForEach-Object { [int]$_.ProcessId })
    [array]::Reverse($pids)
    foreach ($p in $pids) { try { Stop-Process -Id $p -Force -ErrorAction Stop } catch {} }
}
