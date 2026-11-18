// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The notification-side frame units: attention, owner questions, prompts and approval rules, device notify, and
//! the notification bus's owner clicks.
use std::sync::Arc;

use infantry_core::frames::{Envelope, FrameCtx, FrameUnit, Refusal};
use infantry_protocol::frames as f;
use infantry_protocol::prompts::{ApprovalRuleDelete, ApprovalRulesList, ApprovalRulesQuery, PromptAnswer, PromptAsk, PromptResponse, QuestionAnswer, QuestionAsk, QuestionAsked, QuestionCancel};
use serde::Deserialize;
use serde_json::json;

use super::approvals::ApprovalRules;
use super::attention::AttentionStore;
use super::hub::{NotificationActionEvent, NotificationHub, NOTIFICATIONS, NOTIFICATION_ACTION, NOTIFICATION_DISMISS, NOTIFICATION_LIST};
use super::prompts::{PendingPrompts, QuestionSpec};
use super::push::{NotifyRequest, NotifyService, NOTIFY_ACK, NOTIFY_PENDING, NOTIFY_PENDING_LIST, NOTIFY_SOUND, NOTIFY_SOUNDS, NOTIFY_SOUNDS_LIST, NOTIFY_SOUND_GET};
use super::questions::{Asker, OwnerQuestions};
use crate::mail::unit::{json as to_json, reply};

fn err(env: &Envelope, text: String) -> Envelope {
    reply(env, f::ERROR, text)
}

fn parse<T: for<'de> Deserialize<'de>>(env: &Envelope) -> Result<T, Refusal> {
    serde_json::from_str(&env.data).map_err(|_| Refusal::new(&env.r#type, "malformed payload"))
}

// ---- attention ----

pub struct AttentionUnit {
    store: Arc<AttentionStore>,
}

impl AttentionUnit {
    pub fn new(store: Arc<AttentionStore>) -> Self {
        AttentionUnit { store }
    }
}

impl FrameUnit for AttentionUnit {
    fn name(&self) -> &'static str {
        "attention"
    }
    fn frames(&self) -> &'static [&'static str] {
        &[f::ATTENTION_LIST, f::ATTENTION_ACK]
    }
    fn handle(&self, env: &Envelope, _ctx: &FrameCtx) -> Result<Vec<Envelope>, Refusal> {
        match env.r#type.as_str() {
            f::ATTENTION_LIST => Ok(vec![reply(env, f::ATTENTION_ITEMS, to_json(&self.store.list(false)))]),
            _ => {
                // Success answers nothing: the change is broadcast as an `attention` frame.
                self.store.acknowledge(env.data.trim().trim_matches('"')).map_err(|e| Refusal::new(f::ATTENTION_ACK, e))?;
                Ok(vec![])
            }
        }
    }
}

// ---- owner questions ----

/// Resolves an asking session to its agent/role/project.
pub type AskerOf = Arc<dyn Fn(&str) -> Asker + Send + Sync>;

pub struct QuestionsUnit {
    questions: Arc<OwnerQuestions>,
    asker_of: AskerOf,
}

impl QuestionsUnit {
    pub fn new(questions: Arc<OwnerQuestions>, asker_of: AskerOf) -> Self {
        QuestionsUnit { questions, asker_of }
    }
}

impl FrameUnit for QuestionsUnit {
    fn name(&self) -> &'static str {
        "questions"
    }
    fn frames(&self) -> &'static [&'static str] {
        &[f::QUESTION_ASK, f::QUESTION_LIST, f::QUESTION_ANSWER, f::QUESTION_CANCEL]
    }
    fn handle(&self, env: &Envelope, _ctx: &FrameCtx) -> Result<Vec<Envelope>, Refusal> {
        let t = env.r#type.as_str();
        let session = env.session_id.clone().filter(|s| !s.trim().is_empty());
        match t {
            f::QUESTION_ASK => {
                let s = session.ok_or_else(|| Refusal::new(t, "question_ask needs the asking session (sessionId)"))?;
                let ask: QuestionAsk = parse(env)?;
                let q = self.questions.ask(&(self.asker_of)(&s), &ask).map_err(|e| Refusal::new(t, e))?;
                Ok(vec![reply(env, f::QUESTION_ASKED, to_json(&QuestionAsked { question_id: q.id, note: QuestionAsked::NON_BLOCKING_NOTE.into() }))])
            }
            f::QUESTION_LIST => Ok(vec![reply(env, f::QUESTIONS, to_json(&self.questions.list()))]),
            f::QUESTION_ANSWER => {
                if session.is_some() {
                    return Err(Refusal::new(t, "only the owner answers a question; an agent cannot"));
                }
                let a: QuestionAnswer = parse(env)?;
                let q = self.questions.answer(&a).map_err(|e| Refusal::new(t, e))?;
                Ok(vec![reply(env, f::QUESTION, to_json(&q))])
            }
            _ => {
                let c: QuestionCancel = parse(env)?;
                let q = self.questions.cancel(&c.question_id, session.as_deref()).map_err(|e| Refusal::new(t, e))?;
                Ok(vec![reply(env, f::QUESTION, to_json(&q))])
            }
        }
    }
}

