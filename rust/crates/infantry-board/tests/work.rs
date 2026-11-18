// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! The work half, light: a start puts the ticket in_progress, a done starts the lane's next ticket, a lane cap queues,
//! and concurrent starts never wait on each other.
mod common;

use std::collections::HashMap;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use common::*;
use infantry_board::dispatch::reason;
use infantry_board::env::StaticEnv;
use infantry_board::Dispatcher;
use infantry_orchestrator_api::{
    LaunchRequest, LaunchedSession, NoWorkspaces, Orchestrator, SessionLauncher, StartOrigin, TicketDone, TicketStartRequest, WorkerState,
};

/// A launcher that hands out `s1`, `s2`, ... after `delay`, and remembers which are still running.
#[derive(Default)]
struct FakeLauncher {
    delay: Duration,
    n: AtomicUsize,
    live: Mutex<HashMap<String, WorkerState>>,
    briefs: Mutex<Vec<(String, String)>>,
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
