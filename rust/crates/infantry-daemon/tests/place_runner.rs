// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! RR-4 end to end, two daemons in one process: a runner (RR-2's link over a `ProcessBackend` on its own `LocalRunner`,
//! on port 0) and an orchestrator `Daemon` whose registry gets the paired runner from a `RunnerPool`. A session with
//! `runner=<name>` runs its (fake) harness process ON the runner, in the runner's folder, and its output arrives as the
//! session's frames; an offline runner is refused and nothing starts locally; a stop reaches the runner.
use infantry_daemon::harness::{driven::Driven, HarnessRegistry};
use infantry_daemon::sessions::{Config, Daemon, NewOpts};
use infantry_harness::runner::{ProcEvent, ProcId, Runner};
use infantry_harness::*;
use infantry_runner_link::pairing::{Bearer, BearerToken};
use infantry_runner_link::process::ProcessBackend;
use infantry_runner_link::{serve, LinkServer, LinkServing, LinkStore};
use infantry_runner_local::{LocalConfig, LocalRunner};
use infantry_runner_pool::RunnerPool;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::{Duration, Instant};

fn temp(tag: &str) -> PathBuf {
    let d = std::env::temp_dir().join(format!("rr4-{tag}-{}-{}", std::process::id(), std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos()));
    std::fs::create_dir_all(&d).unwrap();
    d
}

/// A fake agent CLI: prints its working directory, then stays alive (so a stop has something to kill).
struct FakeCli;

struct Lines;
impl Translator for Lines {
    fn feed(&mut self, input: TranslatorInput) -> Vec<infantry_harness::HarnessEvent> {
        match input {
            TranslatorInput::Stdout(l) => vec![infantry_harness::HarnessEvent::Stdout(l)],
            TranslatorInput::Exited { code } => vec![
                infantry_harness::HarnessEvent::TurnEnded { end: infantry_harness::TurnEnd::Completed, reason: None },
                infantry_harness::HarnessEvent::Exited { code: Some(code) },
            ],
            _ => Vec::new(),
        }
    }
}

impl infantry_harness::Harness for FakeCli {
    fn id(&self) -> &str {
        "fakecli"
    }
    fn capabilities(&self) -> Capabilities {
        Capabilities {
            delivery: TurnDelivery::SpawnPerTurn,
            pty_screen: false,
            file_tail: false,
            hook_channel: false,
            http: false,
            resume: false,
            usage: false,
            model_selection: false,
            approval_modes: vec![ApprovalMode::AllowAll],
        }
    }
    fn models(&self, _p: &dyn Probe) -> Vec<ModelInfo> {
        Vec::new()
    }
    fn sign_in_problem(&self, _p: &dyn Probe) -> Option<String> {
        None
    }
    fn launch(&self, req: &LaunchRequest) -> Result<LaunchPlan, LaunchError> {
        #[cfg(windows)]
        let spec = ProcessSpec::new("cmd", ["/C", "cd && ping -n 60 127.0.0.1 > nul"]);
        #[cfg(not(windows))]
        let spec = ProcessSpec::new("sh", ["-c", "pwd; sleep 60"]);
        Ok(LaunchPlan { spec: spec.cwd(req.cwd.to_string_lossy()), stdin: None, translator: Box::new(Lines), tail: Vec::new() })
    }
}

struct Rig {
    daemon: Arc<Daemon>,
    runner: Arc<LocalRunner>,
    serving: Option<LinkServing>,
    rdir: PathBuf,
    frames: Arc<std::sync::Mutex<String>>,
}

