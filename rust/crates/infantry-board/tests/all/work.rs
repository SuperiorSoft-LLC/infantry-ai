// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! The work half, light: a start puts the ticket in_progress, a done starts the lane's next ticket, a lane cap queues,
//! and concurrent starts never wait on each other.
use crate::common;

use std::collections::HashMap;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use common::*;
use infantry_board::dispatch::reason;
use infantry_board::env::StaticEnv;
use infantry_board::Dispatcher;
use infantry_orchestrator_api::{
    LaunchRequest, LaunchedSession, NoWorkspaces, Orchestrator, SessionLauncher, StartOrigin, TicketClose, TicketDone, TicketRestart, TicketStartRequest, WorkerState,
    Workspace, WorkspaceProvider, WorkspaceRelease, WorkspaceRequest,
};

/// A launcher that hands out `s1`, `s2`, ... after `delay`, and remembers which are still running.
#[derive(Default)]
struct FakeLauncher {
    delay: Duration,
    n: AtomicUsize,
    live: Mutex<HashMap<String, WorkerState>>,
    briefs: Mutex<Vec<(String, String)>>,
    removed: Mutex<Vec<String>>,
    /// PIPE-RESTART-1: the workspace path each launch was given, and the turns delivered into a session (a resume).
    paths: Mutex<Vec<Option<String>>>,
    prompts: Mutex<Vec<(String, String)>>,
    /// Whether a delivered turn brings a dead session back (a real resume); false = the session cannot take a turn.
    resumable: std::sync::atomic::AtomicBool,
}

impl SessionLauncher for FakeLauncher {
    fn launch(&self, req: &LaunchRequest) -> Result<LaunchedSession, String> {
        std::thread::sleep(self.delay);
        let id = format!("s{}", self.n.fetch_add(1, Ordering::SeqCst) + 1);
        self.live.lock().unwrap().insert(id.clone(), WorkerState::Busy);
        self.briefs.lock().unwrap().push((req.ticket_id.clone(), req.brief.clone()));
        self.paths.lock().unwrap().push(req.workspace.as_ref().map(|w| w.path.clone()));
        Ok(LaunchedSession { session_id: id, note: None, workspace: None })
    }
    fn prompt(&self, session_id: &str, text: &str) -> bool {
        self.prompts.lock().unwrap().push((session_id.to_string(), text.to_string()));
        if !self.resumable.load(Ordering::SeqCst) {
            return false;
        }
        self.live.lock().unwrap().insert(session_id.to_string(), WorkerState::Busy);
        true
    }
    fn stop(&self, session_id: &str, _reason: &str) {
        self.live.lock().unwrap().insert(session_id.to_string(), WorkerState::Ended);
    }
    fn remove(&self, session_id: &str, _reason: &str) -> bool {
        self.stop(session_id, "remove");
        self.removed.lock().unwrap().push(session_id.to_string());
        true
    }
    fn state(&self, session_id: &str) -> WorkerState {
        self.live.lock().unwrap().get(session_id).copied().unwrap_or(WorkerState::Ended)
    }
}

fn setup(tag: &str, env: StaticEnv, delay: Duration) -> (Fixture, Arc<FakeLauncher>, Arc<Dispatcher>, infantry_board::LocalOrchestrator) {
    let f = fixture(tag, env);
    let launcher = Arc::new(FakeLauncher { delay, ..Default::default() });
    let d = Dispatcher::new(f.board.clone(), Arc::new(NoWorkspaces), launcher.clone(), f.bus.clone());
    let o = infantry_board::LocalOrchestrator::with_dispatcher(d.clone(), f.bus.clone());
    (f, launcher, d, o)
}

