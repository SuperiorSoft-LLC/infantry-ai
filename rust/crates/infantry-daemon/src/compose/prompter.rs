// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The sessions' [`Prompter`]: a CLI's tool approval or question becomes an owner prompt card in the mail crate's
//! [`PendingPrompts`] (`prompt_request` to every client, closed by `prompt_response` with `prompt_resolved`), and the
//! answer goes back to the CLI. The approval mode is Kotlin's `ToolApprovals` order: the `toolApproval.mode` setting
//! (session, role, project, then app scope), then `INFANTRY_TOOL_APPROVAL`, then `ask` (FIRSTRUN-PERM-1).
use std::sync::Arc;

use infantry_harness::{ApprovalMode, HarnessAnswer, HarnessQuestion};
use infantry_mail::notify::prompts::{PendingPrompts, QuestionSpec};
use infantry_protocol::prompts::PromptOption;
use serde_json::{json, Value};

use super::settings::SettingsUnit;
use crate::harness::Prompter;

pub const MODE_KEY: &str = "toolApproval.mode";
pub const MODE_ENV: [&str; 2] = ["INFANTRY_TOOL_APPROVAL", "AGENTHQ_TOOL_APPROVAL"];
pub const DEFAULT_MODE: ApprovalMode = ApprovalMode::Ask;

/// A mode's wire name (`deny`, `ask`, `allowEdits`, `allowAll`; case and `_`/`-` ignored).
pub fn parse_mode(text: &str) -> Option<ApprovalMode> {
    match text.trim().to_ascii_lowercase().replace(['_', '-', ' '], "").as_str() {
        "deny" => Some(ApprovalMode::Deny),
        "ask" => Some(ApprovalMode::Ask),
        "allowedits" => Some(ApprovalMode::AllowEdits),
        "allowall" => Some(ApprovalMode::AllowAll),
        _ => None,
    }
}

pub struct SessionPrompter {
    pub prompts: Arc<PendingPrompts>,
    pub settings: SettingsUnit,
    pub handle: tokio::runtime::Handle,
}

impl SessionPrompter {
    fn resolve(&self, session: &str, project: Option<&str>, role: Option<&str>, env: impl Fn(&str) -> Option<String>) -> ApprovalMode {
        let d = json!({"session": session, "project": project, "role": role});
        let stored = self.settings.resolve(MODE_KEY, &d);
        if let Some(m) = stored.get("value").and_then(Value::as_str).and_then(parse_mode) {
            return m;
        }
        MODE_ENV.iter().find_map(|k| env(k).and_then(|v| parse_mode(&v))).unwrap_or(DEFAULT_MODE)
    }
}

/// A CLI question as a card.
pub fn spec_of(q: &HarnessQuestion) -> QuestionSpec {
    QuestionSpec {
        question: q.text.clone(),
        options: q.options.iter().map(|(label, description)| PromptOption { label: label.clone(), description: description.clone() }).collect(),
        multi_select: q.multi_select,
        allow_other: q.allow_other,
        timeout_ms: None,
        recommended: q.recommended.clone(),
    }
}

impl Prompter for SessionPrompter {
    fn mode(&self, session: &str, project: Option<&str>, role: Option<&str>) -> ApprovalMode {
        self.resolve(session, project, role, |k| std::env::var(k).ok())
    }

    fn approve(&self, session: &str, tool: &str, input: &str, done: Box<dyn FnOnce(bool) + Send>) {
        let (p, s, t, i) = (self.prompts.clone(), session.to_string(), tool.to_string(), input.to_string());
        self.handle.spawn(async move {
            let out = p.request_approval(&s, &t, &i, None).await;
            done(out.decision.allows());
        });
    }

