// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! RUST-BUG 2617b342: the app supervises its daemon sidecar. When the daemon stops answering without a clean stop
//! (no `running:false` in `health/status.json`), the app starts it again with a growing delay, writes the reason into
//! the health restart log (`health/events.jsonl`, which `infantry health` prints) and tells the window, which raises an
//! owner notification. A clean stop (the daemon recorded its own reason, or the app stopped it for an update or a
//! restart) is never restarted.
//!
//! Every number is a named setting in `ui.json` under `daemonSupervisor`.

use crate::daemon::{self, Launch, Sidecar};
use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Condvar, Mutex};
use std::time::Duration;

pub const DEFAULT_POLL_MS: u64 = 2_000;
pub const DEFAULT_BACKOFF_MS: u64 = 1_000;
pub const DEFAULT_BACKOFF_MAX_MS: u64 = 60_000;
/// A restarted daemon that stays up this long starts the next failure at the first backoff again.
pub const DEFAULT_STABLE_MS: u64 = 60_000;
/// `daemon.log` generations kept under `<state>/logs/` (`daemon.1.log` ... `daemon.N.log` besides the live one).
pub const DEFAULT_LOG_KEEP: usize = 5;
/// Consecutive probes that must fail before the daemon counts as down (one slow `/health` is not a death).
pub const DEFAULT_FAILED_PROBES: u32 = 3;

/// `ui.json` `daemonSupervisor`: every field optional, an absent one takes its default.
#[derive(Deserialize, Serialize, Default, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct SupervisorSettings {
    /// Restart a daemon that died without a clean stop (default true).
    pub enabled: Option<bool>,
    pub poll_ms: Option<u64>,
    pub backoff_ms: Option<u64>,
    pub backoff_max_ms: Option<u64>,
    pub stable_ms: Option<u64>,
    pub failed_probes: Option<u32>,
    pub log_keep: Option<usize>,
}

impl SupervisorSettings {
    pub fn enabled(&self) -> bool {
        self.enabled.unwrap_or(true)
    }
    pub fn poll_ms(&self) -> u64 {
        self.poll_ms.unwrap_or(DEFAULT_POLL_MS).max(200)
    }
    pub fn backoff_ms(&self) -> u64 {
        self.backoff_ms.unwrap_or(DEFAULT_BACKOFF_MS).max(100)
    }
    pub fn backoff_max_ms(&self) -> u64 {
        self.backoff_max_ms.unwrap_or(DEFAULT_BACKOFF_MAX_MS).max(self.backoff_ms())
    }
    pub fn stable_ms(&self) -> u64 {
        self.stable_ms.unwrap_or(DEFAULT_STABLE_MS)
    }
    pub fn failed_probes(&self) -> u32 {
        self.failed_probes.unwrap_or(DEFAULT_FAILED_PROBES).max(1)
    }
    pub fn log_keep(&self) -> usize {
        self.log_keep.unwrap_or(DEFAULT_LOG_KEEP).max(1)
    }
}

/// What the health record says about a daemon that no longer answers.
#[derive(Debug, PartialEq)]
pub enum Verdict {
    /// It recorded its own stop (the reason is its `stopReason`).
    CleanStop(String),
    /// No clean stop on record: it died. The text names the last evidence (panic / crash file) when there is any.
    Died(String),
}

