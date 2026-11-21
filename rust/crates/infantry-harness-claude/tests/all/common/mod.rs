// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! Shared by the offline tests: the recorded fixtures and a probe over an in-memory machine.

#![allow(dead_code)]

use infantry_harness::Probe;
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::Mutex;

pub const ID: &str = "11111111-2222-3333-4444-555555555555";
pub const TURN: &str = include_str!("../../fixtures/turn.jsonl");
/// RESTART-HANG-1: the record a resumed claude (2.1.330) wrote about the background jobs its killed session left, as
/// recorded from a live ticket restart (paths and ids neutral). It carries `queueTranscriptOnly`: shown, never run.
pub const RESUME_NOTICE: &str = include_str!("../../fixtures/resume_notice.jsonl");
/// A background task's real wake-up: the same prompt kind, but the CLI runs a turn on it (`turnOrigin`, `turnPosition`).
pub const WAKE_UP: &str = r#"{"type":"user","promptSource":"system","origin":{"kind":"task-notification","producer":"session-task","runId":"r1"},"turnOrigin":"task_notification","turnPosition":{"promptIndex":0,"turnIndex":2},"queueSkipAttachments":true,"message":{"role":"user","content":"<task-notification>\n<task-id>b1</task-id>\n<status>completed</status>\n</task-notification>"}}"#;
const SCREENS: &str = include_str!("../../fixtures/screens.txt");
pub const CLAUDE_RUN: &str =
    include_str!("../../../../../../superiorSoftLibrary/infantry/src/test/resources/retrieval/claude-run.jsonl");
pub const CLAUDE_TASKS: &str =
    include_str!("../../../../../../superiorSoftLibrary/infantry/src/test/resources/retrieval/claude-task-tools.jsonl");

pub const INTERRUPTED: &str = r#"{"type":"user","message":{"role":"user","content":[{"type":"text","text":"[Request interrupted by user]"}]}}"#;
pub const PROMPT: &str = r#"{"type":"user","uuid":"u-p","entrypoint":"cli","message":{"role":"user","content":"read a.txt"}}"#;

pub fn assistant(id: &str, text: &str, output: u64) -> String {
    format!(
        r#"{{"type":"assistant","uuid":"u-{id}-{output}","message":{{"id":"{id}","role":"assistant","model":"claude-haiku-5-5","content":[{{"type":"text","text":"{text}"}}],"usage":{{"input_tokens":3,"output_tokens":{output},"cache_read_input_tokens":100,"cache_creation_input_tokens":50}}}}}}"#
    )
}

/// One recorded screen's rows, by name.
pub fn screen(name: &str) -> Vec<String> {
    let mut cur: Option<&str> = None;
    let mut rows = Vec::new();
    for l in SCREENS.lines() {
        if let Some(n) = l.strip_prefix("== ") {
            if cur == Some(name) {
                return rows;
            }
            cur = Some(n.trim());
            continue;
        }
        if cur == Some(name) {
            rows.push(l.trim_end().to_string());
        }
    }
    assert!(cur == Some(name), "no screen {name} in the fixtures");
    rows
}

/// A machine: env, a home, files (path -> text).
#[derive(Default)]
pub struct FakeProbe {
    pub env: HashMap<String, String>,
    pub home: Option<PathBuf>,
    pub files: Mutex<HashMap<PathBuf, String>>,
}

impl FakeProbe {
    pub fn with_home(home: &str) -> Self {
        FakeProbe { home: Some(PathBuf::from(home)), ..Default::default() }
    }
    pub fn file(self, p: impl Into<PathBuf>, text: &str) -> Self {
        self.files.lock().unwrap().insert(p.into(), text.into());
        self
    }
    pub fn var(mut self, k: &str, v: &str) -> Self {
        self.env.insert(k.into(), v.into());
        self
    }
}

impl Probe for FakeProbe {
    fn env(&self, name: &str) -> Option<String> {
        self.env.get(name).cloned()
    }
    fn home(&self) -> Option<PathBuf> {
        self.home.clone()
    }
    fn read_to_string(&self, path: &Path) -> Option<String> {
        self.files.lock().unwrap().get(path).cloned()
    }
    fn exists(&self, path: &Path) -> bool {
        self.files.lock().unwrap().contains_key(path)
    }
}
