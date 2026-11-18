// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! [`Headless`]: the fallback translator, one `claude -p --output-format stream-json --verbose` process per turn (the
//! prompt on stdin). Its `system`/`init` line names the session, its `result` line ends the turn with the usage.

use crate::hooks::Answers;
use crate::stream;
use infantry_harness::{ApprovalMode, HarnessEvent, Outbound, Translator, TranslatorInput, TurnEnd};
use serde_json::Value;

pub struct Headless {
    answers: Answers,
    out: Vec<Outbound>,
    ended: bool,
    started: bool,
    last_stderr: Option<String>,
}

impl Headless {
    pub fn new(approval: ApprovalMode) -> Self {
        Headless { answers: Answers::new(approval), out: Vec::new(), ended: false, started: false, last_stderr: None }
    }

    fn line(&mut self, line: &str) -> Vec<HarnessEvent> {
        let mut out = vec![HarnessEvent::Stdout(line.to_string())];
        let Ok(v) = serde_json::from_str::<Value>(line) else { return out };
        if !self.started && matches!(stream::s(&v, "type"), "system" | "assistant") {
            self.started = true;
            out.push(HarnessEvent::TurnStarted);
        }
        out.extend(stream::events(&v));
        if stream::s(&v, "type") == "result" && !self.ended {
            self.ended = true;
            let failed = v.get("is_error").and_then(Value::as_bool).unwrap_or(false);
            let reason = failed.then(|| {
                let r = stream::s(&v, "result");
                if r.is_empty() { stream::s(&v, "subtype").to_string() } else { r.to_string() }
            });
            if let Some(r) = &reason {
                out.push(HarnessEvent::Error(r.clone()));
            }
            out.push(HarnessEvent::TurnEnded { end: if failed { TurnEnd::Failed } else { TurnEnd::Completed }, reason });
        }
        out
    }
}

impl Translator for Headless {
    fn feed(&mut self, input: TranslatorInput) -> Vec<HarnessEvent> {
        match input {
            TranslatorInput::Stdout(l) => self.line(&l),
            TranslatorInput::Stderr(l) => {
                if !l.trim().is_empty() {
                    self.last_stderr = Some(l.clone());
                }
                vec![HarnessEvent::Stderr(l)]
            }
            TranslatorInput::Hook { id, body } => {
                let mut ob = Vec::new();
                let ev = self.answers.call(id, &body, &mut ob);
                self.out.extend(ob);
                ev
            }
            TranslatorInput::Exited { code } => {
                let mut out = Vec::new();
                if !self.ended {
                    self.ended = true;
                    let why = self.last_stderr.take().map(|e| format!(": {e}")).unwrap_or_default();
                    out.push(HarnessEvent::TurnEnded {
                        end: TurnEnd::Failed,
                        reason: Some(format!("claude exited (code {code}) without a result{why}")),
                    });
                }
                out.push(HarnessEvent::Exited { code: Some(code) });
                out
            }
            _ => Vec::new(),
        }
    }

    fn answer(&mut self, id: &str, allow: bool) {
        let mut ob = Vec::new();
        self.answers.answer(id, allow, &mut ob);
        self.out.extend(ob);
    }

    fn take_outbound(&mut self) -> Vec<Outbound> {
        std::mem::take(&mut self.out)
    }
}