/// Judges a daemon that failed its probes, from `<state>/health/`.
pub fn judge(state: &Path) -> Verdict {
    let health = state.join("health");
    let st: Option<serde_json::Value> = std::fs::read_to_string(health.join("status.json")).ok().and_then(|t| serde_json::from_str(&t).ok());
    if let Some(st) = &st {
        if st["running"].as_bool() == Some(false) {
            return Verdict::CleanStop(st["stopReason"].as_str().unwrap_or("clean stop").to_string());
        }
    }
    let pid = st.as_ref().and_then(|s| s["pid"].as_i64());
    let mut why = String::from("the daemon stopped answering and recorded no clean stop");
    if let Some(pid) = pid {
        why.push_str(&format!(" (pid {pid})"));
        let tag = format!(" pid {pid} ");
        if let Some(line) = std::fs::read_to_string(health.join("panics.log")).ok().and_then(|t| t.lines().rev().find(|l| l.contains(&tag)).map(str::to_string)) {
            why.push_str(&format!("; last panic: {}", line.split_once("panicked: ").map(|(_, m)| m).unwrap_or(&line)));
        }
        let tail = format!("-pid{pid}.txt");
        let mut crashes: Vec<PathBuf> = std::fs::read_dir(health.join("crashes"))
            .map(|d| d.filter_map(|e| e.ok().map(|e| e.path())).filter(|p| p.to_string_lossy().ends_with(&tail)).collect())
            .unwrap_or_default();
        crashes.sort();
        if let Some(c) = crashes.last() {
            why.push_str(&format!("; crash file {}", c.display()));
        }
    }
    Verdict::Died(why)
}

/// The delay after `previous` (0 = the first failure): doubled up to the cap.
pub fn next_backoff(previous_ms: u64, s: &SupervisorSettings) -> u64 {
    if previous_ms == 0 { s.backoff_ms() } else { (previous_ms.saturating_mul(2)).min(s.backoff_max_ms()) }
}

/// Appends one line to the health restart log (`health/events.jsonl`), the file `infantry health` prints from.
pub fn journal(state: &Path, kind: &str, cause: &str, detail: &str) {
    use std::io::Write;
    let dir = state.join("health");
    let _ = std::fs::create_dir_all(&dir);
    let at = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0);
    let line = serde_json::json!({"at": at, "kind": kind, "cause": cause, "detail": detail}).to_string();
    if let Ok(mut f) = std::fs::OpenOptions::new().create(true).append(true).open(dir.join("events.jsonl")) {
        let _ = writeln!(f, "{line}");
    }
}

/// What the window shows as an owner notification.
#[derive(Serialize, Clone)]
pub struct Notice {
    pub id: String,
    pub title: String,
    pub body: String,
    pub level: &'static str,
}

/// Everything the loop needs from the app.
pub struct Watch {
    pub state: PathBuf,
    pub port: u16,
    pub cwd: PathBuf,
    pub settings: SupervisorSettings,
    /// The launch settings are read fresh for each start (phone access can change).
    pub launch: Box<dyn Fn() -> Result<Launch, String> + Send>,
    pub cell: Arc<(Mutex<Option<Result<Sidecar, String>>>, Condvar)>,
    /// Set while the app itself stops the daemon (update, restart): not a death.
    pub paused: Arc<AtomicBool>,
    /// Set when the app is exiting.
    pub exiting: Arc<AtomicBool>,
    pub notify: Box<dyn Fn(Notice) + Send>,
}

