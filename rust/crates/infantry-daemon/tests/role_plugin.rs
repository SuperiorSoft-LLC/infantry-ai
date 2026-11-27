// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! PM-ROLE-1: the role text a session receives comes from a plugin (Infantry_Roles), never from core.
//!
//! - a `pm` session started by the daemon gets the orchestrator playbook ahead of its first prompt;
//! - with no Infantry_Roles in the plugins folder the first prompt is exactly what was typed (core holds no role text);
//! - a user file `<roles dir>/pm.md` wins over the plugin;
//! - every other role keeps its Kotlin-era text unchanged.
//!
//! Its own test binary: it points `INFANTRY_PLUGINS_DIR` at a temp folder, a process-wide setting (serialised by LOCK).
use infantry_daemon::harness::{Capabilities, EventSink, Harness, HarnessSession, OpenRequest, RunStart, Turn};
use infantry_daemon::sessions::{Config, Daemon, NewOpts};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

static LOCK: Mutex<()> = Mutex::new(());

/// A harness that records the prompt of every turn it is sent and never answers.
#[derive(Default)]
struct Recorder {
    prompts: Arc<Mutex<Vec<String>>>,
}

struct RecSession {
    prompts: Arc<Mutex<Vec<String>>>,
}

impl Harness for Recorder {
    fn name(&self) -> &str {
        "recorder"
    }
    fn capabilities(&self) -> Capabilities {
        Capabilities::default()
    }
    fn open(&self, _req: OpenRequest, _sink: EventSink) -> Result<Box<dyn HarnessSession>, String> {
        Ok(Box::new(RecSession { prompts: self.prompts.clone() }))
    }
}

impl HarnessSession for RecSession {
    fn send_turn(&mut self, turn: Turn) -> Result<RunStart, String> {
        self.prompts.lock().unwrap().push(turn.prompt);
        Ok(RunStart { pid: None, provider_session_id: None })
    }
    fn interrupt(&mut self) -> bool {
        false
    }
    fn close(&mut self, _force: bool) {}
    fn process_alive(&self) -> bool {
        false
    }
}

fn rust_dir() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../..")
}

fn plugin_src() -> PathBuf {
    rust_dir().join("plugins/official/Infantry_Roles")
}

/// The Kotlin-era role texts (history, kept on the tree): the port must not have changed them.
fn kotlin_roles() -> PathBuf {
    rust_dir().join("../plugins/official/Infantry_Roles/daemon/src/main/resources/roles")
}

fn copy_dir(from: &Path, to: &Path) {
    std::fs::create_dir_all(to).unwrap();
    for e in std::fs::read_dir(from).unwrap().flatten() {
        let p = e.path();
        if p.is_dir() {
            copy_dir(&p, &to.join(e.file_name()));
        } else {
            std::fs::copy(&p, to.join(e.file_name())).unwrap();
        }
    }
}

struct Run {
    tmp: PathBuf,
}

impl Drop for Run {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.tmp);
    }
}

/// A fresh state dir and plugins dir (with Infantry_Roles installed or not, plus an optional user role file); starts
/// one session of `role` with first prompt `hello` and answers the prompt the harness received.
fn first_prompt(role: &str, with_plugin: bool, user_file: Option<(&str, &str)>) -> String {
    let tmp = std::env::temp_dir().join(format!("infantry-role-plugin-{}", uuid::Uuid::new_v4()));
    let _cleanup = Run { tmp: tmp.clone() };
    let (state, plugins) = (tmp.join("state"), tmp.join("plugins"));
    std::fs::create_dir_all(plugins.join("official")).unwrap();
    std::fs::create_dir_all(&state).unwrap();
    if with_plugin {
        copy_dir(&plugin_src(), &plugins.join("official/Infantry_Roles"));
    }
    if let Some((id, text)) = user_file {
        std::fs::create_dir_all(state.join("roles")).unwrap();
        std::fs::write(state.join("roles").join(format!("{id}.md")), text).unwrap();
    }
    std::env::set_var("INFANTRY_PLUGINS_DIR", &plugins);
    std::env::remove_var("INFANTRY_ROLES_DIR");
    let rec = Recorder::default();
    let prompts = rec.prompts.clone();
    let daemon = Daemon::new(Arc::new(rec), Config::new(state.clone(), tmp.clone())).unwrap();
    let _c = infantry_daemon::compose::build(&daemon, &state).unwrap();
    daemon.launch(NewOpts { role: Some(role.into()), prompt: Some("hello".into()), ..Default::default() }).unwrap();
    let deadline = Instant::now() + Duration::from_secs(10);
    loop {
        if let Some(p) = prompts.lock().unwrap().first().cloned() {
            return p;
        }
        assert!(Instant::now() < deadline, "the {role} session never sent its first turn");
        std::thread::sleep(Duration::from_millis(20));
    }
}

