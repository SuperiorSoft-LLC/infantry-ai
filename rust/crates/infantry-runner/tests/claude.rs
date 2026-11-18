// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The Claude harness over the Kotlin golden inputs: the stream fixtures in `superiorSoftLibrary/infantry/src/test/resources`
//! and the transcript records of `ClaudeTtySessionTest.kt`. The driver is stepped by hand on a fake clock (no process).

use infantry_runner::claude::driver::{self, trust_menu, TrustMenu};
use infantry_runner::claude::hooks as ch;
use infantry_runner::claude::transcript::{self, Signal, TranscriptMapper};
use infantry_runner::claude::{shim_target, Claude};
use infantry_runner::harness::*;
use infantry_runner::hooks::HookServer;
use infantry_runner::screen::Screen;
use infantry_runner::stream::{parse_line, StreamEvent};
use serde_json::Value;
use std::path::PathBuf;
use std::sync::mpsc::{channel, Receiver, Sender};
use std::sync::{Arc, Mutex};

const CLAUDE_RUN: &str = include_str!("../../../../superiorSoftLibrary/infantry/src/test/resources/retrieval/claude-run.jsonl");
const CLAUDE_TASKS: &str = include_str!("../../../../superiorSoftLibrary/infantry/src/test/resources/retrieval/claude-task-tools.jsonl");

const ID: &str = "11111111-2222-3333-4444-555555555555";

fn assistant(id: &str, text: &str, output: u64) -> String {
    format!(
        r#"{{"type":"assistant","uuid":"u-{id}-{output}","sessionId":"x","entrypoint":"cli","message":{{"id":"{id}","role":"assistant","model":"claude-haiku-5-5","content":[{{"type":"text","text":"{text}"}}],"usage":{{"input_tokens":3,"output_tokens":{output},"cache_read_input_tokens":100,"cache_creation_input_tokens":50}}}}}}"#
    )
}
const TOOL_USE: &str = r#"{"type":"assistant","uuid":"u-t","message":{"id":"m0","role":"assistant","model":"claude-haiku-5-5","content":[{"type":"tool_use","id":"toolu_1","name":"Read","input":{"file_path":"a.txt"}}],"usage":{"input_tokens":2,"output_tokens":7,"cache_read_input_tokens":10,"cache_creation_input_tokens":0}}}"#;
const TOOL_RESULT: &str = r#"{"type":"user","uuid":"u-r","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_1","content":"hello"}]}}"#;
const PROMPT: &str = r#"{"type":"user","uuid":"u-p","entrypoint":"cli","message":{"role":"user","content":"read a.txt"}}"#;
const INTERRUPTED: &str = r#"{"type":"user","message":{"role":"user","content":[{"type":"text","text":"[Request interrupted by user]"}]}}"#;

// ---- stream fixtures ---------------------------------------------------------------------------------------------------

#[test]
fn the_claude_stream_fixture_reads_as_tool_calls_results_and_text() {
    let events: Vec<StreamEvent> = CLAUDE_RUN.lines().flat_map(parse_line).collect();
    let calls: Vec<&str> = events.iter().filter_map(|e| if let StreamEvent::ToolCall { name, .. } = e { Some(name.as_str()) } else { None }).collect();
    assert_eq!(calls, vec!["TodoWrite", "Bash", "Bash", "Grep", "Bash", "Bash"]);
    let results: Vec<(&str, bool)> = events
        .iter()
        .filter_map(|e| if let StreamEvent::ToolResult { tool_use_id, is_error, .. } = e { Some((tool_use_id.as_str(), *is_error)) } else { None })
        .collect();
    assert_eq!(results.len(), 6);
    assert!(results.iter().any(|(id, err)| *id == "toolu_01HHWAKbZhDFRec3JuQoa8W2" && !err), "the Grep result is not an error");
    assert_eq!(results.iter().filter(|(_, e)| *e).count(), 5, "{results:?}");
    let ids: Vec<&str> = events.iter().filter_map(|e| if let StreamEvent::ToolCall { id, .. } = e { Some(id.as_str()) } else { None }).collect();
    for (id, _) in &results {
        assert!(ids.contains(id), "every result answers a call: {id}");
    }
    let StreamEvent::AssistantText { text, model, .. } = events.last().unwrap() else { panic!("ends with text: {:?}", events.last()) };
    assert!(text.starts_with("DONE"));
    assert_eq!(model.as_deref(), Some("claude-haiku-5-5"));
}

