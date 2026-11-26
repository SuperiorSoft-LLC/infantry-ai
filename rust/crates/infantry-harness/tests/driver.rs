// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! The generic driver over a scripted runner: a process-per-turn harness and a persistent one, with no real process.

use infantry_harness::runner::{
    HookEndpoint, KillScope, Pong, ProcEvent, ProcHandle, ProcId, Runner, RunnerError, RunnerFuture, ScreenSnapshot, Size,
    TailSpec,
};
use infantry_harness::*;
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::sync::mpsc::{unbounded_channel, UnboundedReceiver, UnboundedSender};

/// Each spawn plays the next script: output chunks, then (unless None) an exit code. Records specs and writes.
#[derive(Default)]
struct ScriptRunner {
    scripts: Mutex<Vec<(Vec<&'static str>, Option<i32>)>>,
    spawned: Mutex<Vec<ProcessSpec>>,
    writes: Mutex<Vec<String>>,
    killed: Mutex<Vec<String>>,
    live: Mutex<Option<UnboundedSender<ProcEvent>>>,
    rx: Mutex<Option<UnboundedReceiver<ProcEvent>>>,
}

fn unsupported<'a, T: Send + 'a>() -> RunnerFuture<'a, T> {
    Box::pin(async { Err(RunnerError::Unsupported("script".into())) })
}

impl Runner for ScriptRunner {
    fn id(&self) -> &str {
        "script"
    }
    fn ping(&self) -> RunnerFuture<'_, Pong> {
        unsupported()
    }
    fn spawn(&self, spec: ProcessSpec) -> RunnerFuture<'_, ProcHandle> {
        Box::pin(async move {
            let n = {
                let mut s = self.spawned.lock().unwrap();
                s.push(spec);
                s.len()
            };
            let (chunks, exit) = self.scripts.lock().unwrap().remove(0);
            let (tx, rx) = unbounded_channel();
            for c in chunks {
                tx.send(ProcEvent::Output(c.as_bytes().to_vec())).unwrap();
            }
            if let Some(code) = exit {
                tx.send(ProcEvent::Exited(code)).unwrap();
            }
            *self.live.lock().unwrap() = Some(tx);
            *self.rx.lock().unwrap() = Some(rx);
            Ok(ProcHandle { id: ProcId(format!("p{n}")), pid: None })
        })
    }
    fn write<'a>(&'a self, _id: &'a ProcId, data: &'a [u8]) -> RunnerFuture<'a, ()> {
        self.writes.lock().unwrap().push(String::from_utf8_lossy(data).into());
        // The persistent fake "CLI" answers every line it gets (a per-turn prompt has no newline).
        if data.ends_with(b"
") {
            if let Some(tx) = self.live.lock().unwrap().as_ref() {
                let _ = tx.send(ProcEvent::Output(b"{\"t\":\"reply\"}
{\"t\":\"done\"}
".to_vec()));
            }
        }
        Box::pin(async { Ok(()) })
    }
    fn close_input<'a>(&'a self, _id: &'a ProcId) -> RunnerFuture<'a, ()> {
        Box::pin(async { Ok(()) })
    }
    fn resize<'a>(&'a self, _id: &'a ProcId, _size: Size) -> RunnerFuture<'a, ()> {
        unsupported()
    }
    fn kill<'a>(&'a self, id: &'a ProcId, _scope: KillScope) -> RunnerFuture<'a, ()> {
        self.killed.lock().unwrap().push(id.0.clone());
        if let Some(tx) = self.live.lock().unwrap().as_ref() {
            let _ = tx.send(ProcEvent::Exited(1));
        }
        Box::pin(async { Ok(()) })
    }
    fn events<'a>(&'a self, _id: &'a ProcId) -> RunnerFuture<'a, UnboundedReceiver<ProcEvent>> {
        let rx = self.rx.lock().unwrap().take().unwrap();
        Box::pin(async move { Ok(rx) })
    }
    fn screen<'a>(&'a self, _id: &'a ProcId) -> RunnerFuture<'a, ScreenSnapshot> {
        unsupported()
    }
    fn screen_text_since<'a>(&'a self, _id: &'a ProcId, _m: u64, _x: usize) -> RunnerFuture<'a, String> {
        unsupported()
    }
    fn tail(&self, _spec: TailSpec) -> RunnerFuture<'_, UnboundedReceiver<String>> {
        unsupported()
    }
    fn hook_channel(&self) -> RunnerFuture<'_, HookEndpoint> {
        unsupported()
    }
    fn release<'a>(&'a self, _id: &'a ProcId) -> RunnerFuture<'a, ()> {
        Box::pin(async { Ok(()) })
    }
}

