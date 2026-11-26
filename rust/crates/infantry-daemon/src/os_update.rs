// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! UPDATE-LIVE-1: the daemon as an app gets ready for an update and picks its work up again after it.
//!
//! The OS (BattalionOS app contract, `prepare` in the ready file) asks `POST /prepare-update` with the stop secret:
//! - `{to}`: new turns stop starting ([Daemon::pause_turns]: a prompt, mail or board start into a session at rest waits
//!   in that session's stored queue). A turn that runs may finish within `limits.update.turnGraceSec` (default
//!   [DEFAULT_TURN_GRACE_SEC]). The answer is `{ready: false, etaMs, reason}` until no turn runs or the grace is over;
//!   then the sessions whose turn is still running are written to `<state>/update-resume.json` and the answer is ready.
//! - `{to, now: true}`: the same file at once, ready ("Update now").
//! - `{cancel: true}`: turns start again and the held messages go out; the file is removed.
//!
//! After the restart on the new version, [after_restart] reads the file once: every session whose turn the update cut is
//! told to continue (the harness resume id keeps its conversation; a ticket worker keeps its ticket and worktree), every
//! session at rest with held messages takes them, and the caller shows one line: "Updated to <to>: N agents resumed".

use crate::os_app::OsRuntime;
use crate::sessions::Daemon;
use std::path::Path;
use std::sync::Arc;
use std::time::Instant;

/// The file that carries what to resume across the restart, in the state dir.
pub const RESUME_FILE: &str = "update-resume.json";
/// The setting: how long a running turn may go on once an update is asked, in seconds.
pub const TURN_GRACE_KEY: &str = "limits.update.turnGraceSec";
pub const DEFAULT_TURN_GRACE_SEC: u64 = 90;
/// What the OS shows while it waits: "<App> will update when <REASON> (about N min)."
pub const REASON: &str = "its agents finish their current step";

/// The body of a prepare call.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct Request {
    pub to: Option<String>,
    pub now: bool,
    pub cancel: bool,
}

impl Request {
    /// An empty body is `{}` (get ready, version unknown). Anything that is not a JSON object is refused.
    pub fn parse(body: &[u8]) -> Result<Request, String> {
        if body.iter().all(|b| b.is_ascii_whitespace()) {
            return Ok(Request::default());
        }
        let v: serde_json::Value = serde_json::from_slice(body).map_err(|e| format!("the prepare body is not JSON: {e}"))?;
        let o = v.as_object().ok_or("the prepare body must be a JSON object")?;
        let flag = |k: &str| o.get(k).and_then(|x| x.as_bool()).unwrap_or(false);
        let to = o.get("to").and_then(|x| x.as_str()).map(str::trim).filter(|s| !s.is_empty()).map(|s| s.chars().take(64).collect());
        Ok(Request { to, now: flag("now"), cancel: flag("cancel") })
    }
}

/// One prepare call. Idempotent: the OS asks again until the answer is ready.
pub fn prepare(daemon: &Arc<Daemon>, os: &OsRuntime, req: &Request) -> serde_json::Value {
    let state_dir = daemon.cfg.state_dir.clone();
    if req.cancel {
        *os.prepare_since.lock().unwrap_or_else(|p| p.into_inner()) = None;
        let _ = std::fs::remove_file(state_dir.join(RESUME_FILE));
        daemon.pause_turns(false);
        eprintln!("[infantry] update put off: new turns start again");
        return serde_json::json!({"ready": false, "cancelled": true});
    }
    daemon.pause_turns(true);
    let since = *os.prepare_since.lock().unwrap_or_else(|p| p.into_inner()).get_or_insert_with(Instant::now);
    let grace = os.turn_grace_ms.get().map(|f| f()).unwrap_or(DEFAULT_TURN_GRACE_SEC * 1000);
    let running = daemon.running_turns();
    let elapsed = since.elapsed().as_millis() as u64;
    if !running.is_empty() && !req.now && elapsed < grace {
        return serde_json::json!({"ready": false, "etaMs": grace - elapsed, "reason": REASON});
    }
    match write_resume(&state_dir, req.to.as_deref(), &running) {
        Ok(()) => {
            eprintln!("[infantry] ready for the update{}: {} turn(s) to resume after the restart", req.to.as_deref().map(|t| format!(" to {t}")).unwrap_or_default(), running.len());
            serde_json::json!({"ready": true})
        }
        // Not ready: stopping now would lose the turns to resume. The OS asks again (or the person chooses).
        Err(e) => serde_json::json!({"ready": false, "reason": format!("it can save its sessions ({e})")}),
    }
}

