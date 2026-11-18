// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
#![allow(dead_code)]
use std::sync::Arc;

use infantry_board::env::{ContextSettings, ProviderFacts, StaticEnv};
use infantry_board::schema::TempDb;
use infantry_board::{Board, LocalOrchestrator};
use infantry_core::bus::EventBus;
use infantry_core::db::Db;
use infantry_orchestrator_api::{Orchestrator, TicketMove, TicketSave};

pub struct Fixture {
    pub tmp: TempDb,
    pub env: Arc<StaticEnv>,
    pub board: Arc<Board>,
    pub bus: EventBus,
    pub orch: LocalOrchestrator,
}

/// The env a freshly started Kotlin daemon (temp state dir, stub-free) shows tickets with: sessions on,
/// default harness `claude`, the project switches unset, `limits.session.defaultContextTokens` 500k.
pub fn kotlin_daemon_env() -> StaticEnv {
    StaticEnv {
        sessions_on: true,
        default_provider: Some("claude".into()),
        providers: vec![ProviderFacts {
            id: "claude".into(),
            context_limit_support: Some("supported".into()),
            context_limit_how: None,
            effort_support: Some("supported".into()),
            effort_how: None,
            ..Default::default()
        }],
        context: Some(ContextSettings::default()),
        default_context_limit: Some(500_000),
        ..Default::default()
    }
}

pub fn fixture(tag: &str, env: StaticEnv) -> Fixture {
    let tmp = TempDb::new(tag);
    let db = Arc::new(Db::open(&tmp.path).expect("open temp db"));
    let env = Arc::new(env);
    env.set_now(1_700_000_000_000);
    let board = Arc::new(Board::new(db, env.clone()));
    let bus = EventBus::new(256);
    let orch = LocalOrchestrator::new(board.clone(), bus.clone());
    Fixture { tmp, env, board, bus, orch }
}

pub fn block_on<F: std::future::Future>(f: F) -> F::Output {
    // The local orchestrator never awaits anything real: a no-op waker suffices.
    use std::task::{Context, Poll, RawWaker, RawWakerVTable, Waker};
    fn raw() -> RawWaker {
        fn clone(_: *const ()) -> RawWaker {
            raw()
        }
        fn noop(_: *const ()) {}
        static VT: RawWakerVTable = RawWakerVTable::new(clone, noop, noop, noop);
        RawWaker::new(std::ptr::null(), &VT)
    }
    let waker = unsafe { Waker::from_raw(raw()) };
    let mut cx = Context::from_waker(&waker);
    let mut f = std::pin::pin!(f);
    loop {
        if let Poll::Ready(v) = f.as_mut().poll(&mut cx) {
            return v;
        }
    }
}

pub fn save(o: &LocalOrchestrator, json: &str) -> infantry_orchestrator_api::TicketAnswer {
    let req: TicketSave = serde_json::from_str(json).unwrap();
    block_on(o.save(req)).unwrap()
}

pub fn mv(o: &LocalOrchestrator, json: &str) -> infantry_orchestrator_api::TicketAnswer {
    let req: TicketMove = serde_json::from_str(json).unwrap();
    block_on(o.move_lane(req)).unwrap()
}

/// The standard board the Kotlin comparison uses: every lane, a held ticket, won't fix, pins.
pub fn standard_board(f: &Fixture) {
    f.env.push_ids(&["ticket-aaaa0001", "ticket-aaaa0002", "ticket-aaaa0003", "ticket-aaaa0004", "ticket-aaaa0005", "ticket-aaaa0006"]);
    let o = &f.orch;
    save(o, r#"{"title":"First","description":"  body  ","ticketType":"bug","projectId":"p1","epicId":"e1"}"#);
    save(o, r#"{"title":"Second","weight":3,"contextLimit":200000,"effort":"HIGH","runner":"Local","provider":"claude"}"#);
    save(o, r#"{"title":"Parked","status":"backlog","useWorktree":false,"includeLinkedTasksContext":false,"includeSkillsContext":true}"#);
    save(o, r#"{"title":"Needs mcp","mcp":{"require":[{"server":"github","purpose":"open PRs"}]},"skillsMode":"replace"}"#);
    save(o, r#"{"title":"Closed","worktreeBranch":"wt/x"}"#);
    save(o, r#"{"title":"Spare","projectId":"p2"}"#);
    f.env.set_now(1_700_000_001_000);
    mv(o, r#"{"id":"ticket-aaaa0005","status":"done","wontFix":true}"#);
    mv(o, r#"{"id":"ticket-aaaa0002","position":0}"#);
    // A blocked_by link (no link frame yet): written straight to the table, then any commit settles it.
    f.board
        .db()
        .write(|c| c.execute("INSERT INTO ticket_links(ticket_id, related_id, type, created_at) VALUES ('ticket-aaaa0004','ticket-aaaa0001','blocked_by',1700000000500)", []))
        .unwrap();
    f.env.set_now(1_700_000_002_000);
    save(o, r#"{"id":"ticket-aaaa0001","title":"First, edited"}"#);
    block_on(o.delete("ticket-aaaa0006".into())).unwrap();
}