    fn ask(&self, session: &str, questions: Vec<HarnessQuestion>, done: Box<dyn FnOnce(Vec<HarnessAnswer>) + Send>) {
        let (p, s) = (self.prompts.clone(), session.to_string());
        self.handle.spawn(async move {
            let mut answers = Vec::with_capacity(questions.len());
            for q in &questions {
                let a = p.ask_question(&s, &spec_of(q)).await;
                let answered = a.answered;
                answers.push(HarnessAnswer { answered, answers: a.answers, other_text: a.other_text });
                if !answered {
                    break;
                }
            }
            done(answers);
        });
    }

    fn end(&self, session: &str) {
        self.prompts.end_session(session);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use super::super::settings::SETTINGS_SET;
    use infantry_core::bus::EventBus;
    use infantry_core::db::Db;
    use infantry_core::frames::{Envelope, FrameCtx, FrameUnit};
    use infantry_mail::notify::prompts::{Decision, TimeoutPolicy};
    use infantry_protocol::prompts::{PromptRequest, PromptResponse};
    use std::sync::Mutex;
    use std::time::Duration;

    type Frames = Arc<Mutex<Vec<(String, String)>>>;

    /// A prompter over a throwaway settings db and a prompt store with no timer; the frames the store emits are collected.
    fn prompter() -> (SessionPrompter, Frames, std::path::PathBuf) {
        let dir = std::env::temp_dir().join(format!("infantry-prompter-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&dir).unwrap();
        let db = Arc::new(Db::open(dir.join("infantry.db")).unwrap());
        db.write(|c| c.execute_batch("CREATE TABLE IF NOT EXISTS settings (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL, updated_at INTEGER NOT NULL)")).unwrap();
        let prompts = Arc::new(PendingPrompts::new(None, Arc::new(|_| None), Arc::new(|_| TimeoutPolicy { timeout_ms: 0, on_timeout: Decision::Deny }), Arc::new(|| 0)));
        let frames: Frames = Arc::default();
        let sink = frames.clone();
        prompts.set_sink(move |ty, data, _session| sink.lock().unwrap().push((ty.to_string(), data)));
        (SessionPrompter { prompts, settings: SettingsUnit { db }, handle: tokio::runtime::Handle::current() }, frames, dir)
    }

    /// A `settings_set` through the unit's own frame, as a client sends it.
    fn set(p: &SessionPrompter, body: Value) {
        let ctx = FrameCtx { conn_id: 1, owner: true, bus: EventBus::new(8) };
        p.settings.handle(&Envelope::new(SETTINGS_SET, body.to_string()), &ctx).unwrap();
    }

    fn mode(p: &SessionPrompter, session: &str, project: Option<&str>, env: Option<&str>) -> ApprovalMode {
        p.resolve(session, project, None, |k| env.filter(|_| k == MODE_ENV[0]).map(String::from))
    }

    async fn frame(frames: &Frames, ty: &str) -> String {
        for _ in 0..300 {
            if let Some((_, data)) = frames.lock().unwrap().iter().find(|(t, _)| t == ty) {
                return data.clone();
            }
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
        panic!("no {ty} frame was emitted");
    }

    /// APPROVAL-ASK-1: QA set `toolApproval.mode=ask` on a scratch daemon; the value is the one a session launches with.
    #[tokio::test(flavor = "multi_thread")]
    async fn a_session_asks_unless_a_setting_or_the_environment_says_otherwise() {
        let (p, _, dir) = prompter();
        assert_eq!(mode(&p, "s1", Some("proj"), None), ApprovalMode::Ask, "nothing set: the fresh-install default asks");
        assert_eq!(mode(&p, "s1", Some("proj"), Some("allowAll")), ApprovalMode::AllowAll, "the environment comes after the settings, before the default");
        set(&p, json!({"key": MODE_KEY, "value": "ask"}));
        assert_eq!(mode(&p, "s1", Some("proj"), Some("allowAll")), ApprovalMode::Ask, "the stored app value beats the environment");
        set(&p, json!({"key": MODE_KEY, "value": "allowEdits", "scope": "project", "project": "proj"}));
        assert_eq!(mode(&p, "s1", Some("proj"), None), ApprovalMode::AllowEdits, "the project beats the app");
        assert_eq!(mode(&p, "s1", Some("other"), None), ApprovalMode::Ask, "another project keeps the app value");
        set(&p, json!({"key": MODE_KEY, "value": "deny", "scope": "session", "session": "s1"}));
        assert_eq!(mode(&p, "s1", Some("proj"), None), ApprovalMode::Deny, "the session beats the project");
        assert_eq!(mode(&p, "s2", Some("proj"), None), ApprovalMode::AllowEdits, "a sibling session keeps the project's");
        set(&p, json!({"key": MODE_KEY, "value": "yolo"}));
        assert_eq!(mode(&p, "s3", None, None), ApprovalMode::Ask, "an unknown stored value is no setting: the default applies");
        let _ = std::fs::remove_dir_all(dir);
    }

    /// APPROVAL-ASK-1: in ask mode a tool call that reaches the harness's approval raises ONE owner card, the CLI is held
    /// (nothing answers it), the open card is what `session.blockedMinutes` reads, and the owner's answer releases it.
    #[tokio::test(flavor = "multi_thread")]
    async fn an_approval_raises_a_card_and_holds_the_cli_until_the_owner_answers() {
        for (decision, allowed) in [("allow_once", true), ("deny", false)] {
            let (p, frames, dir) = prompter();
            let (tx, rx) = std::sync::mpsc::channel();
            p.approve("s1", "Bash", r#"{"command":"mkdir x"}"#, Box::new(move |allow| tx.send(allow).unwrap()));
            let card: PromptRequest = serde_json::from_str(&frame(&frames, "prompt_request").await).unwrap();
            assert_eq!((card.kind.as_str(), card.tool.as_deref(), card.session_id.as_str()), ("tool_approval", Some("Bash"), "s1"), "{decision}");
            assert!(card.input.as_deref().is_some_and(|i| i.contains("mkdir x")), "the card shows what would run: {card:?}");
            assert!(rx.recv_timeout(Duration::from_millis(300)).is_err(), "{decision}: the CLI is held while the card is open");
            let open = p.prompts.snapshot();
            assert_eq!(open.iter().map(|c| c.session_id.as_str()).collect::<Vec<_>>(), vec!["s1"], "{decision}: the open card is the blocked-minutes input");
            let reply: PromptResponse = serde_json::from_value(json!({"promptId": card.prompt_id, "decision": decision})).unwrap();
            p.prompts.respond(&reply, "owner").unwrap();
            assert_eq!(rx.recv_timeout(Duration::from_secs(5)).unwrap(), allowed, "{decision}");
            assert!(p.prompts.snapshot().is_empty(), "{decision}: an answered card closes");
            let _ = std::fs::remove_dir_all(dir);
        }
    }

    #[test]
    fn modes_parse_from_their_wire_names() {
        assert_eq!(parse_mode("allowAll"), Some(ApprovalMode::AllowAll));
        assert_eq!(parse_mode("allow_edits"), Some(ApprovalMode::AllowEdits));
        assert_eq!(parse_mode(" ASK "), Some(ApprovalMode::Ask));
        assert_eq!(parse_mode("deny"), Some(ApprovalMode::Deny));
        assert_eq!(parse_mode("yolo"), None);
    }

    #[test]
    fn a_cli_question_keeps_its_labels_and_recommendation() {
        let q = HarnessQuestion { text: "Which?".into(), options: vec![("A".into(), "first".into()), ("B".into(), String::new())], multi_select: true, allow_other: true, recommended: Some("A".into()) };
        let s = spec_of(&q);
        assert_eq!(s.options.len(), 2);
        assert_eq!(s.options[0].description, "first");
        assert!(s.multi_select && s.allow_other);
        assert_eq!(s.recommended.as_deref(), Some("A"));
    }
}