fn rig() -> Rig {
    let (rdir, odir) = (temp("runner"), temp("orch"));
    // The runner daemon: its own LocalRunner (scratch in its own state dir) behind the link, on port 0.
    let runner = Arc::new(LocalRunner::new(LocalConfig { scratch_dir: Some(rdir.join("runner")), ..Default::default() }));
    let bearer = Bearer::of(&rdir);
    let v = bearer.ensure(Some("box"), false).unwrap();
    let token = BearerToken { name: v.name, fingerprint: None, secret: v.secret };
    let server = LinkServer::new(LinkStore::in_memory(), None, Some(bearer), Box::new(ProcessBackend::new(runner.clone())), None).with_log(|_| {});
    let serving = serve(Arc::new(server), "127.0.0.1", 0, None).unwrap();
    // The orchestrator: a daemon with the fake harness, and the pool paired to the runner.
    let mut cfg = Config::new(odir.clone(), odir.clone());
    cfg.registry = HarnessRegistry::new(vec![Arc::new(FakeCli)]);
    let daemon = Daemon::new(Arc::new(Driven::new(Arc::new(FakeCli))), cfg).unwrap();
    let pool = RunnerPool::open(&odir);
    pool.set_allow_plain(Arc::new(|| true));
    pool.add(&format!("ws://127.0.0.1:{}", serving.port), &token.secret, None, Some("box"), "orch").unwrap();
    pool.check("box");
    daemon.runner_registry().add_source(pool);
    // Collect every frame the daemon broadcasts.
    let frames = Arc::new(std::sync::Mutex::new(String::new()));
    let (mut rx, f) = (daemon.subscribe(), frames.clone());
    std::thread::spawn(move || {
        let rt = tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap();
        rt.block_on(async move {
            while let Ok(m) = rx.recv().await {
                f.lock().unwrap().push_str(&m);
                f.lock().unwrap().push('\n');
            }
        });
    });
    Rig { daemon, runner, serving: Some(serving), rdir, frames }
}

fn wait<T>(what: &str, mut f: impl FnMut() -> Option<T>) -> T {
    let end = Instant::now() + Duration::from_secs(30);
    loop {
        if let Some(v) = f() {
            return v;
        }
        assert!(Instant::now() < end, "timed out waiting for {what}");
        std::thread::sleep(Duration::from_millis(100));
    }
}

fn opts(runner: &str) -> NewOpts {
    NewOpts { provider: Some("fakecli".into()), runner: Some(runner.into()), prompt: Some("go".into()), ..Default::default() }
}

fn block<T>(f: impl std::future::Future<Output = T>) -> T {
    tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap().block_on(f)
}

#[test]
fn a_session_pinned_to_a_runner_runs_there_and_a_stop_reaches_it() {
    let r = rig();
    let sid = r.daemon.launch(opts("box")).expect("the start");
    // The harness ran on the runner: the cwd it printed is in the RUNNER's state dir, and it arrived as the session's frames.
    let marker = r.rdir.file_name().unwrap().to_string_lossy().into_owned();
    wait("the fake CLI's output as frames", || r.frames.lock().unwrap().contains(&marker).then_some(()));
    let rec = wait("a placement", || r.daemon.record(&sid).filter(|x| x.runner_proc.is_some()));
    assert_eq!(rec.runner, "box", "the session list shows the runner");
    assert!(rec.working_dir.contains(&marker), "the working dir is a folder on the runner, not the orchestrator's: {}", rec.working_dir);
    let id = ProcId(rec.runner_proc.clone().unwrap());
    // The orchestrator's own (local) runner never saw that process.
    let local = r.daemon.runner_registry().local().runner;
    assert!(block(local.events(&id)).is_err(), "nothing ran locally");
    // The runner knows the process while the session lives.
    assert!(block(r.runner.events(&id)).is_ok(), "the process runs on the runner");
    // Stop: the process ends ON the runner (killed, then released: the runner no longer knows it).
    r.daemon.stop_session(&sid, "test").unwrap();
    wait("the runner to drop the process", || {
        let Ok(mut rx) = block(r.runner.events(&id)) else { return Some(()) };
        loop {
            match rx.try_recv() {
                Ok(ProcEvent::Exited(_)) => return Some(()),
                Ok(_) => continue,
                Err(_) => return None,
            }
        }
    });
}

#[test]
fn an_offline_or_unknown_runner_is_refused_and_nothing_starts_locally() {
    let mut r = rig();
    // Unknown.
    let e = r.daemon.launch(opts("nope")).unwrap_err();
    assert!(e.contains("'nope'") && e.contains("not paired") && e.contains("nothing ran locally"), "{e}");
    // Offline: the runner's server is closed.
    let s = r.serving.take().unwrap();
    s.close();
    drop(s);
    let e = r.daemon.launch(opts("box")).unwrap_err();
    assert!(e.contains("'box'") && e.contains("offline") && e.contains("nothing ran locally"), "{e}");
    assert_eq!(r.daemon.session_count(), 0, "no session was listed");
}