fn write_resume(state_dir: &Path, to: Option<&str>, cut: &[String]) -> Result<(), String> {
    let body = serde_json::json!({"to": to, "at": crate::sessions::now_ms(), "cut": cut});
    let path = state_dir.join(RESUME_FILE);
    let tmp = path.with_extension("tmp");
    std::fs::write(&tmp, body.to_string()).map_err(|e| format!("{}: {e}", tmp.display()))?;
    std::fs::rename(&tmp, &path).map_err(|e| format!("{}: {e}", path.display()))
}

/// What [after_restart] did.
#[derive(Debug, Clone, PartialEq)]
pub struct Resumed {
    pub to: Option<String>,
    /// Sessions told to continue a turn the update cut, or given their held messages.
    pub sessions: Vec<String>,
}

impl Resumed {
    /// "Updated to 0.8.7: 3 agents resumed".
    pub fn line(&self) -> String {
        let n = self.sessions.len();
        let to = self.to.as_deref().map(|t| format!("Updated to {t}")).unwrap_or_else(|| "Updated".into());
        match n {
            0 => format!("{to}: no agent was working"),
            1 => format!("{to}: 1 agent resumed"),
            _ => format!("{to}: {n} agents resumed"),
        }
    }
}

/// The turn a session cut by the update gets.
pub fn continue_text(to: Option<&str>) -> String {
    format!("[UPDATE] The app was updated{} and restarted while your turn ran. Continue where you left off.", to.map(|t| format!(" to {t}")).unwrap_or_default())
}

/// Once, after the start on the new version (the port is bound, so a resumed agent reaches this daemon): reads and removes
/// the resume file, resumes the cut sessions and sends the held messages. `None` = this start was not after a prepared update.
pub fn after_restart(daemon: &Arc<Daemon>) -> Option<Resumed> {
    let path = daemon.cfg.state_dir.join(RESUME_FILE);
    let text = std::fs::read_to_string(&path).ok()?;
    let _ = std::fs::remove_file(&path);
    let v: serde_json::Value = serde_json::from_str(text.trim_start_matches('\u{feff}')).unwrap_or_default();
    let to = v["to"].as_str().map(str::to_string);
    let cut: Vec<String> = v["cut"].as_array().map(|a| a.iter().filter_map(|x| x.as_str().map(str::to_string)).collect()).unwrap_or_default();
    daemon.pause_turns(false);
    let mut sessions = vec![];
    let text = continue_text(to.as_deref());
    for id in &cut {
        match daemon.deliver(id, &text) {
            Ok(()) => sessions.push(id.clone()),
            Err(e) => eprintln!("[infantry] after the update, session {id} could not be resumed: {e}"),
        }
    }
    for id in daemon.send_held() {
        if !sessions.contains(&id) {
            sessions.push(id);
        }
    }
    let r = Resumed { to, sessions };
    eprintln!("[infantry] {}", r.line());
    Some(r)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_body_is_read_and_a_bad_one_refused() {
        assert_eq!(Request::parse(b"").unwrap(), Request::default());
        assert_eq!(Request::parse(br#"{"to":"0.8.7"}"#).unwrap(), Request { to: Some("0.8.7".into()), ..Default::default() });
        assert_eq!(Request::parse(br#"{"to":"1.0.0","now":true}"#).unwrap().now, true);
        assert!(Request::parse(br#"{"cancel":true}"#).unwrap().cancel);
        assert!(Request::parse(b"[1]").is_err());
        assert!(Request::parse(b"nope").is_err());
    }

    #[test]
    fn the_line_names_the_version_and_the_agents() {
        let r = |to: Option<&str>, n: usize| Resumed { to: to.map(str::to_string), sessions: (0..n).map(|i| i.to_string()).collect() }.line();
        assert_eq!(r(Some("0.8.7"), 3), "Updated to 0.8.7: 3 agents resumed");
        assert_eq!(r(Some("0.8.7"), 1), "Updated to 0.8.7: 1 agent resumed");
        assert_eq!(r(None, 0), "Updated: no agent was working");
        assert!(continue_text(Some("0.8.7")).contains("Continue where you left off"));
    }
}
