// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! ROGUE-PROC-1: the processes the daemon's own children started (a worker's shell: test daemons, dev servers, build
//! tools), found by walking the OS process table down from the pids the daemon tracks, and remembered so that an orphan
//! (its owner session ended, was removed, or its parent died) is still attributed and flagged rogue.
//!
//! Safety: only pids once seen under a tracked root are ever listed; the daemon itself, its ancestors (the owner's app)
//! and listeners on the protected ports are never rogue; shared build daemons are flagged but never reaped by Clean up.
//! Nothing is ever selected by image name: the name only labels a row or protects it.

use std::collections::{HashMap, HashSet};

/// One row of the OS process table.
#[derive(Debug, Clone, PartialEq)]
pub struct Proc {
    pub pid: u32,
    pub ppid: u32,
    pub name: String,
    /// Creation time, ms since the epoch; tells a reused pid from the process we saw (None when the OS does not answer).
    pub created_ms: Option<i64>,
}

/// A pid the daemon started itself (a session's harness CLI or a Run-tab run).
#[derive(Debug, Clone)]
pub struct Root {
    pub pid: u32,
    pub session: Option<String>,
    pub ticket: Option<String>,
    /// False when the owner is gone (the session ended but its process stayed): its descendants are rogue too.
    pub owner_alive: bool,
}

/// A descendant once seen under a root.
#[derive(Debug, Clone)]
pub struct Seen {
    pub session: Option<String>,
    pub ticket: Option<String>,
    pub created_ms: Option<i64>,
}

/// One descendant in the report.
#[derive(Debug, Clone)]
pub struct Found {
    pub proc: Proc,
    pub session: Option<String>,
    pub ticket: Option<String>,
    pub kind: &'static str,
    pub rogue: Option<String>,
    /// A build daemon other jobs may share (REAP-SHARED-1): shown, never reaped by Clean up.
    pub shared: bool,
}

/// Ports whose listener is never touched: the daemon default and the owner's daemon, UI and PM MCP pair.
pub const PROTECTED_PORTS: [u16; 5] = [18990, 8787, 8788, 8797, 8798];

/// Image names of build daemons that outlive one job and are shared (Gradle/Kotlin on the JVM, sccache).
const SHARED_NAMES: [&str; 3] = ["java", "javaw", "sccache"];

fn stem(name: &str) -> String {
    let n = name.rsplit(['/', '\\']).next().unwrap_or(name).to_ascii_lowercase();
    n.strip_suffix(".exe").map(str::to_string).unwrap_or(n)
}

/// What a descendant is, from its image name (a label only).
pub fn kind_of(name: &str) -> &'static str {
    match stem(name).as_str() {
        "infantry-daemon" | "battalion-daemon" => "test daemon",
        "node" | "vite" | "esbuild" | "bun" | "deno" => "dev server",
        "cargo" | "rustc" | "pnpm" | "npm" | "gradle" | "java" | "javaw" | "sccache" | "link" => "build",
        "cmd" | "powershell" | "pwsh" | "bash" | "sh" | "conhost" => "shell",
        _ => "child",
    }
}

fn is_shared(name: &str) -> bool {
    SHARED_NAMES.contains(&stem(name).as_str())
}

/// `pid`, then each parent up the table (the daemon's own line: never rogue).
pub fn ancestors(table: &[Proc], pid: u32) -> HashSet<u32> {
    let by: HashMap<u32, &Proc> = table.iter().map(|p| (p.pid, p)).collect();
    let mut out = HashSet::from([pid]);
    let mut cur = pid;
    while let Some(p) = by.get(&cur) {
        if p.ppid == 0 || !out.insert(p.ppid) {
            break;
        }
        cur = p.ppid;
    }
    out
}