fn tickets(f: &Fixture, n: usize) -> Vec<String> {
    let ids: Vec<String> = (1..=n).map(|i| format!("ticket-0000000{i}")).collect();
    f.env.push_ids(&ids.iter().map(String::as_str).collect::<Vec<_>>());
    for i in 1..=n {
        save(&f.orch, &format!(r#"{{"title":"Work {i}","projectId":"p1","agentId":"role:backend"}}"#));
    }
    ids
}

fn start(o: &infantry_board::LocalOrchestrator, id: &str) -> infantry_orchestrator_api::TicketAnswer {
    block_on(o.start(TicketStartRequest { id: id.into(), started_by: None })).unwrap()
}

#[test]
fn start_puts_the_ticket_in_progress_with_a_brief() {
    let (f, launcher, _d, o) = setup("work-start", kotlin_daemon_env(), Duration::ZERO);
    let ids = tickets(&f, 1);
    let a = start(&o, &ids[0]);
    assert_eq!(a.ticket.start.as_ref().map(|s| s.reason.as_str()), Some(reason::STARTED), "{:?}", a.ticket.start);
    let t = block_on(o.get(ids[0].clone())).unwrap();
    assert_eq!(t.status, "in_progress");
    assert_eq!(t.session_id.as_deref(), Some("s1"));
    let briefs = launcher.briefs.lock().unwrap();
    assert!(briefs[0].1.contains("Work 1"), "the brief names the ticket");
}

/// PIPE-BRIEF-1: a brief without a DONE-report instruction gets the standard block; one with it is unchanged.
#[test]
fn brief_appends_the_done_report_block_only_when_missing() {
    const BLOCK: &str = "STANDARD-DONE-BLOCK for <id>";
    let env = StaticEnv { lane_cap: 10, done_report: Some(BLOCK.into()), ..kotlin_daemon_env() };
    let (f, launcher, _d, o) = setup("work-donereport", env, Duration::ZERO);
    f.env.push_ids(&["ticket-00000001", "ticket-00000002"]);
    save(&f.orch, r#"{"title":"Plain","projectId":"p1","agentId":"role:backend","description":"do it"}"#);
    save(&f.orch, r#"{"title":"Own","projectId":"p1","agentId":"role:backend","description":"do it\n\n**DONE report (mandatory):** mail the PM"}"#);
    start(&o, "ticket-00000001");
    start(&o, "ticket-00000002");
    let briefs = launcher.briefs.lock().unwrap();
    let of = |id: &str| briefs.iter().find(|(t, _)| t == id).unwrap().1.clone();
    assert!(of("ticket-00000001").contains("STANDARD-DONE-BLOCK for ticket-00000001"), "the missing block is appended");
    assert!(!of("ticket-00000002").contains("STANDARD-DONE-BLOCK"), "a ticket with its own DONE report is unchanged");
}

#[test]
fn lane_cap_queues_and_done_starts_the_next() {
    let (f, _launcher, d, o) = setup("work-lane", kotlin_daemon_env(), Duration::ZERO);
    let ids = tickets(&f, 2);
    start(&o, &ids[0]);
    // The backend lane holds one worker (the default cap): the second waits on the lane.
    let b = start(&o, &ids[1]);
    assert_eq!(b.ticket.start.as_ref().map(|s| s.reason.as_str()), Some(reason::QUEUED), "{:?}", b.ticket.start);
    assert_eq!(block_on(o.get(ids[1].clone())).unwrap().status, "todo");
    assert_eq!(d.spawn_limit(Some("p1")).unwrap().running, 1);
    // The done protocol: done comment, done lane, then the queued ticket starts.
    let done = block_on(o.done(TicketDone {
        ticket_id: ids[0].clone(),
        session_id: "s1".into(),
        summary: "commit abc: did the work".into(),
        stop_worker: true,
    }))
    .unwrap();
    assert_eq!(done.ticket.status, "done");
    let next = done.next.expect("the lane's next ticket started");
    assert_eq!(next.id, ids[1]);
    let t = block_on(o.get(ids[1].clone())).unwrap();
    assert_eq!(t.status, "in_progress");
    assert_eq!(t.session_id.as_deref(), Some("s2"));
}

#[test]
fn concurrent_starts_do_not_block() {
    let env = StaticEnv { lane_cap: 10, ..kotlin_daemon_env() };
    let (f, _launcher, d, _o) = setup("work-concurrent", env, Duration::from_millis(400));
    let ids = tickets(&f, 3);
    let t0 = Instant::now();
    let handles: Vec<_> = ids
        .iter()
        .cloned()
        .map(|id| {
            let d = d.clone();
            std::thread::spawn(move || d.start(&id, StartOrigin::Move, None).unwrap())
        })
        .collect();
    // While the three launches run, the board still answers at once.
    std::thread::sleep(Duration::from_millis(100));
    let read = Instant::now();
    f.board.list(None, None).unwrap();
    assert!(read.elapsed() < Duration::from_millis(200), "a board read waited on a start");
    for h in handles {
        let a = h.join().unwrap();
        assert_eq!(a.ticket.start.as_ref().map(|s| s.reason.as_str()), Some(reason::STARTED), "{:?}", a.ticket.start);
    }
    // In parallel: well under three serial launches (1.2 s).
    assert!(t0.elapsed() < Duration::from_millis(1100), "starts ran one after another: {:?}", t0.elapsed());
    for id in &ids {
        assert_eq!(f.board.get(id).unwrap().status, "in_progress");
    }
}

fn start_by(o: &infantry_board::LocalOrchestrator, id: &str, who: &str) {
    block_on(o.start(TicketStartRequest { id: id.into(), started_by: Some(who.into()) })).unwrap();
}

fn notes(f: &Fixture) -> Vec<String> {
    f.env.notified.lock().unwrap().clone()
}

/// AUTO-REPORT-1: every way a worker finishes mails the starter one line, and `board.autoReport=false` silences it.
#[test]
fn the_starter_is_mailed_on_done_on_a_clean_end_and_on_an_end_without_a_report() {
    use infantry_orchestrator_api::dto::{CommentAdd, CommentAuthor};
    use infantry_orchestrator_api::DoneReport;
    let (f, _l, _d, o) = setup("work-autoreport", StaticEnv { lane_cap: 10, ..kotlin_daemon_env() }, Duration::ZERO);
    let ids = tickets(&f, 3);
    for id in &ids {
        start_by(&o, id, "starter-1");
    }
    // 1. the explicit done
    block_on(o.done(TicketDone { ticket_id: ids[0].clone(), session_id: "s1".into(), summary: "commit abc: did it".into(), stop_worker: true })).unwrap();
    assert!(notes(&f).iter().any(|n| n.starts_with("starter-1 ") && n.contains(&ids[0]) && n.contains("Work 1") && n.contains("commit abc")), "{:?}", notes(&f));
    // 2. a clean end after a done comment (the done protocol closes it)
    let author = CommentAuthor { kind: CommentAuthor::SESSION.into(), session_id: Some("s2".into()), agent_id: None };
    block_on(o.comment_add(CommentAdd { ticket_id: ids[1].clone(), body: "commit def: finished".into(), kind: Some("done".into()), state: None, author, wake_kind: None, wake_key: None })).unwrap();
    block_on(o.on_done(DoneReport { ticket_id: ids[1].clone(), session_id: "s2".into(), outcome: "SUCCEEDED".into(), detail: None, background_running: 0 })).unwrap();
    assert_eq!(block_on(o.get(ids[1].clone())).unwrap().status, "done");
    assert!(notes(&f).iter().any(|n| n.contains(&ids[1]) && n.contains("commit def")), "{:?}", notes(&f));
    // 3. a clean end with no done comment
    let before = notes(&f).len();
    block_on(o.on_done(DoneReport { ticket_id: ids[2].clone(), session_id: "s3".into(), outcome: "SUCCEEDED".into(), detail: None, background_running: 0 })).unwrap();
    assert!(notes(&f)[before..].iter().any(|n| n.contains(&ids[2]) && n.contains("without a done comment")), "{:?}", notes(&f));
    // 4. the setting off: no mail
    f.board.db().write(|c| c.execute("INSERT INTO settings(key, value, updated_at) VALUES ('board.autoReport', 'false', 1)", [])).unwrap();
    f.env.push_ids(&["ticket-00000004"]);
    save(&f.orch, r#"{"title":"Work 4","projectId":"p1","agentId":"role:backend"}"#);
    start_by(&o, "ticket-00000004", "starter-1");
    let before = notes(&f).len();
    block_on(o.done(TicketDone { ticket_id: "ticket-00000004".into(), session_id: "s4".into(), summary: "x".into(), stop_worker: true })).unwrap();
    assert_eq!(notes(&f).len(), before, "autoReport off must stay silent");
}

/// `lanes.maxInProgress` (default 1) holds even with a wide lane cap, and a FAILED run leaves its ticket in_progress,
/// which keeps the slot: the lane's next ticket does not start beside it.
#[test]
fn max_in_progress_holds_and_a_failed_run_keeps_its_slot() {
    use infantry_orchestrator_api::DoneReport;
    let env = StaticEnv { lane_cap: 10, max_in_progress: 1, ..kotlin_daemon_env() };
    let (f, _l, _d, o) = setup("work-maxinprog", env, Duration::ZERO);
    let ids = tickets(&f, 3);
    start(&o, &ids[0]);
    for id in &ids[1..] {
        let b = start(&o, id);
        assert_eq!(b.ticket.start.as_ref().map(|s| s.reason.as_str()), Some(reason::QUEUED), "{:?}", b.ticket.start);
    }
    // The worker's run fails (no done comment): the ticket stays in_progress and nothing else starts.
    block_on(o.on_done(DoneReport { ticket_id: ids[0].clone(), session_id: "s1".into(), outcome: "FAILED".into(), detail: None, background_running: 0 })).unwrap();
    assert_eq!(block_on(o.get(ids[0].clone())).unwrap().status, "in_progress");
    for id in &ids[1..] {
        assert_eq!(block_on(o.get(id.clone())).unwrap().status, "todo", "a failed run must not free its slot");
    }
    // A restart of the failed ticket itself is still allowed.
    let r = start(&o, &ids[0]);
    assert_eq!(r.ticket.start.as_ref().map(|s| s.reason.as_str()), Some(reason::STARTED), "{:?}", r.ticket.start);
}

/// hooks.json autoStart=false: a done starts no next ticket.
#[test]
fn auto_start_off_starts_nothing_after_a_done() {
    let env = StaticEnv { auto_start_off: true, ..kotlin_daemon_env() };
    let (f, _l, _d, o) = setup("work-autostart-off", env, Duration::ZERO);
    let ids = tickets(&f, 2);
    start(&o, &ids[0]);
    start(&o, &ids[1]);
    let done = block_on(o.done(TicketDone { ticket_id: ids[0].clone(), session_id: "s1".into(), summary: "commit abc: x".into(), stop_worker: true })).unwrap();
    assert!(done.next.is_none());
    assert_eq!(block_on(o.get(ids[1].clone())).unwrap().status, "todo");
}

// ---- PIPE-CLOSE-1: the one-call close ------------------------------------------------------------------------

/// Cuts a fake worktree per ticket; `release` answers what `verdict` holds (Ok = clean and merged) and records removals.
struct FakeWorkspaces {
    verdict: Mutex<Result<(), String>>,
    released: Mutex<Vec<String>>,
}

impl WorkspaceProvider for FakeWorkspaces {
    fn wants_worktree(&self, _p: Option<&str>) -> bool {
        true
    }
    fn ensure(&self, r: &WorkspaceRequest) -> Result<Workspace, String> {
        Ok(Workspace { branch: format!("ticket/{}", r.ticket_id), base: "main".into(), path: format!("/wt/{}", r.ticket_id), note: None })
    }
    fn release(&self, r: &WorkspaceRelease) -> Result<Option<String>, String> {
        self.verdict.lock().unwrap().clone()?;
        if !r.dry_run {
            self.released.lock().unwrap().push(r.branch.clone());
        }
        Ok(Some(format!("/wt/{}", r.branch)))
    }
}

fn close_setup(tag: &str, verdict: Result<(), String>) -> (Fixture, Arc<FakeLauncher>, Arc<FakeWorkspaces>, infantry_board::LocalOrchestrator) {
    let f = fixture(tag, kotlin_daemon_env());
    let launcher = Arc::new(FakeLauncher::default());
    let ws = Arc::new(FakeWorkspaces { verdict: Mutex::new(verdict), released: Mutex::new(Vec::new()) });
    let d = Dispatcher::new(f.board.clone(), ws.clone(), launcher.clone(), f.bus.clone());
    let o = infantry_board::LocalOrchestrator::with_dispatcher(d, f.bus.clone());
    (f, launcher, ws, o)
}

fn close(id: &str) -> TicketClose {
    TicketClose { ticket_id: id.into(), summary: Some("commit abc: merged".into()), keep_worktree: false, closed_by: Some("lead".into()) }
}

#[test]
fn close_is_done_worker_gone_worktree_gone_and_the_next_starts() {
    let (f, launcher, ws, o) = close_setup("close-ok", Ok(()));
    let ids = tickets(&f, 2);
    start(&o, &ids[0]);
    start(&o, &ids[1]); // queued behind the one-worker lane
    let c = block_on(o.close(close(&ids[0]))).unwrap();
    assert_eq!(c.ticket.status, "done");
    assert_eq!(c.worker_removed.as_deref(), Some("s1"));
    assert_eq!(launcher.removed.lock().unwrap().as_slice(), ["s1"]);
    assert!(c.worktree_removed.is_some(), "{c:?}");
    assert_eq!(ws.released.lock().unwrap().len(), 1);
    assert_eq!(c.next.as_ref().map(|n| n.id.as_str()), Some(ids[1].as_str()), "the lane's next ticket started");
    assert_eq!(block_on(o.get(ids[1].clone())).unwrap().status, "in_progress");
    assert!(c.warnings.is_empty(), "{:?}", c.warnings);
}

#[test]
fn close_refuses_an_unmerged_worktree_and_changes_nothing() {
    let (f, launcher, ws, o) = close_setup("close-unmerged", Err("branch 'x' is not merged into 'main'".into()));
    let ids = tickets(&f, 2);
    start(&o, &ids[0]);
    start(&o, &ids[1]);
    let e = block_on(o.close(close(&ids[0]))).unwrap_err();
    assert!(e.to_string().contains("not merged"), "{e}");
    let t = block_on(o.get(ids[0].clone())).unwrap();
    assert_eq!((t.status.as_str(), t.session_id.as_deref()), ("in_progress", Some("s1")), "the ticket and its worker are untouched");
    assert!(launcher.removed.lock().unwrap().is_empty() && ws.released.lock().unwrap().is_empty());
    assert_eq!(block_on(o.get(ids[1].clone())).unwrap().status, "todo", "the next ticket did not start");
    // keepWorktree closes anyway and leaves the worktree to the sweep.
    let mut keep = close(&ids[0]);
    keep.keep_worktree = true;
    let c = block_on(o.close(keep)).unwrap();
    assert_eq!((c.ticket.status.as_str(), c.worktree_removed), ("done", None));
    assert!(ws.released.lock().unwrap().is_empty());
}

#[test]
fn close_of_a_backlog_ticket_is_refused() {
    let (f, _l, _w, o) = close_setup("close-backlog", Ok(()));
    f.env.push_ids(&["ticket-00000001"]);
    save(&f.orch, r#"{"title":"Later","projectId":"p1","status":"backlog"}"#);
    assert!(block_on(o.close(close("ticket-00000001"))).is_err());
}

// ---- PIPE-RESTART-1: a dead worker is told to the lead; restart resumes, or starts a fresh session on the same worktree ----

fn restart_setup(tag: &str, env: StaticEnv) -> (Fixture, Arc<FakeLauncher>, infantry_board::LocalOrchestrator) {
    let (f, l, _d, o) = restart_setup_d(tag, env);
    (f, l, o)
}

fn restart_setup_d(tag: &str, env: StaticEnv) -> (Fixture, Arc<FakeLauncher>, Arc<Dispatcher>, infantry_board::LocalOrchestrator) {
    let f = fixture(tag, StaticEnv { lane_cap: 10, max_in_progress: 10, ..env });
    let launcher = Arc::new(FakeLauncher::default());
    let ws = Arc::new(FakeWorkspaces { verdict: Mutex::new(Ok(())), released: Mutex::new(Vec::new()) });
    let d = Dispatcher::new(f.board.clone(), ws, launcher.clone(), f.bus.clone());
    let o = infantry_board::LocalOrchestrator::with_dispatcher(d.clone(), f.bus.clone());
    (f, launcher, d, o)
}

fn restart(id: &str, fresh: bool) -> TicketRestart {
    TicketRestart { ticket_id: id.into(), fresh, restarted_by: Some("lead-1".into()) }
}

/// The worker is killed: its run ends FAILED, as the daemon reports it.
fn kill(launcher: &FakeLauncher, o: &infantry_board::LocalOrchestrator, ticket: &str, session: &str, detail: &str) {
    use infantry_orchestrator_api::DoneReport;
    launcher.live.lock().unwrap().insert(session.to_string(), WorkerState::Ended);
    block_on(o.on_done(DoneReport { ticket_id: ticket.into(), session_id: session.into(), outcome: "FAILED".into(), detail: Some(detail.into()), background_running: 0 })).unwrap();
}

fn ticket_notes(o: &infantry_board::LocalOrchestrator, id: &str) -> Vec<String> {
    use infantry_orchestrator_api::CommentQuery;
    block_on(o.comments(CommentQuery { ticket_id: id.into(), which: None, kind: None, limit: None })).unwrap().into_iter().map(|c| c.body).collect()
}

#[test]
fn a_dead_worker_is_mailed_to_the_lead_with_the_command_that_recovers_it() {
    let (f, l, o) = restart_setup("restart-mail", StaticEnv { lead_mail: true, ..kotlin_daemon_env() });
    let ids = tickets(&f, 1);
    start_by(&o, &ids[0], "lead-1");
    kill(&l, &o, &ids[0], "s1", "the interactive claude exited (code 1)");
    let failed = f.env.failed.lock().unwrap().clone();
    assert_eq!(failed.len(), 1, "{failed:?}");
    assert!(failed[0].starts_with(&format!("lead-1 {} s1 FAILED", ids[0])), "{failed:?}");
    assert!(notes(&f).is_empty(), "a lead that was mailed gets no second urgent line: {:?}", notes(&f));
    assert!(ticket_notes(&o, &ids[0]).iter().any(|b| b.contains("[AUTOMATIC FAILURE]") && b.contains("ticket_restart")), "the ticket comment names the way back");
    assert_eq!(block_on(o.get(ids[0].clone())).unwrap().status, "in_progress", "a dead worker leaves the ticket where it was");
}

/// A worker whose process dies while it is at rest (no turn running) has no turn end to carry the news.
#[test]
fn a_worker_that_dies_at_rest_is_told_to_the_lead_and_can_be_restarted() {
    use infantry_orchestrator_api::DoneReport;
    let (f, l, d, o) = restart_setup_d("restart-atrest", StaticEnv { lead_mail: true, ..kotlin_daemon_env() });
    let ids = tickets(&f, 1);
    start_by(&o, &ids[0], "lead-1");
    // The worker finished a turn (clean end, no done report), then its process is killed while it waits.
    block_on(o.on_done(DoneReport { ticket_id: ids[0].clone(), session_id: "s1".into(), outcome: "SUCCEEDED".into(), detail: None, background_running: 0 })).unwrap();
    let clean = f.env.failed.lock().unwrap().clone();
    assert!(clean[0].contains("SUCCEEDED"), "{clean:?}");
    l.live.lock().unwrap().insert("s1".into(), WorkerState::Ended);
    let died = DoneReport { ticket_id: ids[0].clone(), session_id: "s1".into(), outcome: "FAILED".into(), detail: Some("the worker's process died while it was idle (exit code -1)".into()), background_running: 0 };
    d.on_worker_died(&died).unwrap();
    let failed = f.env.failed.lock().unwrap().clone();
    assert_eq!(failed.len(), 2, "{failed:?}");
    assert!(failed[1].starts_with(&format!("lead-1 {} s1 FAILED", ids[0])), "{failed:?}");
    // Not the ticket's current worker (another ticket, or an unknown session): nothing is said.
    d.on_worker_died(&DoneReport { session_id: "s9".into(), ..died.clone() }).unwrap();
    d.on_worker_died(&DoneReport { ticket_id: "ticket-00000099".into(), ..died.clone() }).unwrap();
    assert_eq!(f.env.failed.lock().unwrap().len(), 2);
    // The restart works on it: the session reads gone, so it is not refused as live.
    let a = block_on(o.restart(restart(&ids[0], true))).unwrap();
    assert_eq!((a.mode.as_str(), a.session_id.as_deref()), ("fresh", Some("s2")), "{a:?}");
}

/// Found live: a worker ended a turn clean (its run closed), the lead prompted it again, and it died mid-turn.
#[test]
fn a_worker_that_ended_clean_and_dies_in_a_later_turn_is_told_once() {
    use infantry_orchestrator_api::DoneReport;
    let (f, _l, o) = restart_setup("restart-later", StaticEnv { lead_mail: true, ..kotlin_daemon_env() });
    let ids = tickets(&f, 1);
    start_by(&o, &ids[0], "lead-1");
    let end = |outcome: &str, detail: Option<&str>| {
        block_on(o.on_done(DoneReport { ticket_id: ids[0].clone(), session_id: "s1".into(), outcome: outcome.into(), detail: detail.map(String::from), background_running: 0 })).unwrap()
    };
    end("SUCCEEDED", None);
    assert_eq!(f.env.failed.lock().unwrap().len(), 1);
    end("FAILED", Some("the interactive claude exited (code -1)"));
    let ended = block_on(o.get(ids[0].clone())).unwrap().last_run.unwrap();
    assert_eq!(ended.outcome.as_deref(), Some("FAILED"), "the run that read SUCCEEDED now records how the session really ended: {ended:?}");
    let failed = f.env.failed.lock().unwrap().clone();
    assert_eq!(failed.len(), 2, "{failed:?}");
    assert!(failed[1].starts_with(&format!("lead-1 {} s1 FAILED", ids[0])), "{failed:?}");
    assert!(failed[1].contains("is down"), "{failed:?}");
    let notes = ticket_notes(&o, &ids[0]);
    assert!(notes.iter().any(|b| b.contains("died in a later turn") && b.contains("ticket_restart")), "{notes:?}");
    // The same death reported again says nothing more; a stop the board asked for (KILLED_*) says nothing either.
    end("FAILED", Some("the interactive claude exited (code -1)"));
    end("KILLED_GRACEFUL", None);
    assert_eq!(f.env.failed.lock().unwrap().len(), 2);
}

#[test]
fn a_clean_end_without_a_done_report_does_not_say_the_worker_is_down() {
    use infantry_orchestrator_api::DoneReport;
    let (f, _l, o) = restart_setup("restart-clean", StaticEnv { lead_mail: true, ..kotlin_daemon_env() });
    let ids = tickets(&f, 1);
    start_by(&o, &ids[0], "lead-1");
    block_on(o.on_done(DoneReport { ticket_id: ids[0].clone(), session_id: "s1".into(), outcome: "SUCCEEDED".into(), detail: None, background_running: 0 })).unwrap();
    let notes = ticket_notes(&o, &ids[0]);
    assert!(notes.iter().any(|b| b.contains("without a done comment") && b.contains("Message it to finish")), "{notes:?}");
    assert!(!notes.iter().any(|b| b.contains("ticket_restart")), "a live idle worker cannot be restarted, so the comment must not offer it: {notes:?}");
}

#[test]
fn a_dead_worker_with_no_mail_path_still_reaches_the_lead_by_the_urgent_line() {
    let (f, l, o) = restart_setup("restart-nomail", kotlin_daemon_env());
    let ids = tickets(&f, 1);
    start_by(&o, &ids[0], "lead-1");
    kill(&l, &o, &ids[0], "s1", "gone");
    assert_eq!(f.env.failed.lock().unwrap().len(), 1);
    assert!(notes(&f).iter().any(|n| n.starts_with("lead-1 ") && n.contains(&ids[0])), "{:?}", notes(&f));
}

#[test]
fn restart_resumes_the_dead_session_in_place() {
    let (f, l, o) = restart_setup("restart-resume", StaticEnv { lead_mail: true, resume_window_ms: 60_000, ..kotlin_daemon_env() });
    l.resumable.store(true, Ordering::SeqCst);
    let ids = tickets(&f, 1);
    start_by(&o, &ids[0], "lead-1");
    kill(&l, &o, &ids[0], "s1", "killed");
    let a = block_on(o.restart(restart(&ids[0], false))).unwrap();
    assert_eq!((a.mode.as_str(), a.session_id.as_deref(), a.previous_session_id.as_deref()), ("resumed", Some("s1"), Some("s1")), "{a:?}");
    let prompts = l.prompts.lock().unwrap().clone();
    assert_eq!(prompts.len(), 1);
    assert!(prompts[0].1.contains("[RESTART]") && prompts[0].1.contains("git log"), "{}", prompts[0].1);
    assert_eq!(l.n.load(Ordering::SeqCst), 1, "no second session was launched");
    assert_eq!(a.ticket.session_id.as_deref(), Some("s1"));
    assert!(ticket_notes(&o, &ids[0]).iter().any(|b| b.contains("resumed session s1")), "{:?}", ticket_notes(&o, &ids[0]));
    // The resumed session finishes with a done comment: its end closes the ticket like any run's.
    use infantry_orchestrator_api::dto::{CommentAdd, CommentAuthor};
    use infantry_orchestrator_api::DoneReport;
    let author = CommentAuthor { kind: CommentAuthor::SESSION.into(), session_id: Some("s1".into()), agent_id: None };
    block_on(o.comment_add(CommentAdd { ticket_id: ids[0].clone(), body: "commit abc: finished".into(), kind: Some("done".into()), state: None, author, wake_kind: None, wake_key: None })).unwrap();
    block_on(o.on_done(DoneReport { ticket_id: ids[0].clone(), session_id: "s1".into(), outcome: "SUCCEEDED".into(), detail: None, background_running: 0 })).unwrap();
    assert_eq!(block_on(o.get(ids[0].clone())).unwrap().status, "done", "the resumed run was open again, so its end was handled");
}

#[test]
fn a_resume_that_dies_inside_the_window_starts_a_fresh_session_on_the_same_worktree() {
    let (f, l, o) = restart_setup("restart-fallback", StaticEnv { lead_mail: true, resume_window_ms: 60_000, ..kotlin_daemon_env() });
    l.resumable.store(true, Ordering::SeqCst);
    let ids = tickets(&f, 1);
    start_by(&o, &ids[0], "lead-1");
    kill(&l, &o, &ids[0], "s1", "killed");
    let a = block_on(o.restart(restart(&ids[0], false))).unwrap();
    assert_eq!(a.mode, "resumed");
    // The resumed session dies at once (the harness exits 1): a fresh session replaces it.
    kill(&l, &o, &ids[0], "s1", "the interactive claude exited (code 1)");
    let t = block_on(o.get(ids[0].clone())).unwrap();
    assert_eq!((t.status.as_str(), t.session_id.as_deref()), ("in_progress", Some("s2")), "a fresh session took over");
    assert_eq!(l.state("s1"), WorkerState::Ended);
    assert_eq!(l.state("s2"), WorkerState::Busy);
    let paths = l.paths.lock().unwrap().clone();
    assert_eq!(paths.len(), 2);
    assert_eq!(paths[0], paths[1], "the fresh session runs in the SAME worktree: {paths:?}");
    assert_eq!(paths[1].as_deref(), Some(format!("/wt/{}", ids[0]).as_str()));
    let briefs = l.briefs.lock().unwrap().clone();
    assert!(briefs[1].1.contains("Work 1") && briefs[1].1.contains("Continue from your last commit"), "the ticket brief plus the continue note: {}", briefs[1].1);
    assert_eq!(f.env.failed.lock().unwrap().len(), 1, "only the original death was reported as a failure; the dead resume was replaced, not mailed");
    assert!(ticket_notes(&o, &ids[0]).iter().any(|b| b.contains("fresh session s2") && b.contains("resumed session s1 died")), "{:?}", ticket_notes(&o, &ids[0]));
}

#[test]
fn a_resume_that_dies_after_the_window_is_a_plain_failure() {
    let (f, l, o) = restart_setup("restart-late", StaticEnv { lead_mail: true, resume_window_ms: 1, ..kotlin_daemon_env() });
    l.resumable.store(true, Ordering::SeqCst);
    let ids = tickets(&f, 1);
    start_by(&o, &ids[0], "lead-1");
    kill(&l, &o, &ids[0], "s1", "killed");
    block_on(o.restart(restart(&ids[0], false))).unwrap();
    std::thread::sleep(Duration::from_millis(30));
    kill(&l, &o, &ids[0], "s1", "died later");
    assert_eq!(block_on(o.get(ids[0].clone())).unwrap().session_id.as_deref(), Some("s1"), "no fresh session: the resume had worked for a while");
    assert_eq!(l.n.load(Ordering::SeqCst), 1);
    assert_eq!(f.env.failed.lock().unwrap().len(), 2, "the lead is told again");
}

#[test]
fn a_session_that_cannot_take_a_turn_falls_back_to_a_fresh_session_at_once() {
    let (f, l, o) = restart_setup("restart-noresume", StaticEnv { lead_mail: true, ..kotlin_daemon_env() });
    let ids = tickets(&f, 1);
    start_by(&o, &ids[0], "lead-1");
    kill(&l, &o, &ids[0], "s1", "killed");
    let a = block_on(o.restart(restart(&ids[0], false))).unwrap();
    assert_eq!((a.mode.as_str(), a.session_id.as_deref(), a.previous_session_id.as_deref()), ("fresh", Some("s2"), Some("s1")), "{a:?}");
    assert_eq!(block_on(o.get(ids[0].clone())).unwrap().session_id.as_deref(), Some("s2"));
    assert!(l.briefs.lock().unwrap()[1].1.contains("Continue from your last commit"));
}

#[test]
fn fresh_skips_the_resume() {
    let (f, l, o) = restart_setup("restart-fresh", StaticEnv { lead_mail: true, ..kotlin_daemon_env() });
    l.resumable.store(true, Ordering::SeqCst);
    let ids = tickets(&f, 1);
    start_by(&o, &ids[0], "lead-1");
    kill(&l, &o, &ids[0], "s1", "killed");
    let a = block_on(o.restart(restart(&ids[0], true))).unwrap();
    assert_eq!((a.mode.as_str(), a.session_id.as_deref()), ("fresh", Some("s2")), "{a:?}");
    assert!(l.prompts.lock().unwrap().is_empty(), "the dead session was not asked to resume");
}

#[test]
fn restart_refuses_a_live_worker_a_todo_ticket_and_an_unknown_ticket() {
    let (f, l, o) = restart_setup("restart-refuse", StaticEnv { lead_mail: true, ..kotlin_daemon_env() });
    let ids = tickets(&f, 2);
    start_by(&o, &ids[0], "lead-1");
    let live = block_on(o.restart(restart(&ids[0], false))).unwrap_err().to_string();
    assert!(live.contains("live worker") && live.contains("s1"), "{live}");
    let todo = block_on(o.restart(restart(&ids[1], false))).unwrap_err().to_string();
    assert!(todo.contains("only an in_progress ticket restarts"), "{todo}");
    assert!(block_on(o.restart(restart("nope-nope", false))).is_err());
    assert_eq!(l.n.load(Ordering::SeqCst), 1, "a refusal launches nothing");
}

/// PIPE-WAKE-1: a `ticket_move` (what `infantry ticket start` sends) and a `ticket_start` frame that carry the
/// caller's session id record it as `started_by`, so the done notice reaches that session.
#[test]
fn a_start_frame_with_a_session_id_records_started_by_and_the_done_notice_reaches_it() {
    use infantry_core::frames::{FrameCtx, FrameUnit};
    use infantry_protocol::Envelope;
    let (f, _l, d, o) = setup("work-frame-starter", StaticEnv { lane_cap: 10, ..kotlin_daemon_env() }, Duration::ZERO);
    let ids = tickets(&f, 2);
    let unit = infantry_board::BoardUnit::with_dispatcher(d);
    let ctx = FrameCtx { conn_id: 1, owner: true, bus: f.bus.clone() };
    let mut mv = Envelope::new("ticket_move", format!(r#"{{"id":"{}","status":"in_progress"}}"#, ids[0]));
    mv.session_id = Some("lead-1".into());
    unit.handle(&mv, &ctx).map_err(|e| e.0).unwrap();
    let mut st = Envelope::new("ticket_start", format!(r#"{{"id":"{}"}}"#, ids[1]));
    st.session_id = Some("lead-1".into());
    unit.handle(&st, &ctx).map_err(|e| e.0).unwrap();
    for (i, id) in ids.iter().enumerate() {
        block_on(o.done(TicketDone { ticket_id: id.clone(), session_id: format!("s{}", i + 1), summary: format!("commit abc{i}: ok"), stop_worker: true })).unwrap();
        assert!(notes(&f).iter().any(|n| n.starts_with("lead-1 ") && n.contains(&format!("commit abc{i}"))), "{id}: {:?}", notes(&f));
    }
}

/// PIPE-WAKE-1: a worker that moves its own ticket to done (the live path: `ticket_move`, not `ticket_done`) mails the starter.
#[test]
fn a_worker_moving_its_ticket_to_done_mails_the_starter() {
    let (f, _l, _d, o) = setup("work-move-done-mail", StaticEnv { lane_cap: 10, ..kotlin_daemon_env() }, Duration::ZERO);
    let ids = tickets(&f, 1);
    start_by(&o, &ids[0], "lead-1");
    block_on(o.move_lane(infantry_orchestrator_api::dto::TicketMove { id: ids[0].clone(), status: Some("done".into()), position: None, wont_fix: None })).unwrap();
    assert!(notes(&f).iter().any(|n| n.starts_with("lead-1 ") && n.contains(&ids[0]) && n.contains("is done")), "{:?}", notes(&f));
}
/// PIPE-ROLE-1: a ticket with no role is refused without a default role, and starts with it when one is set.
#[test]
fn a_ticket_with_no_role_starts_with_the_default_role() {
    for (tag, default, ok) in [("work-norole-refused", None, false), ("work-norole-default", Some("custom".to_string()), true)] {
        let env = StaticEnv { default_role: default, ..kotlin_daemon_env() };
        let (f, _l, _d, o) = setup(tag, env, Duration::ZERO);
        f.env.push_ids(&["ticket-00000001"]);
        save(&f.orch, r#"{"title":"No role","projectId":"p1"}"#);
        let a = start(&o, "ticket-00000001");
        let s = a.ticket.start.as_ref().map(|s| s.reason.as_str());
        if ok {
            assert_eq!(s, Some(reason::STARTED), "{:?}", a.ticket.start);
        } else {
            assert_ne!(s, Some(reason::STARTED), "{:?}", a.ticket.start);
        }
    }
}

#[test]
fn a_clean_end_while_background_tasks_run_is_no_failure_until_they_end() {
    use infantry_orchestrator_api::DoneReport;
    let (f, _l, o) = restart_setup("restart-bg", StaticEnv { lead_mail: true, ..kotlin_daemon_env() });
    let ids = tickets(&f, 1);
    start_by(&o, &ids[0], "lead-1");
    let report = |n| DoneReport { ticket_id: ids[0].clone(), session_id: "s1".into(), outcome: "SUCCEEDED".into(), detail: None, background_running: n };
    block_on(o.on_done(report(2))).unwrap();
    assert!(!ticket_notes(&o, &ids[0]).iter().any(|b| b.contains("without a done comment")), "a worker with background tasks is still working");
    block_on(o.on_done(report(0))).unwrap();
    assert!(ticket_notes(&o, &ids[0]).iter().any(|b| b.contains("without a done comment")), "after they all end, the missing done comment fails it");
}

/// DONE-ONCE-1: drains the bus and counts the `ticket.done` and `ticket.moved`-to-done events (the lane hook DONE-PING runs on).
fn completions(rx: &mut tokio::sync::broadcast::Receiver<infantry_core::bus::NamedEvent>) -> (usize, usize) {
    use infantry_orchestrator_api::hooks;
    let (mut done, mut moved) = (0, 0);
    while let Ok(e) = rx.try_recv() {
        if e.name == hooks::TICKET_DONE {
            done += 1;
        }
        if e.name == hooks::TICKET_MOVED && e.payload["to"] == "done" {
            moved += 1;
        }
    }
    (done, moved)
}

/// DONE-ONCE-1: complete:true completes in one step with one event; later done comments and moves are notes, silent.
#[test]
fn a_completing_done_comment_moves_once_and_later_dones_are_notes() {
    use infantry_orchestrator_api::dto::{status, CommentAuthor, TicketMove};
    use infantry_orchestrator_api::DoneReport;
    let (f, _l, d, o) = setup("work-done-once", StaticEnv { lead_mail: true, ..kotlin_daemon_env() }, Duration::ZERO);
    let ids = tickets(&f, 1);
    start_by(&o, &ids[0], "lead-1");
    let mut rx = f.bus.subscribe_events();
    let worker = CommentAuthor { kind: CommentAuthor::SESSION.into(), session_id: Some("s1".into()), agent_id: None };
    // 1. complete:true: the done comment and the move, ONE completion event, one lane move (one DONE-PING).
    let c = d.complete(&ids[0], "commit abc: did it", worker.clone(), true).unwrap();
    assert!(c.completed && c.already.is_none());
    assert_eq!(c.comment.kind, "done");
    assert_eq!(block_on(o.get(ids[0].clone())).unwrap().status, status::DONE);
    assert_eq!(completions(&mut rx), (1, 1), "one completion event, one lane move");
    // 2. a second done comment (plain or complete): the "already completed" answer, kept as a note, no event.
    for complete in [false, true] {
        let again = d.complete(&ids[0], "more detail", worker.clone(), complete).unwrap();
        let line = again.already.expect("already completed answer");
        assert!(line.starts_with("already completed ") && line.ends_with("by s1"), "{line}");
        assert!(!again.completed);
        assert_eq!(again.comment.kind, "note");
        assert_eq!(again.comment.body, "more detail");
    }
    // 3. a move to done on the done ticket: no event.
    d.move_lane(TicketMove { id: ids[0].clone(), status: Some(status::DONE.into()), position: None, wont_fix: None }, None).unwrap();
    assert_eq!(completions(&mut rx), (0, 0), "later done comments and moves send nothing");
    // 4. the worker's clean end after complete:true is no "ended without a done comment" failure.
    block_on(o.on_done(DoneReport { ticket_id: ids[0].clone(), session_id: "s1".into(), outcome: "SUCCEEDED".into(), detail: None, background_running: 0 })).unwrap();
    assert!(!ticket_notes(&o, &ids[0]).iter().any(|b| b.contains("without a done comment")), "{:?}", ticket_notes(&o, &ids[0]));
    assert!(!notes(&f).iter().any(|n| n.contains("without a done comment")), "{:?}", notes(&f));
}

/// DONE-ONCE-1: a ticket the lead closes itself carries `via: close`, so DONE-PING skips it.
#[test]
fn a_close_marks_its_move_so_done_ping_skips_it() {
    use infantry_orchestrator_api::hooks;
    let (f, _l, _ws, o) = close_setup("work-close-via", Ok(()));
    let ids = tickets(&f, 1);
    start(&o, &ids[0]);
    let mut rx = f.bus.subscribe_events();
    block_on(o.close(close(&ids[0]))).unwrap();
    let mut via = Vec::new();
    while let Ok(e) = rx.try_recv() {
        if e.name == hooks::TICKET_MOVED {
            via.push(e.payload["via"].clone());
        }
    }
    assert_eq!(via, vec![serde_json::json!("close")]);
}