/// A toy JSON-lines CLI: `{"t":"session","id":..}`, `{"t":"text","v":..}`, `{"t":"done"}`, `{"t":"reply"}`.
struct ToyHarness {
    delivery: TurnDelivery,
}

/// Queues prompts as stdin lines (persistent delivery); reads the toy JSON lines.
#[derive(Default)]
struct Toy {
    queued: Vec<String>,
}

impl Translator for Toy {
    fn feed(&mut self, input: TranslatorInput) -> Vec<HarnessEvent> {
        match input {
            TranslatorInput::Stdout(l) => {
                let v: serde_json::Value = serde_json::from_str(&l).unwrap();
                match v["t"].as_str().unwrap() {
                    "session" => vec![HarnessEvent::ProviderSession(v["id"].as_str().unwrap().into())],
                    "text" => vec![HarnessEvent::Text(v["v"].as_str().unwrap().into())],
                    "reply" => vec![HarnessEvent::Text("reply".into())],
                    "done" => vec![HarnessEvent::TurnEnded { end: TurnEnd::Completed, reason: None }],
                    _ => vec![],
                }
            }
            TranslatorInput::Exited { code } => vec![HarnessEvent::Exited { code: Some(code) }],
            _ => vec![],
        }
    }
    fn send_turn(&mut self, prompt: &str) {
        self.queued.push(prompt.to_string());
    }
    fn take_outbound(&mut self) -> Vec<Outbound> {
        self.queued.drain(..).map(|p| Outbound::Stdin(format!("{p}
").into_bytes())).collect()
    }
}

impl Harness for ToyHarness {
    fn id(&self) -> &str {
        "toy"
    }
    fn capabilities(&self) -> Capabilities {
        Capabilities {
            delivery: self.delivery,
            pty_screen: false,
            file_tail: false,
            hook_channel: false,
            http: false,
            resume: true,
            usage: false,
            model_selection: false,
            approval_modes: vec![ApprovalMode::Deny],
        }
    }
    fn models(&self, _p: &dyn Probe) -> Vec<ModelInfo> {
        vec![]
    }
    fn sign_in_problem(&self, _p: &dyn Probe) -> Option<String> {
        None
    }
    fn launch(&self, req: &LaunchRequest) -> Result<LaunchPlan, LaunchError> {
        let mut args = vec!["run".to_string()];
        if let Some(r) = &req.resume {
            args.push(format!("--resume={r}"));
        }
        Ok(LaunchPlan {
            spec: ProcessSpec::new("toy", args),
            stdin: req.prompt.clone().map(String::into_bytes),
            translator: Box::new(Toy::default()),
            tail: vec![],
        })
    }
}

fn base() -> LaunchRequest {
    LaunchRequest::new(std::env::temp_dir())
}

#[tokio::test]
async fn per_turn_harness_resumes_with_the_captured_token() {
    let runner = Arc::new(ScriptRunner::default());
    runner.scripts.lock().unwrap().extend([
        // Split mid-line on purpose: the driver reassembles lines.
        (vec!["{\"t\":\"session\",\"id\":\"th-1\"}\n{\"t\":\"te", "xt\",\"v\":\"ok\"}\n{\"t\":\"done\"}\n"], Some(0)),
        (vec!["{\"t\":\"session\",\"id\":\"th-1\"}\n{\"t\":\"done\"}\n"], Some(0)),
    ]);
    let mut d = HarnessDriver::new(Arc::new(ToyHarness { delivery: TurnDelivery::SpawnPerTurn }), runner.clone(), base());
    let mut seen = Vec::new();
    let end = d.run_turn("hi", Duration::from_secs(5), Duration::from_millis(20), |e| seen.push(e.clone())).await.unwrap();
    assert_eq!(end, TurnEnd::Completed);
    assert!(seen.contains(&HarnessEvent::Text("ok".into())), "{seen:?}");
    assert_eq!(d.resume_token(), Some("th-1"));
    d.run_turn("again", Duration::from_secs(5), Duration::from_millis(20), |_| {}).await.unwrap();
    let specs = runner.spawned.lock().unwrap();
    assert_eq!(specs[0].args, vec!["run"]);
    assert_eq!(specs[1].args, vec!["run", "--resume=th-1"]);
    assert_eq!(*runner.writes.lock().unwrap(), vec!["hi".to_string(), "again".to_string()]);
}

#[tokio::test]
async fn a_saved_program_replaces_the_planned_one_and_keeps_the_arguments() {
    let runner = Arc::new(ScriptRunner::default());
    runner.scripts.lock().unwrap().extend([(vec!["{\"t\":\"done\"}
"], Some(0)), (vec!["{\"t\":\"done\"}
"], Some(0))]);
    let mut req = base();
    req.program = Some("/opt/mine/toy-cli".into());
    let mut d = HarnessDriver::new(Arc::new(ToyHarness { delivery: TurnDelivery::SpawnPerTurn }), runner.clone(), req);
    d.run_turn("hi", Duration::from_secs(5), Duration::from_millis(20), |_| {}).await.unwrap();
    let mut plain = HarnessDriver::new(Arc::new(ToyHarness { delivery: TurnDelivery::SpawnPerTurn }), runner.clone(), base());
    plain.run_turn("hi", Duration::from_secs(5), Duration::from_millis(20), |_| {}).await.unwrap();
    let specs = runner.spawned.lock().unwrap();
    assert_eq!((specs[0].program.as_str(), specs[0].args.clone()), ("/opt/mine/toy-cli", vec!["run".to_string()]));
    assert_ne!(specs[1].program, "/opt/mine/toy-cli");
}

#[tokio::test]
async fn a_process_that_dies_mid_turn_ends_the_turn_failed() {
    let runner = Arc::new(ScriptRunner::default());
    runner.scripts.lock().unwrap().push((vec!["{\"t\":\"text\",\"v\":\"partial\"}\n"], Some(3)));
    let mut d = HarnessDriver::new(Arc::new(ToyHarness { delivery: TurnDelivery::SpawnPerTurn }), runner, base());
    let mut seen = Vec::new();
    let end = d.run_turn("hi", Duration::from_secs(5), Duration::from_millis(20), |e| seen.push(e.clone())).await.unwrap();
    assert_eq!(end, TurnEnd::Failed);
    assert!(seen.contains(&HarnessEvent::Exited { code: Some(3) }));
    assert!(!d.busy());
}

#[tokio::test]
async fn interrupt_kills_a_per_turn_process_and_reports_interrupted() {
    let runner = Arc::new(ScriptRunner::default());
    runner.scripts.lock().unwrap().push((vec![], None)); // never ends by itself
    let mut d = HarnessDriver::new(Arc::new(ToyHarness { delivery: TurnDelivery::SpawnPerTurn }), runner.clone(), base());
    d.start_turn("hi").await.unwrap();
    assert!(d.poll(Duration::from_millis(20)).await.is_empty());
    assert!(d.interrupt().await);
    let evs = d.poll(Duration::from_millis(200)).await;
    assert!(evs.contains(&HarnessEvent::TurnEnded { end: TurnEnd::Interrupted, reason: Some("process exited before the turn ended".into()) }), "{evs:?}");
    assert_eq!(runner.killed.lock().unwrap().len(), 1);
}

#[tokio::test]
async fn persistent_harness_spawns_once_and_sends_turns_as_outbound() {
    let runner = Arc::new(ScriptRunner::default());
    runner.scripts.lock().unwrap().push((vec![], None));
    let mut d = HarnessDriver::new(Arc::new(ToyHarness { delivery: TurnDelivery::Persistent }), runner.clone(), base());
    for p in ["one", "two"] {
        let mut seen = Vec::new();
        let end = d.run_turn(p, Duration::from_secs(5), Duration::from_millis(20), |e| seen.push(e.clone())).await.unwrap();
        assert_eq!(end, TurnEnd::Completed);
        assert!(seen.contains(&HarnessEvent::Text("reply".into())));
    }
    assert_eq!(runner.spawned.lock().unwrap().len(), 1);
    assert_eq!(*runner.writes.lock().unwrap(), vec!["one\n".to_string(), "two\n".to_string()]);
    assert!(runner.spawned.lock().unwrap()[0].args == vec!["run"]);
    assert_eq!(d.start_turn("x").await, Ok(()));
    assert_eq!(d.start_turn("y").await, Err(DriverError::Busy));
}