/// Every descendant of `root` (not `root` itself). A child created before its listed parent is a reused ppid, not a child.
pub fn descendants(table: &[Proc], root: u32) -> Vec<&Proc> {
    let mut kids: HashMap<u32, Vec<&Proc>> = HashMap::new();
    for p in table {
        if p.pid != p.ppid {
            kids.entry(p.ppid).or_default().push(p);
        }
    }
    let created: HashMap<u32, Option<i64>> = table.iter().map(|p| (p.pid, p.created_ms)).collect();
    let (mut out, mut stack, mut seen) = (Vec::new(), vec![root], HashSet::from([root]));
    while let Some(parent) = stack.pop() {
        for c in kids.get(&parent).map(Vec::as_slice).unwrap_or(&[]) {
            let reused = matches!((created.get(&parent).copied().flatten(), c.created_ms), (Some(pc), Some(cc)) if cc < pc);
            if !reused && seen.insert(c.pid) {
                out.push(*c);
                stack.push(c.pid);
            }
        }
    }
    out
}

/// The descendants of every root, plus the remembered ones still alive (orphans), each flagged rogue or not.
/// `seen` is updated: new descendants are remembered, dead or reused pids forgotten. Roots themselves are not returned.
pub fn classify(table: &[Proc], roots: &[Root], seen: &mut HashMap<u32, Seen>, protected: &HashSet<u32>, now_ms: i64, rogue_after_ms: Option<i64>) -> Vec<Found> {
    let root_pids: HashSet<u32> = roots.iter().map(|r| r.pid).collect();
    let mut under: HashMap<u32, &Root> = HashMap::new();
    for r in roots {
        for d in descendants(table, r.pid) {
            if root_pids.contains(&d.pid) {
                continue;
            }
            // A pid under a live owner wins over one under a gone owner (never rogue while a live tree has it).
            match under.get(&d.pid) {
                Some(prev) if prev.owner_alive => {}
                _ => {
                    under.insert(d.pid, r);
                }
            }
        }
    }
    for (pid, r) in &under {
        let created = table.iter().find(|p| p.pid == *pid).and_then(|p| p.created_ms);
        seen.insert(*pid, Seen { session: r.session.clone(), ticket: r.ticket.clone(), created_ms: created });
    }
    let by: HashMap<u32, &Proc> = table.iter().map(|p| (p.pid, p)).collect();
    seen.retain(|pid, s| match by.get(pid) {
        Some(p) => s.created_ms.is_none() || p.created_ms.is_none() || p.created_ms == s.created_ms,
        None => false,
    });
    let mut out: Vec<Found> = seen
        .iter()
        .filter(|(pid, _)| !root_pids.contains(pid))
        .filter_map(|(pid, s)| {
            let p = (*by.get(pid)?).clone();
            let shared = is_shared(&p.name);
            let rogue = if protected.contains(pid) {
                None
            } else {
                match under.get(pid) {
                    Some(r) if !r.owner_alive => Some("owner gone: its session ended".to_string()),
                    Some(_) => match (rogue_after_ms, p.created_ms) {
                        (Some(limit), Some(c)) if limit > 0 && now_ms - c > limit => Some(format!("running past {} min", limit / 60_000)),
                        _ => None,
                    },
                    None => Some("owner gone: its parent session or process ended".to_string()),
                }
            };
            Some(Found { kind: kind_of(&p.name), session: s.session.clone(), ticket: s.ticket.clone(), rogue, shared, proc: p })
        })
        .collect();
    out.sort_by_key(|f| f.proc.pid);
    out
}

/// The OS process table (Windows: a Toolhelp snapshot; elsewhere `/proc`). Empty when it cannot be read.
#[cfg(windows)]
pub fn snapshot() -> Vec<Proc> {
    use windows_sys::Win32::Foundation::{CloseHandle, INVALID_HANDLE_VALUE};
    use windows_sys::Win32::System::Diagnostics::ToolHelp::{CreateToolhelp32Snapshot, Process32FirstW, Process32NextW, PROCESSENTRY32W, TH32CS_SNAPPROCESS};
    let mut out = Vec::new();
    // SAFETY: a snapshot handle is walked with a correctly sized entry and closed once.
    unsafe {
        let h = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
        if h == INVALID_HANDLE_VALUE {
            return out;
        }
        let mut e: PROCESSENTRY32W = std::mem::zeroed();
        e.dwSize = std::mem::size_of::<PROCESSENTRY32W>() as u32;
        let mut ok = Process32FirstW(h, &mut e) != 0;
        while ok {
            let len = e.szExeFile.iter().position(|c| *c == 0).unwrap_or(e.szExeFile.len());
            let name = String::from_utf16_lossy(&e.szExeFile[..len]);
            out.push(Proc { pid: e.th32ProcessID, ppid: e.th32ParentProcessID, name, created_ms: created_ms(e.th32ProcessID) });
            ok = Process32NextW(h, &mut e) != 0;
        }
        CloseHandle(h);
    }
    out
}

