// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! A BUSY turn silent past the limit is flagged stalled on the card; its child is hung, so the next turn kills that child
//! by pid and starts a new one (a new pid), never attaching to the old process.
use infantry_daemon::harness::{Capabilities, EventSink, Harness, HarnessEvent, HarnessSession, OpenRequest, RunStart, Turn, TurnEnd};
use infantry_daemon::sessions::{Config, Daemon, NewOpts, WatchConfig, WatchPorts};
use infantry_protocol::session::SessionRecord;
use std::process::{Child, Command, Stdio};
use std::sync::{Arc, Mutex};
use std::time::Duration;

#[derive(Default)]
struct Shared {
    children: Mutex<Vec<Child>>,
    sink: Mutex<Option<EventSink>>,
}

struct Hung(Arc<Shared>);
struct HungSession(Arc<Shared>);

fn sleeper() -> Child {
    #[cfg(windows)]
    let mut c = Command::new("ping");
    #[cfg(windows)]
    c.args(["-n", "120", "127.0.0.1"]);
    #[cfg(not(windows))]
    let mut c = Command::new("sleep");
    #[cfg(not(windows))]
    c.arg("120");
    c.stdout(Stdio::null()).stderr(Stdio::null()).spawn().unwrap()
}

impl Harness for Hung {
    fn name(&self) -> &str {
        "hung"
    }
    fn capabilities(&self) -> Capabilities {
        Capabilities { pty_screen: false, file_tail: false, hook_channel: false, persistent_process: true }
    }
    fn open(&self, _req: OpenRequest, sink: EventSink) -> Result<Box<dyn HarnessSession>, String> {
        *self.0.sink.lock().unwrap() = Some(sink);
        Ok(Box::new(HungSession(self.0.clone())))
    }
}

impl HarnessSession for HungSession {
    fn send_turn(&mut self, _t: Turn) -> Result<RunStart, String> {
        let mut kids = self.0.children.lock().unwrap();
        // The process of the first turn stays (a persistent CLI); a replaced session starts a fresh one.
        let c = sleeper();
        let pid = c.id();
        kids.push(c);
        Ok(RunStart { pid: Some(pid), provider_session_id: None })
    }
    fn interrupt(&mut self) -> bool {
        false
    }
    fn close(&mut self, _force: bool) {}
    fn process_alive(&self) -> bool {
        true
    }
}

struct Ports;
impl WatchPorts for Ports {
    fn raise(&self, _r: &SessionRecord, _o: &str, _d: &str) {}
}

#[tokio::test(flavor = "multi_thread")]
async fn a_hung_child_is_flagged_then_killed_and_replaced_by_the_next_turn() {
    let shared = Arc::new(Shared::default());
    let dir = std::env::temp_dir().join(format!("infantry-hung-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let daemon = Daemon::new(Arc::new(Hung(shared.clone())), Config::new(dir.clone(), dir)).unwrap();
    let sid = daemon.launch(NewOpts { prompt: Some("one".into()), ..Default::default() }).unwrap();
    let first = daemon.record(&sid).unwrap().child_pid.expect("a pid");

    // An hour of silence: flagged on the card.
    let now = infantry_daemon::sessions::now_ms() + 3_600_000;
    let d = daemon.clone();
    tokio::task::spawn_blocking(move || d.watch_tick(&Ports, &WatchConfig::default(), now)).await.unwrap();
    let rec = daemon.record(&sid).unwrap();
    assert!(rec.watch_label.as_deref().is_some_and(|l| l.starts_with("stalled")), "{:?}", rec.watch_label);

    // The turn ends (a stop), the process stays; the owner prompts again.
    let sink = shared.sink.lock().unwrap().clone().unwrap();
    sink.emit(HarnessEvent::TurnEnded { end: TurnEnd::Failed, exit_code: None, reason: None, process_alive: true });
    tokio::time::sleep(Duration::from_millis(300)).await;
    let d = daemon.clone();
    let s = sid.clone();
    tokio::task::spawn_blocking(move || d.deliver(&s, "two").unwrap()).await.unwrap();
    tokio::time::sleep(Duration::from_millis(500)).await;

    let second = daemon.record(&sid).unwrap().child_pid.expect("a pid");
    assert_ne!(first, second, "the new turn must not report the hung pid as a new process");
    let mut kids = shared.children.lock().unwrap();
    assert!(kids[0].try_wait().unwrap().is_some(), "the hung child was killed by pid");
    assert!(kids[1].try_wait().unwrap().is_none(), "the replacement runs");
    for k in kids.iter_mut() {
        let _ = k.kill();
    }
}