// ---- prompts and approval rules ----

pub struct PromptsUnit {
    prompts: Arc<PendingPrompts>,
    rules: Option<Arc<ApprovalRules>>,
}

impl PromptsUnit {
    pub fn new(prompts: Arc<PendingPrompts>, rules: Option<Arc<ApprovalRules>>) -> Self {
        PromptsUnit { prompts, rules }
    }
}

impl FrameUnit for PromptsUnit {
    fn name(&self) -> &'static str {
        "prompts"
    }
    fn frames(&self) -> &'static [&'static str] {
        &[f::PROMPT_RESPONSE, f::PROMPT_ASK, f::APPROVAL_RULES, f::APPROVAL_RULE_DELETE]
    }
    fn handle(&self, env: &Envelope, ctx: &FrameCtx) -> Result<Vec<Envelope>, Refusal> {
        let t = env.r#type.as_str();
        match t {
            f::PROMPT_RESPONSE => {
                let r: PromptResponse = parse(env)?;
                let who = if ctx.owner { "owner".to_string() } else { format!("client:{}", ctx.conn_id) };
                // The resolution is broadcast as `prompt_resolved` by the prompts sink.
                self.prompts.respond(&r, &who).map_err(|e| Refusal::new(t, e))?;
                Ok(vec![])
            }
            // A session's blocking question (Kotlin `prompt_ask`): a card now, its `prompt_answer` (to the asking
            // session) once the owner answers, the timer fires or the session ends.
            f::PROMPT_ASK => {
                let asker = env.session_id.as_deref().map(str::trim).filter(|s| !s.is_empty()).map(str::to_string);
                let asker = asker.ok_or_else(|| Refusal::new(t, "no session: the envelope's sessionId names the asking session"))?;
                let ask: PromptAsk = parse(env)?;
                let spec = QuestionSpec::of(&ask).map_err(|e| Refusal::new(t, e))?;
                let rt = tokio::runtime::Handle::try_current().map_err(|_| Refusal::new(t, "no runtime to wait for the answer"))?;
                let (prompts, bus) = (self.prompts.clone(), ctx.bus.clone());
                rt.spawn(async move {
                    let a = prompts.ask_question(&asker, &spec).await;
                    let mut e = Envelope::new(f::PROMPT_ANSWER, to_json(&PromptAnswer { answered: a.answered, answers: a.answers, other_text: a.other_text }));
                    e.session_id = Some(asker);
                    bus.publish(e);
                });
                Ok(vec![])
            }
            f::APPROVAL_RULES => {
                let q: ApprovalRulesQuery = if env.data.trim().is_empty() { ApprovalRulesQuery { project_id: None } } else { parse(env)? };
                let rules = self.rules.as_ref().map(|r| r.list(q.project_id.as_deref())).unwrap_or_default();
                Ok(vec![reply(env, f::APPROVAL_RULES, to_json(&ApprovalRulesList { project_id: q.project_id, rules }))])
            }
            _ => {
                let d: ApprovalRuleDelete = parse(env)?;
                let r = self.rules.as_ref().ok_or_else(|| Refusal::new(t, "no approval rule store"))?;
                let gone = r.delete(&d.rule_id).map_err(|e| Refusal::new(t, e))?;
                let rules = r.list(Some(&gone.project_id));
                Ok(vec![reply(env, f::APPROVAL_RULES, to_json(&ApprovalRulesList { project_id: Some(gone.project_id), rules }))])
            }
        }
    }
}

// ---- device notify ----