#[test]
fn the_task_tools_fixture_reads_its_three_calls() {
    let events: Vec<StreamEvent> = CLAUDE_TASKS.lines().flat_map(parse_line).collect();
    let calls: Vec<(String, Value)> =
        events.iter().filter_map(|e| if let StreamEvent::ToolCall { name, input, .. } = e { Some((name.clone(), input.clone())) } else { None }).collect();
    assert_eq!(calls.iter().map(|c| c.0.as_str()).collect::<Vec<_>>(), vec!["TaskCreate", "TaskUpdate", "TaskUpdate"]);
    assert_eq!(calls[0].1["subject"], "Read README");
    assert_eq!(calls[2].1["status"], "deleted");
    assert!(events.contains(&StreamEvent::ToolResult { tool_use_id: "toolu_02".into(), content: "Updated task #1 status".into(), is_error: false }));
}

// ---- transcript mapper (ClaudeTtySessionTest) --------------------------------------------------------------------------

fn line(s: &Signal) -> &str {
    match s {
        Signal::Line(l) => l,
        other => panic!("not a line: {other:?}"),
    }
}

#[test]
fn transcript_records_become_stream_json_records() {
    let mut m = TranscriptMapper::new("sid");
    assert_eq!(m.map(PROMPT), vec![Signal::PromptRecorded]);
    let a = m.map(TOOL_USE);
    let r = m.map(TOOL_RESULT);
    assert!(m.map(r#"{"type":"file-history-snapshot"}"#).is_empty());
    assert!(m.map(r#"{"type":"assistant","isSidechain":true,"message":{"id":"z","content":[]}}"#).is_empty(), "a subagent's record is not the session's");
    assert!(m.map("not json").is_empty());
    let mut events = parse_line(&m.init_line());
    events.extend(parse_line(line(&a[0])));
    events.extend(parse_line(line(&r[0])));
    assert!(events.contains(&StreamEvent::Init { session_id: Some("sid".into()) }));
    assert!(events.iter().any(|e| matches!(e, StreamEvent::ToolCall { id, name, .. } if id == "toolu_1" && name == "Read")));
    assert!(events.contains(&StreamEvent::ToolResult { tool_use_id: "toolu_1".into(), content: "hello".into(), is_error: false }));
    let v: Value = serde_json::from_str(line(&a[0])).unwrap();
    assert_eq!(v["session_id"], "sid");
    assert_eq!(v["uuid"], "u-t");
    assert!(v["parent_tool_use_id"].is_null());
}

#[test]
fn the_result_sums_the_last_usage_of_each_message_and_resets_the_turn() {
    let mut m = TranscriptMapper::new("sid");
    m.map(TOOL_USE);
    m.map(&assistant("m1", "partial", 5));
    m.map(&assistant("m1", "final answer", 9));
    let r: Value = serde_json::from_str(&m.result_line(None)).unwrap();
    assert_eq!(r["subtype"], "success");
    assert_eq!(r["result"], "final answer");
    assert_eq!(r["usage"]["output_tokens"], 16);
    assert_eq!(r["usage"]["cache_read_input_tokens"], 110);
    assert_eq!(r["usage"]["input_tokens"], 5);
    assert_eq!(r["cost_estimated"], true);
    let next: Value = serde_json::from_str(&m.result_line(None)).unwrap();
    assert!(next.get("usage").is_none(), "the next turn starts from zero");
    let StreamEvent::Result { usage: Some(u), cost_usd: Some(_), .. } = &parse_line(&TranscriptMapper::new("s").tap(|m| m.map(TOOL_USE)).result_line(None))[0]
    else {
        panic!()
    };
    assert_eq!(u["output_tokens"], 7);
}

trait Tap: Sized {
    fn tap(mut self, f: impl FnOnce(&mut Self) -> Vec<Signal>) -> Self {
        f(&mut self);
        self
    }
}
impl Tap for TranscriptMapper {}

#[test]
fn interrupts_synthetic_messages_api_errors_and_turn_ends_are_signals() {
    let mut m = TranscriptMapper::new("sid");
    assert_eq!(m.map(INTERRUPTED), vec![Signal::Interrupted]);
    assert!(m.map(r#"{"type":"assistant","message":{"id":"s","model":"<synthetic>","content":[{"type":"text","text":"No response requested."}]}}"#).is_empty());
    assert_eq!(
        m.map(r#"{"type":"assistant","isApiErrorMessage":true,"message":{"id":"e","model":"<synthetic>","content":[{"type":"text","text":"API Error: 529 overloaded"}]}}"#),
        vec![Signal::ApiError("API Error: 529 overloaded".into())]
    );
    assert!(m.map(r#"{"type":"user","isMeta":true,"message":{"role":"user","content":"<command-name>/model</command-name>"}}"#).is_empty());
    assert_eq!(m.map(r#"{"type":"system","subtype":"turn_duration","durationMs":5}"#), vec![Signal::TurnEnded]);
    let notice = r#"{"type":"user","origin":{"kind":"task-notification"},"message":{"role":"user","content":"<task-notification>done</task-notification>"}}"#;
    assert_eq!(m.map(notice), vec![Signal::OwnPrompt("task-notification".into())]);
    let pasted = r#"{"type":"user","origin":{"kind":"human"},"message":{"role":"user","content":"NEW MAIL (1)"}}"#;
    assert_eq!(m.map(pasted), vec![Signal::PromptRecorded]);
    let compact = r#"{"type":"system","subtype":"compact_boundary","compactMetadata":{"trigger":"auto","preTokens":1234}}"#;
    assert_eq!(parse_line(line(&m.map(compact)[0])), vec![StreamEvent::Compact { trigger: Some("auto".into()), pre_tokens: Some(1234) }]);
}

#[test]
fn the_transcript_is_found_under_the_cwd_slug_or_by_its_id() {
    assert_eq!(transcript::slug("C:\\Users\\a\\x y.z"), "C--Users-a-x-y-z");
    let root = temp("find");
    let p = root.join("projects").join(transcript::slug("C:\\w")).join(format!("{ID}.jsonl"));
    std::fs::create_dir_all(p.parent().unwrap()).unwrap();
    std::fs::write(&p, PROMPT).unwrap();
    assert_eq!(transcript::find(&root, Some("C:\\w"), ID), Some(p.clone()));
    assert_eq!(transcript::find(&root, Some("elsewhere"), ID), Some(p));
    assert_eq!(transcript::find(&root, Some("C:\\w"), "nope"), None);
}

// ---- hooks -------------------------------------------------------------------------------------------------------------

const ASK_CALL: &str = r#"{"hook_event_name":"PreToolUse","tool_name":"AskUserQuestion","tool_input":{"questions":[{"question":"Which colour?","header":"Colour","multiSelect":false,"options":[{"label":"Red (Recommended)","description":"warm"},{"label":"Blue","description":""}]}]}}"#;

#[test]
fn an_ask_user_question_call_becomes_the_owners_card_and_its_answer() {
    let mut asked = Vec::new();
    let out = ch::decide_question(ASK_CALL, |q| {
        asked.push(q.clone());
        Answer { picked: vec!["Blue".into()], other: Some("navy".into()) }
    });
    assert_eq!(asked[0].text, "[Colour] Which colour?\n\n- Red (Recommended): warm");
    assert_eq!(asked[0].options, vec!["Red (Recommended)", "Blue"]);
    assert_eq!(asked[0].recommended.as_deref(), Some("Red (Recommended)"));
    let v: Value = serde_json::from_str(&out).unwrap();
    assert_eq!(v["hookSpecificOutput"]["hookEventName"], "PreToolUse");
    assert_eq!(v["hookSpecificOutput"]["permissionDecision"], "allow");
    assert_eq!(v["hookSpecificOutput"]["updatedInput"]["answers"]["Which colour?"], "Blue, navy");
    assert_eq!(v["hookSpecificOutput"]["updatedInput"]["questions"][0]["header"], "Colour", "the input is kept");
}

#[test]
fn an_unanswered_or_unreadable_question_is_denied_with_a_reason() {
    let v: Value = serde_json::from_str(&ch::decide_question(ASK_CALL, |_| Answer::default())).unwrap();
    assert_eq!(v["hookSpecificOutput"]["permissionDecision"], "deny");
    assert_eq!(v["hookSpecificOutput"]["permissionDecisionReason"], ch::NO_ANSWER);
    let v: Value = serde_json::from_str(&ch::decide_question("{}", |_| panic!("never asked"))).unwrap();
    assert_eq!(v["hookSpecificOutput"]["permissionDecision"], "deny");
}

#[test]
fn the_permission_hook_asks_only_under_ask() {
    let call = r#"{"hook_event_name":"PermissionRequest","tool_name":"Bash","tool_input":{"command":"echo $HOME"}}"#;
    let (out, denied) = ch::decide_permission(ToolApproval::Ask, call, |tool, input| {
        assert_eq!(tool, "Bash");
        assert!(input.contains("echo $HOME"));
        true
    });
    assert_eq!(serde_json::from_str::<Value>(&out).unwrap()["hookSpecificOutput"]["decision"]["behavior"], "allow");
    assert_eq!(denied, None);
    let (out, denied) = ch::decide_permission(ToolApproval::AllowEdits, call, |_, _| panic!("never asked"));
    let v: Value = serde_json::from_str(&out).unwrap();
    assert_eq!(v["hookSpecificOutput"]["hookEventName"], "PermissionRequest");
    assert_eq!(v["hookSpecificOutput"]["decision"]["behavior"], "deny");
    assert_eq!(denied.as_deref(), Some("Bash"));
}

// ---- launch: argv and settings -----------------------------------------------------------------------------------------

fn temp(tag: &str) -> PathBuf {
    let d = std::env::temp_dir().join(format!("infantry-runner-test-{tag}-{}", uuid::Uuid::new_v4()));
    std::fs::create_dir_all(&d).unwrap();
    d
}

struct FixedOwner;
impl Owner for FixedOwner {
    fn ask(&self, _: &str, q: &Question) -> Answer {
        Answer { picked: vec![q.options[0].clone()], other: None }
    }
    fn approve(&self, _: &str, _: &str, _: &str) -> bool {
        true
    }
}

struct Rig {
    root: PathBuf,
    cwd: PathBuf,
    config: PathBuf,
    spec_args: Vec<String>,
    driver: Box<dyn Driver>,
    screen: Screen,
    tx: Sender<Event>,
    rx: Receiver<Event>,
    seen: Vec<Event>,
    written: Vec<String>,
    now: u64,
    hooks: Arc<HookServer>,
}

impl Rig {
    fn new(approval: ToolApproval, resume: Option<&str>, prepare: impl FnOnce(&PathBuf, &PathBuf)) -> Rig {
        let root = temp("rig");
        let (cwd, config, state) = (root.join("work tree"), root.join("config"), root.join("state"));
        std::fs::create_dir_all(&cwd).unwrap();
        prepare(&cwd, &config);
        let hooks = HookServer::start().unwrap();
        let host = Host { state_dir: state, hooks: hooks.clone(), owner: Arc::new(FixedOwner) };
        let claude = Claude { executable: Some("claude.exe".into()), config_dir: Some(config.clone()), ..Default::default() };
        let mut req = LaunchRequest::new(cwd.clone());
        req.model = Some("haiku".into());
        req.approval = approval;
        req.session_id = Some(ID.into());
        req.resume = resume.map(str::to_string);
        let spec = claude.launch(&req, &host).unwrap();
        assert_eq!(spec.program, "claude.exe");
        let (tx, rx) = channel();
        Rig { root, cwd, config, spec_args: spec.args, driver: spec.driver, screen: Screen::new(30, 120), tx, rx, seen: vec![], written: vec![], now: 1_000, hooks }
    }

    fn settings(&self) -> Value {
        let i = self.spec_args.iter().position(|a| a == "--settings").unwrap();
        serde_json::from_str(&std::fs::read_to_string(&self.spec_args[i + 1]).unwrap()).unwrap()
    }

    fn events_file(&self) -> PathBuf {
        self.root.join("state").join("claude-tty").join(format!("{ID}.events"))
    }

    fn event(&self, e: &str) {
        use std::io::Write;
        let mut f = std::fs::OpenOptions::new().create(true).append(true).open(self.events_file()).unwrap();
        write!(f, "{e}\r\n").unwrap();
    }

    fn record(&self, l: &str) {
        use std::io::Write;
        let p = self.config.join("projects").join(transcript::slug(&self.cwd.to_string_lossy())).join(format!("{ID}.jsonl"));
        std::fs::create_dir_all(p.parent().unwrap()).unwrap();
        let mut f = std::fs::OpenOptions::new().create(true).append(true).open(p).unwrap();
        writeln!(f, "{l}").unwrap();
    }

    fn screen(&mut self, s: &str) {
        self.screen.feed(s.as_bytes(), self.now);
    }

    fn with(&mut self, f: impl FnOnce(&mut dyn Driver, &mut Io<'_>)) {
        let mut input = Vec::new();
        {
            let mut io = Io::new(self.now, &self.screen, &mut input, &self.tx);
            f(self.driver.as_mut(), &mut io);
        }
        if !input.is_empty() {
            self.written.push(String::from_utf8(input).unwrap());
        }
        self.seen.extend(self.rx.try_iter());
    }

    fn tick(&mut self) {
        self.with(|d, io| d.tick(io));
    }

    fn send(&mut self, p: &str) {
        let p = p.to_string();
        self.with(move |d, io| d.send_turn(p, io));
    }

    fn up(&mut self) {
        self.screen("\x1b[2J welcome");
        self.event("start");
        self.tick();
        self.now += driver::READY_AFTER_START_MS;
    }

    fn ends(&self) -> Vec<(TurnEnd, Option<String>)> {
        self.seen.iter().filter_map(|e| if let Event::TurnEnded { end, reason } = e { Some((*end, reason.clone())) } else { None }).collect()
    }

    fn output_types(&self) -> Vec<String> {
        self.seen
            .iter()
            .filter_map(|e| if let Event::Output { line, .. } = e { Some(serde_json::from_str::<Value>(line).unwrap()["type"].as_str().unwrap().to_string()) } else { None })
            .collect()
    }
}

impl Drop for Rig {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.root);
    }
}

#[test]
fn the_launch_is_the_interactive_cli_with_a_known_session_id_and_one_settings_file() {
    let r = Rig::new(ToolApproval::AllowAll, None, |_, _| {});
    let a = &r.spec_args;
    assert_eq!(&a[..2], &["--session-id".to_string(), ID.to_string()]);
    assert!(!a.contains(&"-p".to_string()));
    for pair in [["--model", "haiku"], ["--thinking-display", "summarized"], ["--permission-mode", "bypassPermissions"]] {
        let i = a.iter().position(|x| x == pair[0]).unwrap_or_else(|| panic!("{pair:?} in {a:?}"));
        assert_eq!(a[i + 1], pair[1]);
    }
    assert_eq!(a.last().unwrap(), "--strict-mcp-config");
    let s = r.settings();
    assert_eq!(s["skipDangerousModePermissionPrompt"], true);
    assert_eq!(s["showThinkingSummaries"], true);
    let ev = r.events_file().to_string_lossy().replace('\\', "/");
    for (hook, word) in [("SessionStart", "start"), ("UserPromptSubmit", "submit"), ("Stop", "stop")] {
        assert_eq!(s["hooks"][hook][0]["hooks"][0]["command"], format!("echo {word} >> \"{ev}\""));
    }
    assert!(s["hooks"].get("PermissionRequest").is_none(), "allowAll has no permission hook");
    let q = &s["hooks"]["PreToolUse"][0];
    assert_eq!(q["matcher"], "AskUserQuestion", "every mode has the question hook");
    assert!(q["hooks"][0]["url"].as_str().unwrap().starts_with("http://127.0.0.1:"));
}

#[test]
fn ask_mode_carries_the_permission_hook_and_no_bypass_switch() {
    let r = Rig::new(ToolApproval::Ask, None, |_, _| {});
    let i = r.spec_args.iter().position(|x| x == "--permission-mode").unwrap();
    assert_eq!(r.spec_args[i + 1], "default");
    let s = r.settings();
    assert!(s.get("skipDangerousModePermissionPrompt").is_none());
    let url = s["hooks"]["PermissionRequest"][0]["hooks"][0]["url"].as_str().unwrap().to_string();
    // the hook URL answers over the generic channel
    let body = r#"{"hook_event_name":"PermissionRequest","tool_name":"Bash","tool_input":{"command":"ls"}}"#;
    let reply = post(&url, body);
    assert!(reply.contains("\"behavior\":\"allow\""), "{reply}");
    let qurl = s["hooks"]["PreToolUse"][0]["hooks"][0]["url"].as_str().unwrap().to_string();
    let reply = post(&qurl, ASK_CALL);
    assert!(reply.contains("\"Which colour?\":\"Red (Recommended)\""), "{reply}");
    let _ = &r.hooks;
}

fn post(url: &str, body: &str) -> String {
    use std::io::{Read, Write};
    let rest = url.strip_prefix("http://").unwrap();
    let (host, path) = rest.split_at(rest.find('/').unwrap());
    let mut s = std::net::TcpStream::connect(host).unwrap();
    write!(s, "POST {path} HTTP/1.1\r\nHost: {host}\r\nContent-Type: application/json\r\nContent-Length: {}\r\n\r\n{body}", body.len()).unwrap();
    let mut out = String::new();
    s.read_to_string(&mut out).unwrap();
    out
}

#[test]
fn a_resume_of_a_recorded_session_resumes_and_reads_only_what_comes_after() {
    let r = Rig::new(ToolApproval::AllowAll, Some(ID), |cwd, config| {
        let p = config.join("projects").join(transcript::slug(&cwd.to_string_lossy())).join(format!("{ID}.jsonl"));
        std::fs::create_dir_all(p.parent().unwrap()).unwrap();
        std::fs::write(p, format!("{PROMPT}\n{}\n", assistant("old", "history", 1))).unwrap();
    });
    let mut r = r;
    assert_eq!(&r.spec_args[..2], &["--resume".to_string(), ID.to_string()]);
    r.tick();
    assert_eq!(r.output_types(), vec!["system"], "the history is never read as live");
    r.record(&assistant("new", "fresh", 2));
    r.tick();
    assert_eq!(r.output_types(), vec!["system", "assistant"]);
}

#[test]
fn a_resume_of_an_unrecorded_id_starts_it_fresh_under_the_same_id() {
    let r = Rig::new(ToolApproval::AllowAll, Some("99999999-0000-0000-0000-000000000000"), |_, _| {});
    assert_eq!(&r.spec_args[..2], &["--session-id".to_string(), "99999999-0000-0000-0000-000000000000".to_string()]);
}

// ---- the driver --------------------------------------------------------------------------------------------------------

#[test]
fn a_turn_waits_for_the_cli_is_pasted_entered_and_ends_on_the_stop_hook_with_a_result() {
    let mut r = Rig::new(ToolApproval::AllowAll, None, |_, _| {});
    r.send("read a.txt");
    r.tick();
    assert!(r.written.is_empty(), "nothing is typed before the CLI is up");
    assert!(r.seen.contains(&Event::ProviderSession(ID.into())));
    r.up();
    r.tick();
    assert_eq!(r.written, vec!["\x1b[200~read a.txt\x1b[201~"], "bracketed paste");
    r.now += driver::PASTE_SETTLE_MS;
    r.tick();
    assert_eq!(r.written.last().unwrap(), "\r", "then Enter");
    r.event("submit");
    r.record(PROMPT);
    r.record(TOOL_USE);
    r.record(TOOL_RESULT);
    r.tick();
    assert!(r.ends().is_empty(), "still running");
    r.record(&assistant("m1", "done", 4));
    r.event("stop");
    r.tick();
    assert!(r.ends().is_empty(), "the stop settles first");
    r.now += driver::STOP_SETTLE_MS;
    r.tick();
    assert_eq!(r.ends(), vec![(TurnEnd::Completed, None)]);
    assert_eq!(r.output_types(), vec!["system", "assistant", "user", "assistant", "result"]);
    assert_eq!(r.written.len(), 2, "no Enter retry once the prompt was taken");
    let structured: Vec<&StreamEvent> = r.seen.iter().filter_map(|e| if let Event::Output { records, .. } = e { Some(records) } else { None }).flatten().collect();
    assert!(structured.iter().any(|e| matches!(e, StreamEvent::AssistantText { text, .. } if text == "done")));
    assert!(structured.iter().any(|e| matches!(e, StreamEvent::Result { text, is_error: false, .. } if text == "done")));
}

#[test]
fn an_enter_the_paste_swallowed_is_pressed_once_more() {
    let mut r = Rig::new(ToolApproval::AllowAll, None, |_, _| {});
    r.send("x");
    r.up();
    r.tick();
    r.now += driver::PASTE_SETTLE_MS;
    r.tick();
    r.now += driver::SUBMIT_RETRY_MS;
    r.tick();
    assert_eq!(r.written, vec!["\x1b[200~x\x1b[201~", "\r", "\r"]);
    r.now += driver::SUBMIT_RETRY_MS;
    r.tick();
    assert_eq!(r.written.len(), 3, "only once");
}

#[test]
fn a_utf16_stop_from_powershell_ends_the_turn() {
    let mut r = Rig::new(ToolApproval::AllowAll, None, |_, _| {});
    r.send("x");
    r.up();
    r.tick();
    r.now += driver::PASTE_SETTLE_MS;
    r.tick();
    let mut bytes = std::fs::read(r.events_file()).unwrap();
    for c in "stop\r\n".encode_utf16() {
        bytes.extend_from_slice(&c.to_le_bytes());
    }
    std::fs::write(r.events_file(), bytes).unwrap();
    r.tick();
    r.now += driver::STOP_SETTLE_MS;
    r.tick();
    assert_eq!(r.ends(), vec![(TurnEnd::Completed, None)]);
}

#[test]
fn with_hooks_that_write_nothing_the_transcripts_turn_duration_ends_the_turn() {
    let mut r = Rig::new(ToolApproval::AllowAll, None, |_, _| {});
    r.send("x");
    r.screen("welcome");
    r.now += driver::READY_FALLBACK_MS;
    r.tick();
    assert_eq!(r.written.len(), 1, "pasted on the fallback readiness");
    r.now += driver::PASTE_SETTLE_MS;
    r.tick();
    r.record(PROMPT);
    r.record(&assistant("m1", "ok", 1));
    r.record(r#"{"type":"system","subtype":"turn_duration","durationMs":49}"#);
    r.tick();
    r.now += driver::STOP_SETTLE_MS;
    r.tick();
    assert_eq!(r.ends(), vec![(TurnEnd::Completed, None)]);
}

#[test]
fn abort_is_esc_and_a_turn_stopped_before_its_reply_clears_the_input_box() {
    let mut r = Rig::new(ToolApproval::AllowAll, None, |_, _| {});
    r.send("long job");
    r.up();
    r.tick();
    r.now += driver::PASTE_SETTLE_MS;
    r.tick();
    r.event("submit");
    r.tick();
    assert!(r.driver.busy());
    r.with(|d, io| assert!(d.abort_turn(io)));
    assert_eq!(r.written.last().unwrap(), "\x1b");
    r.record(INTERRUPTED);
    r.tick();
    assert_eq!(r.ends(), vec![(TurnEnd::Interrupted, None)]);
    assert_eq!(r.written.last().unwrap(), "\u{3}", "Ctrl+C empties the box the CLI put the prompt back into");
    assert_eq!(r.output_types().last().unwrap(), "result", "an interrupted turn still reports its usage");
    r.send("next");
    r.tick();
    assert_eq!(r.written.last().unwrap(), "\u{3}", "the next paste waits for the box to clear");
    r.now += driver::CLEAR_SETTLE_MS;
    r.tick();
    assert_eq!(r.written.last().unwrap(), "\x1b[200~next\x1b[201~");
}

#[test]
fn abort_ends_interrupted_after_the_grace_even_when_nothing_is_recorded_and_after_a_reply_no_ctrl_c() {
    let mut r = Rig::new(ToolApproval::AllowAll, None, |_, _| {});
    r.send("x");
    r.up();
    r.tick();
    r.now += driver::PASTE_SETTLE_MS;
    r.tick();
    r.record(PROMPT);
    r.record(&assistant("m1", "partial", 1));
    r.tick();
    r.with(|d, io| {
        d.abort_turn(io);
    });
    r.now += driver::ABORT_GRACE_MS;
    r.tick();
    assert_eq!(r.ends(), vec![(TurnEnd::Interrupted, None)]);
    assert!(!r.written.contains(&"\u{3}".to_string()));
}

#[test]
fn an_api_error_the_cli_records_fails_the_turn_with_its_words() {
    let mut r = Rig::new(ToolApproval::AllowAll, None, |_, _| {});
    r.send("x");
    r.up();
    r.tick();
    r.record(r#"{"type":"assistant","isApiErrorMessage":true,"message":{"id":"e","model":"<synthetic>","content":[{"type":"text","text":"API Error: 401"}]}}"#);
    r.tick();
    assert_eq!(r.ends(), vec![(TurnEnd::Failed, Some("API Error: 401".into()))]);
    let last = r.seen.iter().rev().find_map(|e| if let Event::Output { records, .. } = e { Some(records.clone()) } else { None }).unwrap();
    assert!(matches!(&last[0], StreamEvent::Result { is_error: true, text, .. } if text == "API Error: 401"));
}

const TRUST_NO: &str = "\x1b[2J\x1b[1;1HDo you trust the files in this folder?\x1b[3;1H\u{276F} 1. No, exit\x1b[4;1H  2. Yes, I trust this folder";

#[test]
fn the_folder_trust_menu_is_read_from_the_grid_down_then_enter_before_any_paste() {
    let mut r = Rig::new(ToolApproval::AllowAll, None, |_, _| {});
    r.send("x");
    r.screen(TRUST_NO);
    r.tick();
    assert!(r.written.is_empty(), "no key before the screen is quiet");
    r.now += driver::KEY_GAP_MS;
    r.tick();
    assert_eq!(r.written, vec!["\x1b[B"]);
    r.now += driver::KEY_GAP_MS;
    r.tick();
    assert_eq!(r.written.len(), 1, "no second Down before the retry interval");
    // the CLI repaints only the pointer cells (no colour): the grid shows where it is
    r.screen("\x1b[3;1H \x1b[4;1H\u{276F}");
    r.now += driver::KEY_GAP_MS;
    r.tick();
    assert_eq!(r.written, vec!["\x1b[B", "\r"]);
    r.event("start");
    r.screen("\x1b[2J\x1b[1;1H> ");
    r.tick();
    r.now += driver::READY_AFTER_START_MS;
    r.tick();
    assert_eq!(r.written.last().unwrap(), "\x1b[200~x\x1b[201~");
}

#[test]
fn trust_menu_reads_the_row_with_the_pointer() {
    let rows = |s: &str| {
        let mut sc = Screen::new(10, 80);
        sc.feed(s.as_bytes(), 0);
        sc.rows()
    };
    assert_eq!(trust_menu(&rows("hello")), TrustMenu::Absent);
    assert_eq!(trust_menu(&rows(TRUST_NO)), TrustMenu::OnNo);
    assert_eq!(trust_menu(&rows(&format!("{TRUST_NO}\x1b[3;1H \x1b[4;1H\u{276F}"))), TrustMenu::OnYes);
    assert_eq!(trust_menu(&rows("\x1b[1;1H  No, exit\x1b[2;1H  Yes, I trust this folder")), TrustMenu::NoPointer);
}

#[test]
fn a_permission_dialog_nothing_answers_is_answered_no_and_ends_the_turn() {
    let mut r = Rig::new(ToolApproval::Ask, None, |_, _| {});
    r.send("x");
    r.up();
    r.tick();
    r.now += driver::PASTE_SETTLE_MS;
    r.tick();
    r.screen("Bash command\r\n echo hi\r\nDo you want to proceed?\r\n\u{276F} 1. Yes\r\n  2. No, and tell Claude what to do differently");
    r.tick();
    r.now += driver::DIALOG_GRACE_MS;
    r.tick();
    assert_eq!(r.written.last().unwrap(), "\x1b");
    r.record(INTERRUPTED);
    r.tick();
    assert_eq!(r.ends(), vec![(TurnEnd::Interrupted, Some(driver::DIALOG_DENIED_REASON.into()))]);
}

#[test]
fn an_ask_user_question_menu_no_hook_answered_is_cancelled() {
    let mut r = Rig::new(ToolApproval::AllowAll, None, |_, _| {});
    r.send("x");
    r.up();
    r.tick();
    r.now += driver::PASTE_SETTLE_MS;
    r.tick();
    r.screen("\x1b[2J\x1b[1;1HWhich colour?\x1b[2;1H\u{276F} 1. Red\x1b[3;1H  2. Blue\x1b[5;1HEnter to select \u{b7} \u{2191}/\u{2193} to navigate \u{b7} Esc to cancel");
    r.tick();
    r.now += driver::DIALOG_GRACE_MS;
    r.tick();
    assert_eq!(r.written.last().unwrap(), "\x1b");
    r.now += driver::ABORT_GRACE_MS;
    r.tick();
    assert_eq!(r.ends(), vec![(TurnEnd::Interrupted, Some(driver::ASK_MENU_REASON.into()))]);
}

#[test]
fn a_turn_the_cli_begins_by_itself_is_tracked_and_nothing_is_pasted_into_it() {
    let mut r = Rig::new(ToolApproval::AllowAll, None, |_, _| {});
    r.up();
    r.tick();
    r.record(r#"{"type":"user","origin":{"kind":"task-notification"},"message":{"role":"user","content":"<task-notification>b1</task-notification>"}}"#);
    r.tick();
    r.tick();
    assert!(r.seen.contains(&Event::TurnStarted { reason: Some("a background task ended".into()) }));
    r.send("queued");
    r.tick();
    assert!(r.written.is_empty(), "nothing pasted into the CLI's own turn");
    r.record(&assistant("m1", "noted", 1));
    r.event("stop");
    r.tick();
    r.now += driver::STOP_SETTLE_MS;
    r.tick();
    assert_eq!(r.ends(), vec![(TurnEnd::Completed, None)]);
    r.tick();
    assert_eq!(r.written, vec!["\x1b[200~queued\x1b[201~"]);
}

#[test]
fn close_is_esc_then_exit_and_an_exit_fails_open_turns() {
    let mut r = Rig::new(ToolApproval::AllowAll, None, |_, _| {});
    r.up();
    r.with(|d, io| d.close(io));
    assert_eq!(r.written, vec!["\x1b"]);
    r.now += driver::KEY_GAP_MS;
    r.tick();
    r.now += driver::KEY_GAP_MS;
    r.tick();
    assert_eq!(r.written, vec!["\x1b", "\x1b[200~/exit\x1b[201~", "\r"]);
    r.send("late");
    r.with(|d, io| d.on_exit(0, io));
    assert_eq!(r.ends().len(), 1);
    assert_eq!(r.ends()[0].0, TurnEnd::Failed);
    r.send("after");
    assert_eq!(r.ends().len(), 2, "a turn sent after the exit fails at once");
}

#[test]
fn an_npm_shim_is_followed_to_its_exe() {
    let d = temp("shim");
    let exe = d.join("node_modules").join("@x").join("bin").join("tool.exe");
    std::fs::create_dir_all(exe.parent().unwrap()).unwrap();
    std::fs::write(&exe, b"").unwrap();
    let shim = "@ECHO off\r\nCALL :find_dp0\r\n\"%dp0%\\node_modules\\@x\\bin\\tool.exe\"   %*\r\n";
    assert_eq!(shim_target(shim, &d).map(PathBuf::from), Some(exe));
    assert_eq!(shim_target("\"%dp0%\\node_modules\\@x\\bin\\cli.js\" %*", &d), None);
    let _ = std::fs::remove_dir_all(d);
}

#[allow(dead_code)]
fn _owner_is_object_safe(_: Arc<dyn Owner>, _: Mutex<()>) {}