/// A process's creation time, ms since the Unix epoch.
#[cfg(windows)]
pub fn created_ms(pid: u32) -> Option<i64> {
    use windows_sys::Win32::Foundation::{CloseHandle, FILETIME};
    use windows_sys::Win32::System::Threading::{GetProcessTimes, OpenProcess, PROCESS_QUERY_LIMITED_INFORMATION};
    // SAFETY: the handle is checked and closed; the out FILETIMEs are plain data.
    unsafe {
        let h = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, pid);
        if h.is_null() {
            return None;
        }
        let (mut c, mut x, mut k, mut u): (FILETIME, FILETIME, FILETIME, FILETIME) = std::mem::zeroed();
        let ok = GetProcessTimes(h, &mut c, &mut x, &mut k, &mut u) != 0;
        CloseHandle(h);
        let t = ((c.dwHighDateTime as u64) << 32 | c.dwLowDateTime as u64) as i64;
        // FILETIME counts 100 ns since 1601; the Unix epoch is 11 644 473 600 s later.
        ok.then(|| t / 10_000 - 11_644_473_600_000)
    }
}

#[cfg(not(windows))]
pub fn snapshot() -> Vec<Proc> {
    let boot_ms = std::fs::read_to_string("/proc/stat")
        .ok()
        .and_then(|t| t.lines().find(|l| l.starts_with("btime "))?.split_whitespace().nth(1)?.parse::<i64>().ok())
        .map(|s| s * 1000);
    let Ok(dir) = std::fs::read_dir("/proc") else { return Vec::new() };
    dir.filter_map(|e| {
        let pid: u32 = e.ok()?.file_name().to_str()?.parse().ok()?;
        let t = std::fs::read_to_string(format!("/proc/{pid}/stat")).ok()?;
        let name = t[t.find('(')? + 1..t.rfind(')')?].to_string();
        let f: Vec<&str> = t[t.rfind(')')? + 2..].split_whitespace().collect();
        let ppid = f.get(1)?.parse().ok()?;
        let start_ticks: Option<i64> = f.get(19).and_then(|s| s.parse().ok());
        Some(Proc { pid, ppid, name, created_ms: boot_ms.zip(start_ticks).map(|(b, t)| b + t * 10) })
    })
    .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn p(pid: u32, ppid: u32, name: &str, created: i64) -> Proc {
        Proc { pid, ppid, name: name.into(), created_ms: Some(created) }
    }
    fn root(pid: u32, session: &str, alive: bool) -> Root {
        Root { pid, session: Some(session.into()), ticket: Some("ticket-1".into()), owner_alive: alive }
    }

    // daemon 10 -> harness 20 (session s1) -> bash 21 -> test daemon 22, node 23; unrelated 99 (child of 1).
    fn table() -> Vec<Proc> {
        vec![p(1, 0, "explorer.exe", 0), p(10, 1, "infantry-daemon.exe", 100), p(20, 10, "claude.exe", 200), p(21, 20, "bash.exe", 300), p(22, 21, "infantry-daemon.exe", 400), p(23, 21, "node.exe", 500), p(99, 1, "node.exe", 50)]
    }

    #[test]
    fn descendants_of_a_live_session_are_listed_and_not_rogue() {
        let mut seen = HashMap::new();
        let f = classify(&table(), &[root(20, "s1", true)], &mut seen, &HashSet::new(), 1_000, None);
        assert_eq!(f.iter().map(|f| f.proc.pid).collect::<Vec<_>>(), vec![21, 22, 23]);
        assert!(f.iter().all(|f| f.rogue.is_none()));
        assert_eq!(f.iter().find(|f| f.proc.pid == 22).unwrap().kind, "test daemon");
        assert_eq!(f.iter().find(|f| f.proc.pid == 23).unwrap().kind, "dev server");
    }

    #[test]
    fn an_orphan_left_after_its_session_is_gone_is_rogue() {
        let mut seen = HashMap::new();
        classify(&table(), &[root(20, "s1", true)], &mut seen, &HashSet::new(), 1_000, None);
        // The harness and its shell end; the test daemon and the dev server stay (their parent pid is now dead).
        let after: Vec<Proc> = table().into_iter().filter(|p| p.pid != 20 && p.pid != 21).collect();
        let f = classify(&after, &[], &mut seen, &HashSet::new(), 2_000, None);
        assert_eq!(f.iter().map(|f| (f.proc.pid, f.rogue.is_some(), f.session.as_deref())).collect::<Vec<_>>(), vec![(22, true, Some("s1")), (23, true, Some("s1"))]);
    }

    #[test]
    fn descendants_of_an_exited_session_are_rogue_and_old_ones_past_the_limit() {
        let mut seen = HashMap::new();
        let f = classify(&table(), &[root(20, "s1", false)], &mut seen, &HashSet::new(), 1_000, None);
        assert!(f.iter().all(|f| f.rogue.is_some()));
        let mut seen = HashMap::new();
        let f = classify(&table(), &[root(20, "s1", true)], &mut seen, &HashSet::new(), 60_400, Some(60_000));
        assert_eq!(f.iter().filter(|f| f.rogue.is_some()).map(|f| f.proc.pid).collect::<Vec<_>>(), vec![21]);
    }

    #[test]
    fn a_non_infantry_process_is_never_selected() {
        let mut seen = HashMap::new();
        classify(&table(), &[root(20, "s1", true)], &mut seen, &HashSet::new(), 1_000, None);
        let f = classify(&table(), &[], &mut seen, &HashSet::new(), 2_000, None);
        assert!(f.iter().all(|f| f.proc.pid != 99 && f.proc.pid != 1 && f.proc.pid != 10), "only pids once seen under a root");
    }

    #[test]
    fn the_live_daemon_and_protected_pids_are_never_rogue() {
        let mut seen = HashMap::new();
        // The daemon (10) is under the root only by a broken table; protected wins anyway, and so does a protected port's pid.
        let t = vec![p(20, 1, "claude.exe", 200), p(10, 20, "infantry-daemon.exe", 300), p(22, 20, "infantry-daemon.exe", 400)];
        let protected = HashSet::from([10, 22]);
        let f = classify(&t, &[root(20, "s1", false)], &mut seen, &protected, 1_000, Some(1));
        assert!(f.iter().all(|f| f.rogue.is_none()));
        assert!(ancestors(&table(), 22).is_superset(&HashSet::from([22, 21, 20, 10, 1])));
    }

    #[test]
    fn a_reused_pid_is_forgotten_and_shared_build_daemons_are_flagged() {
        let mut seen = HashMap::new();
        let mut t = table();
        t.push(p(24, 21, "java.exe", 600));
        classify(&t, &[root(20, "s1", true)], &mut seen, &HashSet::new(), 1_000, None);
        let mut after: Vec<Proc> = t.into_iter().filter(|p| ![20, 21, 22].contains(&p.pid)).collect();
        after.push(p(22, 1, "notepad.exe", 9_000)); // pid 22 reused by an unrelated process
        let f = classify(&after, &[], &mut seen, &HashSet::new(), 10_000, None);
        assert!(f.iter().all(|f| f.proc.pid != 22));
        assert!(f.iter().find(|f| f.proc.pid == 24).unwrap().shared);
    }
}