#[derive(Deserialize)]
struct Ack {
    id: String,
    #[serde(default)]
    device: Option<String>,
}

#[derive(Deserialize)]
struct SoundGet {
    name: String,
}

#[derive(Deserialize, Default)]
struct PendingQuery {
    #[serde(default)]
    device: Option<String>,
}

pub struct NotifyUnit {
    svc: Arc<NotifyService>,
}

impl NotifyUnit {
    pub fn new(svc: Arc<NotifyService>) -> Self {
        NotifyUnit { svc }
    }
}

impl FrameUnit for NotifyUnit {
    fn name(&self) -> &'static str {
        "notify"
    }
    fn frames(&self) -> &'static [&'static str] {
        &[f::NOTIFY, NOTIFY_ACK, NOTIFY_PENDING, NOTIFY_SOUNDS, NOTIFY_SOUND_GET]
    }
    fn handle(&self, env: &Envelope, _ctx: &FrameCtx) -> Result<Vec<Envelope>, Refusal> {
        let t = env.r#type.as_str();
        match t {
            f::NOTIFY => {
                let req: NotifyRequest = parse(env)?;
                match self.svc.notify(&req, None).map_err(|e| Refusal::new(t, e))? {
                    super::push::NotifyOutcome::Sent { message, silent_reason } => {
                        Ok(vec![reply(env, f::NOTIFY, json!({ "sent": true, "id": message.id, "silentReason": silent_reason }).to_string())])
                    }
                    super::push::NotifyOutcome::Off(why) => Ok(vec![reply(env, f::NOTIFY, json!({ "sent": false, "reason": why }).to_string())]),
                }
            }
            NOTIFY_ACK => {
                let a: Ack = parse(env)?;
                if !self.svc.ack(&a.id, a.device.as_deref()) {
                    return Ok(vec![err(env, format!("unknown notification '{}'", a.id))]);
                }
                Ok(vec![])
            }
            NOTIFY_PENDING => {
                let q: PendingQuery = if env.data.trim().is_empty() { PendingQuery::default() } else { parse(env)? };
                Ok(vec![reply(env, NOTIFY_PENDING_LIST, to_json(&self.svc.pending(q.device.as_deref())))])
            }
            NOTIFY_SOUNDS => Ok(vec![reply(env, NOTIFY_SOUNDS_LIST, to_json(&self.svc.sounds.list()))]),
            _ => {
                let g: SoundGet = parse(env)?;
                use base64::Engine;
                match self.svc.sounds.resolve(Some(&g.name)).map_err(|e| Refusal::new(t, e))? {
                    Some(s) => Ok(vec![reply(env, NOTIFY_SOUND, json!({ "name": s.name, "type": s.r#type, "data": base64::engine::general_purpose::STANDARD.encode(&s.bytes) }).to_string())]),
                    None => Ok(vec![err(env, format!("no sound '{}'", g.name))]),
                }
            }
        }
    }
}

// ---- notification bus clicks ----

pub struct NotificationsUnit {
    hub: Arc<NotificationHub>,
}

impl NotificationsUnit {
    pub fn new(hub: Arc<NotificationHub>) -> Self {
        NotificationsUnit { hub }
    }
}

impl FrameUnit for NotificationsUnit {
    fn name(&self) -> &'static str {
        "notifications"
    }
    fn frames(&self) -> &'static [&'static str] {
        &[NOTIFICATION_ACTION, NOTIFICATION_DISMISS, NOTIFICATION_LIST]
    }
    fn handle(&self, env: &Envelope, ctx: &FrameCtx) -> Result<Vec<Envelope>, Refusal> {
        match env.r#type.as_str() {
            NOTIFICATION_ACTION => {
                let e: NotificationActionEvent = parse(env)?;
                self.hub.action(&e);
                ctx.bus.emit("notification.action", json!({ "notificationId": e.notification_id, "actionId": e.action_id, "source": e.source }));
                Ok(vec![])
            }
            NOTIFICATION_DISMISS => {
                let id = serde_json::from_str::<serde_json::Value>(&env.data).ok().and_then(|v| v.get("id").and_then(|i| i.as_str()).map(str::to_string)).unwrap_or_else(|| env.data.trim().to_string());
                self.hub.dismissed(&id);
                Ok(vec![])
            }
            _ => Ok(vec![reply(env, NOTIFICATIONS, to_json(&self.hub.history()))]),
        }
    }
}
