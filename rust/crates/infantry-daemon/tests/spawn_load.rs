// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! RUST-BUG 2617b342: the spawn path under load. Many sessions are launched, prompted and removed at once from several
//! threads while the watch tick runs; the daemon must not panic anywhere (a panic hook counts every one, and the crash
//! files it writes are checked), and no session may be lost.
use infantry_daemon::harness::{Capabilities, EventSink, Harness, HarnessSession, OpenRequest, RunStart, Turn};
use infantry_daemon::sessions::{Config, Daemon, NewOpts, WatchConfig, WatchPorts};
use infantry_protocol::session::SessionRecord;
use std::process::{Child, Command, Stdio};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};

static PANICS: AtomicUsize = AtomicUsize::new(0);

#[derive(Default)]
struct Kids(Mutex<Vec<Child>>);
struct Spawner(Arc<Kids>);
struct SpawnerSession(Arc<Kids>);

fn sleeper() -> Result<Child, String> {
    #[cfg(windows)]
    let mut c = Command::new("ping");
    #[cfg(windows)]
    c.args(["-n", "30", "127.0.0.1"]);
    #[cfg(not(windows))]
    let mut c = Command::new("sleep");
    #[cfg(not(windows))]
    c.arg("30");
    c.stdout(Stdio::null()).stderr(Stdio::null()).spawn().map_err(|e| e.to_string())
}

impl Harness for Spawner {
    fn name(&self) -> &str {
        "spawner"
    }
    fn capabilities(&self) -> Capabilities {
        Capabilities { pty_screen: false, file_tail: false, hook_channel: false, persistent_process: true }
    }
    fn open(&self, _req: OpenRequest, _sink: EventSink) -> Result<Box<dyn HarnessSession>, String> {
        Ok(Box::new(SpawnerSession(self.0.clone())))
    }
}

impl HarnessSession for SpawnerSession {
    fn send_turn(&mut self, _t: Turn) -> Result<RunStart, String> {
        let c = sleeper()?;
        let pid = c.id();
        self.0 .0.lock().unwrap().push(c);
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

#[test]
fn a_burst_of_spawns_prompts_and_ticks_never_panics() {
    let dir = std::env::temp_dir().join(format!("infantry-spawnload-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    // The production hook (message + backtrace to health/crashes/), then a counter on top of it.
    infantry_daemon::health::install_panic_log(&dir);
    let previous = std::panic::take_hook();
    std::panic::set_hook(Box::new(move |i| {
        PANICS.fetch_add(1, Ordering::SeqCst);
        previous(i);
    }));
    let kids = Arc::new(Kids::default());
    let daemon = Daemon::new(Arc::new(Spawner(kids.clone())), Config::new(dir.clone(), dir.clone())).unwrap();
    let (threads, per_thread) = (8, 6);
    let ids = Arc::new(Mutex::new(Vec::new()));
    let mut hs = Vec::new();
    for t in 0..threads {
        let (d, ids) = (daemon.clone(), ids.clone());
        hs.push(std::thread::spawn(move || {
            for i in 0..per_thread {
                let sid = d.launch(NewOpts { prompt: Some(format!("work {t}-{i}")), ..Default::default() }).expect("launch");
                ids.lock().unwrap().push(sid.clone());
                let _ = d.deliver(&sid, "again");
                if i % 3 == 0 {
                    let _ = d.watch_tick(&Ports, &WatchConfig::default(), infantry_daemon::sessions::now_ms() + 3_600_000);
                }
            }
        }));
    }
    for h in hs {
        h.join().expect("a spawning thread panicked");
    }
    let ids = ids.lock().unwrap().clone();
    assert_eq!(ids.len(), threads * per_thread);
    for id in &ids {
        assert!(daemon.record(id).is_some(), "session {id} was lost");
    }
    for k in kids.0.lock().unwrap().iter_mut() {
        let _ = k.kill();
    }
    let crashes = infantry_daemon::health::crash_files(&dir.join("health"));
    assert_eq!(PANICS.load(Ordering::SeqCst), 0, "panics under load; crash files: {crashes:?}");
    let _ = std::fs::remove_dir_all(&dir);
}