/// Runs on its own thread for the life of the app.
pub fn run(w: Watch) {
    let s = w.settings.clone();
    if !s.enabled() {
        return;
    }
    let mut failed = 0u32;
    let mut backoff = 0u64;
    let mut up_since = std::time::Instant::now();
    loop {
        std::thread::sleep(Duration::from_millis(s.poll_ms()));
        if w.exiting.load(Ordering::SeqCst) {
            return;
        }
        if w.paused.load(Ordering::SeqCst) {
            failed = 0;
            continue;
        }
        // Only a daemon the sidecar cell holds a good start for is watched (a failed first start is the window's banner).
        let url = match w.cell.0.lock().unwrap().as_ref() {
            Some(Ok(sc)) => sc.url.clone(),
            _ => continue,
        };
        let port = url.trim_end_matches("/ws").rsplit(':').next().and_then(|p| p.parse().ok()).unwrap_or(w.port);
        if daemon::healthy(port) {
            failed = 0;
            if up_since.elapsed() > Duration::from_millis(s.stable_ms()) {
                backoff = 0;
            }
            continue;
        }
        failed += 1;
        if failed < s.failed_probes() {
            continue;
        }
        failed = 0;
        let why = match judge(&w.state) {
            Verdict::CleanStop(reason) => {
                // The daemon ended itself on purpose; leave it down, once the window knows.
                journal(&w.state, "supervisor_idle", &reason, "the daemon stopped cleanly; the app does not restart it");
                return;
            }
            Verdict::Died(why) => why,
        };
        loop {
            if w.exiting.load(Ordering::SeqCst) || w.paused.load(Ordering::SeqCst) {
                break;
            }
            backoff = next_backoff(if backoff == 0 { 0 } else { backoff }, &s);
            journal(&w.state, "daemon_died", &why, &format!("restarting in {backoff} ms"));
            std::thread::sleep(Duration::from_millis(backoff));
            let started = (w.launch)().and_then(|l| daemon::ensure(&w.state, w.port, &w.cwd, &l, s.log_keep()));
            match started {
                Ok(sc) => {
                    journal(&w.state, "daemon_supervised_restart", &why, &format!("restarted after {backoff} ms backoff: {}", sc.url));
                    *w.cell.0.lock().unwrap() = Some(Ok(sc));
                    w.cell.1.notify_all();
                    up_since = std::time::Instant::now();
                    (w.notify)(Notice {
                        id: format!("daemon-restarted-{}", std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis()).unwrap_or(0)),
                        title: "The Infantry AI daemon stopped and was restarted".into(),
                        body: format!("{why}. Sessions that were running may need to be resumed."),
                        level: "urgent",
                    });
                    break;
                }
                Err(e) => {
                    journal(&w.state, "daemon_restart_failed", &why, &e);
                    (w.notify)(Notice {
                        id: format!("daemon-restart-failed-{backoff}"),
                        title: "The Infantry AI daemon is down and did not restart".into(),
                        body: format!("{e}. The app tries again in {} ms.", next_backoff(backoff, &s)),
                        level: "urgent",
                    });
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tmp(tag: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("infantry-sup-{tag}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        std::fs::create_dir_all(d.join("health")).unwrap();
        d
    }

    #[test]
    fn a_recorded_stop_is_clean_and_a_running_record_is_a_death() {
        let d = tmp("judge");
        std::fs::write(d.join("health/status.json"), r#"{"pid":5,"port":1,"running":false,"stopReason":"ctrl-c"}"#).unwrap();
        assert_eq!(judge(&d), Verdict::CleanStop("ctrl-c".into()));
        std::fs::write(d.join("health/status.json"), r#"{"pid":5,"port":1,"running":true}"#).unwrap();
        std::fs::write(d.join("health/panics.log"), "1 pid 5 thread 'main' panicked: boom at x.rs:1\n").unwrap();
        std::fs::create_dir_all(d.join("health/crashes")).unwrap();
        std::fs::write(d.join("health/crashes/crash-000000000000001-pid5.txt"), "x").unwrap();
        match judge(&d) {
            Verdict::Died(w) => assert!(w.contains("no clean stop") && w.contains("last panic: boom at x.rs:1") && w.contains("crash file"), "{w}"),
            v => panic!("{v:?}"),
        }
        assert!(matches!(judge(&tmp("none")), Verdict::Died(_)), "no record at all is a death");
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn backoff_doubles_to_its_cap_and_settings_have_defaults() {
        let s = SupervisorSettings { backoff_ms: Some(1000), backoff_max_ms: Some(5000), ..Default::default() };
        let seq: Vec<u64> = std::iter::successors(Some(next_backoff(0, &s)), |p| Some(next_backoff(*p, &s))).take(5).collect();
        assert_eq!(seq, vec![1000, 2000, 4000, 5000, 5000]);
        let d = SupervisorSettings::default();
        assert!(d.enabled() && d.log_keep() == DEFAULT_LOG_KEEP && d.failed_probes() == DEFAULT_FAILED_PROBES);
    }

    #[test]
    fn the_journal_line_is_what_the_health_tail_reads() {
        let d = tmp("journal");
        journal(&d, "daemon_died", "why", "detail");
        let line = std::fs::read_to_string(d.join("health/events.jsonl")).unwrap();
        let v: serde_json::Value = serde_json::from_str(line.trim()).unwrap();
        assert_eq!((v["kind"].as_str(), v["cause"].as_str(), v["detail"].as_str()), (Some("daemon_died"), Some("why"), Some("detail")));
        let _ = std::fs::remove_dir_all(&d);
    }

    /// A real daemon on a free port, killed by its pid: the supervisor starts a new one, journals why and raises the
    /// notice. Needs a daemon build (INFANTRY_DAEMON_BIN or the release build; returns early when there is none).
    #[test]
    fn a_killed_daemon_is_restarted_journaled_and_announced() {
        use std::sync::atomic::Ordering;
        if daemon::binary().is_err() {
            return;
        }
        let state = std::env::temp_dir().join(format!("infantry-supe2e-{}", uuid::Uuid::new_v4().simple()));
        let cwd = state.clone();
        std::fs::create_dir_all(&state).unwrap();
        let first = daemon::ensure(&state, 0, &cwd, &Launch::default(), 3).unwrap();
        let first_url = first.url.clone();
        let mut child = first.child;
        let cell: Arc<(Mutex<Option<Result<Sidecar, String>>>, Condvar)> = Arc::new((Mutex::new(Some(Ok(Sidecar { url: first_url.clone(), child: None, note: None }))), Condvar::new()));
        let (paused, exiting) = (Arc::new(AtomicBool::new(false)), Arc::new(AtomicBool::new(false)));
        let notices = Arc::new(Mutex::new(Vec::<Notice>::new()));
        let (n2, st2, e2, c2, p2) = (notices.clone(), state.clone(), exiting.clone(), cell.clone(), paused.clone());
        let settings = SupervisorSettings { poll_ms: Some(200), backoff_ms: Some(100), failed_probes: Some(2), log_keep: Some(3), ..Default::default() };
        let t = std::thread::spawn(move || {
            run(Watch { state: st2.clone(), port: 0, cwd, settings, launch: Box::new(|| Ok(Launch::default())), cell: c2, paused: p2, exiting: e2, notify: Box::new(move |n| n2.lock().unwrap().push(n)) })
        });
        child.as_mut().unwrap().kill().unwrap(); // by pid, never by image name
        let _ = child.as_mut().unwrap().wait();
        let start = std::time::Instant::now();
        let new_url = loop {
            let u = match cell.0.lock().unwrap().as_ref() {
                Some(Ok(sc)) if sc.url != first_url => Some(sc.url.clone()),
                _ => None,
            };
            if let Some(u) = u {
                break u;
            }
            assert!(start.elapsed() < Duration::from_secs(60), "no restart: {:?}", std::fs::read_to_string(state.join("health/events.jsonl")));
            std::thread::sleep(Duration::from_millis(200));
        };
        let port: u16 = new_url.trim_end_matches("/ws").rsplit(':').next().unwrap().parse().unwrap();
        assert!(daemon::healthy(port));
        let events = std::fs::read_to_string(state.join("health/events.jsonl")).unwrap();
        assert!(events.contains("daemon_died") && events.contains("daemon_supervised_restart") && events.contains("no clean stop"), "{events}");
        assert!(notices.lock().unwrap().iter().any(|n| n.level == "urgent" && n.title.contains("restarted")));
        assert!(state.join("logs/daemon.log").is_file() && state.join("logs/daemon.1.log").is_file(), "logs rotated, not truncated");
        exiting.store(true, Ordering::SeqCst);
        let _ = t.join();
        if let Some(pid) = daemon::running_pid(&state) {
            let _ = daemon::stop(&state).map_err(|e| eprintln!("cleanup pid {pid}: {e}"));
        }
        let _ = std::fs::remove_dir_all(&state);
    }
}