const PLAYBOOK: &str = "orchestrates, verifies and decides";

#[tokio::test(flavor = "multi_thread")]
async fn a_pm_session_receives_the_orchestrator_playbook_from_the_plugin() {
    let _g = LOCK.lock().unwrap_or_else(|e| e.into_inner());
    let p = first_prompt("pm", true, None);
    assert!(p.contains(PLAYBOOK), "a pm session's first turn carries the playbook:\n{p}");
    for tool in ["infantry_ticket_start", "infantry_spawn", "infantry_message", "version-control"] {
        assert!(p.contains(tool), "the playbook names Infantry's own tool/role `{tool}`:\n{p}");
    }
    assert!(p.contains("Report status with evidence, not optimism"), "the six existing pm bullets are kept:\n{p}");
    assert!(p.trim_end().ends_with("hello"), "the typed prompt follows the role text:\n{p}");
}

#[tokio::test(flavor = "multi_thread")]
async fn without_the_plugin_core_gives_no_role_text() {
    let _g = LOCK.lock().unwrap_or_else(|e| e.into_inner());
    let p = first_prompt("pm", false, None);
    assert_eq!(p, "hello", "no Infantry_Roles installed: core adds no role text");
}

#[tokio::test(flavor = "multi_thread")]
async fn a_user_role_file_wins_over_the_plugin() {
    let _g = LOCK.lock().unwrap_or_else(|e| e.into_inner());
    let p = first_prompt("pm", true, Some(("pm", "---\nname: PM\ncolor: #eab308\n---\nOWNER PM OVERRIDE\n")));
    assert!(p.contains("OWNER PM OVERRIDE"), "{p}");
    assert!(!p.contains(PLAYBOOK), "the user file replaces the plugin's text:\n{p}");
}

#[tokio::test(flavor = "multi_thread")]
async fn other_roles_keep_their_text() {
    let _g = LOCK.lock().unwrap_or_else(|e| e.into_inner());
    let backend = std::fs::read_to_string(plugin_src().join("roles").join("backend.md")).unwrap().replace("\r\n", "\n");
    let p = first_prompt("backend", true, None);
    assert!(p.contains(backend.trim()), "a backend session gets the backend text unchanged:\n{p}");
    let mut compared = 0;
    for e in std::fs::read_dir(kotlin_roles()).unwrap().flatten() {
        let name = e.file_name().to_string_lossy().into_owned();
        if !name.ends_with(".md") || name == "pm.md" {
            continue;
        }
        let ported = std::fs::read(plugin_src().join("roles").join(&name)).unwrap_or_else(|_| panic!("{name} is not in the plugin"));
        // SCRIPT-TOOLS-MISSING-1 (1f39d7687) deliberately replaced the non-existent tool infantry_ticket_related_files in
        // the briefs with the CLI form; that one edit is applied to the Kotlin text, every other byte must match.
        let kotlin = std::fs::read_to_string(e.path()).unwrap().replace(
            "call infantry_ticket_related_files (or `infantry ticket files <id> --related`)",
            "run `infantry ticket files <id> --related`",
        );
        assert_eq!(String::from_utf8(ported).unwrap().replace("\r\n", "\n"), kotlin.replace("\r\n", "\n"), "{name} changed in the port");
        compared += 1;
    }
    assert!(compared >= 16, "every non-pm role was compared ({compared})");
}
