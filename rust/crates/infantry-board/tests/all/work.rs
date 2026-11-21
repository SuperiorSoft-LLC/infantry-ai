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
    LaunchRequest, LaunchedSession, NoWorkspaces, Orchestrator, SessionLauncher, StartOrigin, TicketClose, TicketDone, TicketStartRequest, WorkerState,
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
}

impl SessionLauncher for FakeLauncher {
    fn launch(&self, req: &LaunchRequest) -> Result<LaunchedSession, String> {
        std::thread::sleep(self.delay);
        let id = format!("s{}", self.n.fetch_add(1, Ordering::SeqCst) + 1);
        self.live.lock().unwrap().insert(id.clone(), WorkerState::Busy);
        self.briefs.lock().unwrap().push((req.ticket_id.clone(), req.brief.clone()));
        Ok(LaunchedSession { session_id: id, note: None, workspace: None })
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
    block_on(o.comment_add(CommentAdd { ticket_id: ids[1].clone(), body: "commit def: finished".into(), kind: Some("done".into()), state: None, author })).unwrap();
    block_on(o.on_done(DoneReport { ticket_id: ids[1].clone(), session_id: "s2".into(), outcome: "SUCCEEDED".into(), detail: None })).unwrap();
    assert_eq!(block_on(o.get(ids[1].clone())).unwrap().status, "done");
    assert!(notes(&f).iter().any(|n| n.contains(&ids[1]) && n.contains("commit def")), "{:?}", notes(&f));
    // 3. a clean end with no done comment
    let before = notes(&f).len();
    block_on(o.on_done(DoneReport { ticket_id: ids[2].clone(), session_id: "s3".into(), outcome: "SUCCEEDED".into(), detail: None })).unwrap();
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
    block_on(o.on_done(DoneReport { ticket_id: ids[0].clone(), session_id: "s1".into(), outcome: "FAILED".into(), detail: None })).unwrap();
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
