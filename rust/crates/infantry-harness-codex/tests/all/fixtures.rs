// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! Offline: the translator over recorded `codex exec --json` output, the launch arguments, models and sign-in checks.
//! `turn1_new.jsonl` / `turn2_resume.jsonl` are REAL output of codex-cli 0.162.1 (the second resumes the first's thread;
//! the home path is scrubbed); `handwritten_*.jsonl` follow the documented event format for cases a tiny turn does not hit.

use infantry_harness::{
    ApprovalMode, Harness, HarnessEvent, LaunchRequest, ModelInfo, Probe, Translator, TranslatorInput, TurnEnd, Usage,
};
use infantry_harness_codex::{CodexHarness, CodexTranslator};
use std::collections::HashMap;
use std::path::{Path, PathBuf};

fn fixture(name: &str) -> String {
    std::fs::read_to_string(Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures").join(name)).unwrap()
}

/// Feeds a fixture as a process would: stdout lines, then exit 0. Drops the raw `Stdout` echoes.
fn translate(name: &str) -> Vec<HarnessEvent> {
    let mut t = CodexTranslator::default();
    let mut out = Vec::new();
    for line in fixture(name).lines() {
        out.extend(t.feed(TranslatorInput::Stdout(line.to_string())));
    }
    out.extend(t.feed(TranslatorInput::Exited { code: 0 }));
    out.into_iter().filter(|e| !matches!(e, HarnessEvent::Stdout(_))).collect()
}

const THREAD: &str = "01a1eebb-fd93-7db3-9bf3-0f2223341fa7";

#[test]
fn real_first_turn_streams_text_thread_id_and_usage() {
    let ev = translate("turn1_new.jsonl");
    assert_eq!(ev[0], HarnessEvent::ProviderSession(THREAD.into()));
    assert!(matches!(&ev[1], HarnessEvent::Warning(w) if w.contains("unrecognized configuration")), "{:?}", ev[1]);
    assert!(ev.contains(&HarnessEvent::TurnStarted));
    assert!(ev.contains(&HarnessEvent::Text("ok".into())));
    assert!(ev.contains(&HarnessEvent::Usage(Usage { input_tokens: 15671, cached_input_tokens: 0, output_tokens: 5, reasoning_tokens: 0 })));
    let ends: Vec<_> = ev.iter().filter(|e| matches!(e, HarnessEvent::TurnEnded { .. })).collect();
    assert_eq!(ends, vec![&HarnessEvent::TurnEnded { end: TurnEnd::Completed, reason: None }]);
    assert_eq!(ev.last(), Some(&HarnessEvent::Exited { code: Some(0) }));
}

#[test]
fn real_resumed_turn_keeps_the_thread_and_reports_the_failed_command() {
    let ev = translate("turn2_resume.jsonl");
    assert_eq!(ev[0], HarnessEvent::ProviderSession(THREAD.into()));
    let started = ev.iter().find_map(|e| match e {
        HarnessEvent::ToolStarted { id, name, input } => Some((id.clone(), name.clone(), input.clone())),
        _ => None,
    });
    let (id, name, input) = started.expect("a command started");
    assert_eq!((id.as_str(), name.as_str()), ("item_3", "command"));
    assert!(input.contains("echo hi"));
    assert!(ev.iter().any(|e| matches!(e, HarnessEvent::ToolFinished { id, exit_code: Some(-1), ok: false, .. } if id == "item_3")));
    assert_eq!(ev.iter().filter(|e| matches!(e, HarnessEvent::Text(_))).count(), 2);
    assert!(ev.contains(&HarnessEvent::TurnEnded { end: TurnEnd::Completed, reason: None }));
}

#[test]
fn every_documented_item_kind_maps() {
    let ev = translate("handwritten_items.jsonl");
    assert!(ev.contains(&HarnessEvent::Reasoning("**Planning the edit**".into())));
    assert!(ev.contains(&HarnessEvent::ToolFinished { id: "item_1".into(), output: "README.md\n".into(), exit_code: Some(0), ok: true }));
    assert!(ev.contains(&HarnessEvent::ToolStarted { id: "item_2".into(), name: "file_change".into(), input: "update README.md".into() }));
    assert!(ev.contains(&HarnessEvent::ToolStarted { id: "item_3".into(), name: "docs/search".into(), input: "{\"q\":\"x\"}".into() }));
    assert!(ev.iter().any(|e| matches!(e, HarnessEvent::ToolFinished { id, ok: true, .. } if id == "item_3")));
    assert!(ev.contains(&HarnessEvent::ToolStarted { id: "item_4".into(), name: "web_search".into(), input: "rust tokio".into() }));
    assert!(ev.contains(&HarnessEvent::Text("Done.".into())));
    assert!(ev.contains(&HarnessEvent::Usage(Usage { input_tokens: 100, cached_input_tokens: 40, output_tokens: 7, reasoning_tokens: 3 })));
}

#[test]
fn turn_failed_is_an_error_and_a_failed_end() {
    let ev = translate("handwritten_failed.jsonl");
    assert!(ev.contains(&HarnessEvent::Warning("Reconnecting... 1/5".into())));
    assert!(ev.contains(&HarnessEvent::Error("unexpected status 401 Unauthorized".into())));
    let ends: Vec<_> = ev.iter().filter(|e| matches!(e, HarnessEvent::TurnEnded { .. })).collect();
    assert_eq!(ends, vec![&HarnessEvent::TurnEnded { end: TurnEnd::Failed, reason: Some("unexpected status 401 Unauthorized".into()) }]);
}

#[test]
fn an_exit_without_turn_completed_fails_with_the_last_stderr_line() {
    let mut t = CodexTranslator::default();
    t.feed(TranslatorInput::Stdout(format!("{{\"type\":\"thread.started\",\"thread_id\":\"{THREAD}\"}}")));
    t.feed(TranslatorInput::Stderr("Error: not logged in".into()));
    let ev = t.feed(TranslatorInput::Exited { code: 1 });
    assert_eq!(
        ev,
        vec![
            HarnessEvent::TurnEnded { end: TurnEnd::Failed, reason: Some("Error: not logged in".into()) },
            HarnessEvent::Exited { code: Some(1) }
        ]
    );
}

#[test]
fn launch_args_new_resume_model_effort_and_approval() {
    let h = CodexHarness::default();
    let mut req = LaunchRequest::new(PathBuf::from("C:/w"));
    req.prompt = Some("reply with the word ok".into());
    let plan = h.launch(&req).unwrap();
    assert_eq!(plan.spec.program, "codex");
    assert_eq!(plan.spec.args, ["exec", "--json", "--skip-git-repo-check", "-c", "sandbox_mode=\"read-only\"", "-"]);
    assert_eq!(plan.spec.cwd.as_deref(), Some("C:/w"));
    assert_eq!(plan.stdin.as_deref(), Some(&b"reply with the word ok"[..]));

    req.resume = Some(THREAD.into());
    req.model = Some("gpt-6-luna".into());
    req.effort = Some("low".into());
    req.approval = ApprovalMode::AllowEdits;
    assert_eq!(
        h.launch(&req).unwrap().spec.args,
        [
            "exec", "resume", THREAD, "--json", "--skip-git-repo-check", "-c", "sandbox_mode=\"workspace-write\"", "-m",
            "gpt-6-luna", "-c", "model_reasoning_effort=\"low\"", "-"
        ]
    );
    req.approval = ApprovalMode::AllowAll;
    assert!(h.launch(&req).unwrap().spec.args.contains(&"--dangerously-bypass-approvals-and-sandbox".to_string()));
    req.approval = ApprovalMode::Ask; // exec cannot ask: read-only
    assert!(h.launch(&req).unwrap().spec.args.contains(&"sandbox_mode=\"read-only\"".to_string()));

    req.prompt = None;
    assert!(h.launch(&req).is_err(), "a per-turn harness needs the prompt");
}

/// A machine described by a map of env vars and files.
struct FakeProbe {
    env: HashMap<&'static str, String>,
    files: HashMap<PathBuf, String>,
}

impl Probe for FakeProbe {
    fn env(&self, name: &str) -> Option<String> {
        self.env.get(name).cloned()
    }
    fn home(&self) -> Option<PathBuf> {
        Some(PathBuf::from("/home/u"))
    }
    fn read_to_string(&self, path: &Path) -> Option<String> {
        self.files.get(path).cloned()
    }
    fn exists(&self, path: &Path) -> bool {
        self.files.contains_key(path)
    }
}

#[test]
fn models_come_from_the_cli_cache_and_sign_in_from_auth_or_key() {
    let h = CodexHarness::default();
    let bin = PathBuf::from("/bin");
    let codex = bin.join(if cfg!(windows) { "codex.cmd" } else { "codex" });
    let mut p = FakeProbe {
        env: HashMap::from([("PATH", bin.to_string_lossy().to_string())]),
        files: HashMap::from([(codex, String::new())]),
    };
    assert_eq!(h.sign_in_problem(&p).as_deref(), Some("codex is not signed in: run `codex login`"));
    assert!(h.models(&p).is_empty());
    p.files.insert(PathBuf::from("/home/u/.codex/auth.json"), "{}".into());
    p.files.insert(PathBuf::from("/home/u/.codex/models_cache.json"), fixture("models_cache.json"));
    assert_eq!(h.sign_in_problem(&p), None);
    assert_eq!(
        h.models(&p),
        vec![
            ModelInfo { id: "gpt-6-luna".into(), label: "GPT-6-Luna".into() },
            ModelInfo { id: "gpt-5.6-terra".into(), label: "GPT-5.6-Terra".into() }
        ]
    );
    p.files.clear();
    p.env.insert("OPENAI_API_KEY", "x".into());
    assert!(h.sign_in_problem(&p).unwrap().contains("not installed"));
}
