// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Pending prompts: a tool approval or a question an agent is blocked on, shown to the owner as a card
//! (`prompt_request`) and closed by the owner's `prompt_response` (or the timer, the session's end, the daemon's
//! stop) with `prompt_resolved`. The asker awaits a tokio oneshot; nothing here blocks a thread.
//!
//! `allow_session` grants the tool for the rest of the session; `allow_always` stores a rule of the session's
//! project ([ApprovalRulesHook]) so the next matching call is answered with no card, just a transcript notice.
use std::collections::{HashMap, HashSet, VecDeque};
use std::sync::{Arc, Mutex, RwLock};
use std::time::Duration;

use infantry_protocol::frames as f;
use infantry_protocol::prompts::{
    prompt_decisions as dec, prompt_kinds as kinds, prompt_outcomes as outcomes, prompt_resolved_by as by, ApprovalRule, PromptAsk, PromptOption, PromptRequest, PromptResolved,
    PromptResponse,
};
use tokio::sync::oneshot;

use super::approvals::ApprovalRules;

pub const DEFAULT_TIMEOUT_MS: i64 = 600_000;
pub const INPUT_SUMMARY_MAX: usize = 200;
const RESOLVED_MEMORY: usize = 1024;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Decision {
    AllowOnce,
    AllowSession,
    AllowAlways,
    Deny,
}

impl Decision {
    pub fn parse(text: Option<&str>) -> Option<Self> {
        match text?.trim() {
            dec::ALLOW_ONCE => Some(Self::AllowOnce),
            dec::ALLOW_SESSION => Some(Self::AllowSession),
            dec::ALLOW_ALWAYS => Some(Self::AllowAlways),
            dec::DENY => Some(Self::Deny),
            _ => None,
        }
    }
    pub fn wire(self) -> &'static str {
        match self {
            Self::AllowOnce => dec::ALLOW_ONCE,
            Self::AllowSession => dec::ALLOW_SESSION,
            Self::AllowAlways => dec::ALLOW_ALWAYS,
            Self::Deny => dec::DENY,
        }
    }
    pub fn allows(self) -> bool {
        self != Self::Deny
    }
}

#[derive(Debug, Clone, PartialEq, Default)]
pub struct Answer {
    pub answers: Vec<String>,
    pub other_text: Option<String>,
    pub answered: bool,
}

/// A validated question.
#[derive(Debug, Clone, PartialEq)]
pub struct QuestionSpec {
    pub question: String,
    pub options: Vec<PromptOption>,
    pub multi_select: bool,
    pub allow_other: bool,
    pub timeout_ms: Option<i64>,
    pub recommended: Option<String>,
}

impl QuestionSpec {
    pub fn of(ask: &PromptAsk) -> Result<Self, String> {
        let question = ask.question.trim();
        if question.is_empty() {
            return Err("a question is required".into());
        }
        if question.chars().count() > 2_000 {
            return Err("the question is longer than 2000 characters".into());
        }
        if ask.options.is_empty() {
            return Err("at least one option is required".into());
        }
        let options: Vec<PromptOption> = ask.options.iter().map(|o| PromptOption { label: o.label.trim().into(), description: o.description.trim().into() }).collect();
        if options.iter().any(|o| o.label.is_empty()) {
            return Err("an option label cannot be blank".into());
        }
        if let Some((_, o)) = options.iter().enumerate().find(|(i, o)| options[..*i].iter().any(|p| p.label == o.label)) {
            return Err(format!("option '{}' is given twice", o.label.chars().take(80).collect::<String>()));
        }
        if let Some(t) = ask.timeout_sec {
            let r = PromptAsk::TIMEOUT_SEC_RANGE;
            if !r.contains(&t) {
                return Err(format!("timeoutSec must be {}..{}, got {t}", r.start(), r.end()));
            }
        }
        let recommended = ask.recommended.as_deref().map(str::trim).filter(|s| !s.is_empty()).map(str::to_string);
        if let Some(rec) = &recommended {
            if !options.iter().any(|o| &o.label == rec) {
                return Err(format!("recommended '{}' is not one of the options", rec.chars().take(80).collect::<String>()));
            }
        }
        Ok(QuestionSpec { question: question.into(), options, multi_select: ask.multi_select, allow_other: ask.allow_other, timeout_ms: ask.timeout_sec.map(|s| s as i64 * 1000), recommended })
    }
}

/// The timer of a session's prompts (`prompts.timeoutSec`, `prompts.onTimeout`). A timeout of 0 = no timer.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct TimeoutPolicy {
    pub timeout_ms: i64,
    pub on_timeout: Decision,
}

impl Default for TimeoutPolicy {
    fn default() -> Self {
        TimeoutPolicy { timeout_ms: DEFAULT_TIMEOUT_MS, on_timeout: Decision::Deny }
    }
}

/// The remembered "Always allow" rules.
pub trait ApprovalRulesHook: Send + Sync {
    fn matches(&self, project: Option<&str>, tool: &str, input: &str) -> Option<ApprovalRule>;
    /// True = a rule is stored.
    fn remember(&self, project: Option<&str>, tool: &str, input: &str) -> bool;
}

impl ApprovalRulesHook for ApprovalRules {
    fn matches(&self, project: Option<&str>, tool: &str, input: &str) -> Option<ApprovalRule> {
        ApprovalRules::matches(self, project, tool, input)
    }
    fn remember(&self, project: Option<&str>, tool: &str, input: &str) -> bool {
        self.add(project, tool, input).is_some()
    }
}

#[derive(Debug, Clone, PartialEq)]
enum Resolution {
    Approval(Decision),
    Question(Answer),
}

struct Open {
    request: PromptRequest,
    full_input: Option<String>,
    tx: Option<oneshot::Sender<(Resolution, String)>>,
}

#[derive(Default)]
struct State {
    open: HashMap<String, Open>,
    order: Vec<String>,
    resolved: VecDeque<String>,
    session_allowed: HashMap<String, HashSet<String>>,
}

type Sink = Arc<dyn Fn(&str, String, &str) + Send + Sync>;
type Notice = Arc<dyn Fn(&str, &str) + Send + Sync>;

/// Who: the outcome of an approval request.
#[derive(Debug, Clone, PartialEq)]
pub struct ApprovalOutcome {
    pub decision: Decision,
    pub timed_out: bool,
}

pub struct PendingPrompts {
    rules: Option<Arc<dyn ApprovalRulesHook>>,
    project_of: Arc<dyn Fn(&str) -> Option<String> + Send + Sync>,
    policy_for: Arc<dyn Fn(&str) -> TimeoutPolicy + Send + Sync>,
    clock: Arc<dyn Fn() -> i64 + Send + Sync>,
    /// `(frame type, json, session)` for `prompt_request` / `prompt_resolved`.
    sink: RwLock<Option<Sink>>,
    /// One transcript line for a session.
    notice: RwLock<Option<Notice>>,
    state: Mutex<State>,
}

/// One line, at most [INPUT_SUMMARY_MAX] characters.
pub fn summarize(input: &str) -> String {
    let one: String = input.split_whitespace().collect::<Vec<_>>().join(" ");
    if one.chars().count() <= INPUT_SUMMARY_MAX {
        one
    } else {
        one.chars().take(INPUT_SUMMARY_MAX - 1).collect::<String>() + "…"
    }
}

impl PendingPrompts {
    pub fn new(
        rules: Option<Arc<dyn ApprovalRulesHook>>,
        project_of: Arc<dyn Fn(&str) -> Option<String> + Send + Sync>,
        policy_for: Arc<dyn Fn(&str) -> TimeoutPolicy + Send + Sync>,
        clock: Arc<dyn Fn() -> i64 + Send + Sync>,
    ) -> Self {
        PendingPrompts { rules, project_of, policy_for, clock, sink: RwLock::new(None), notice: RwLock::new(None), state: Mutex::new(State::default()) }
    }

    pub fn set_sink(&self, f: impl Fn(&str, String, &str) + Send + Sync + 'static) {
        *self.sink.write().unwrap() = Some(Arc::new(f));
    }
    pub fn set_notice(&self, f: impl Fn(&str, &str) + Send + Sync + 'static) {
        *self.notice.write().unwrap() = Some(Arc::new(f));
    }

    fn emit(&self, frame: &str, data: String, session: &str) {
        if let Some(s) = self.sink.read().unwrap().clone() {
            s(frame, data, session);
        }
    }
    fn note(&self, session: &str, text: &str) {
        if let Some(n) = self.notice.read().unwrap().clone() {
            n(session, text);
        }
    }

    pub fn is_allowed_for_session(&self, session: &str, tool: &str) -> bool {
        self.state.lock().unwrap().session_allowed.get(session).is_some_and(|s| s.contains(tool))
    }

    /// The open prompts, oldest first (a client that connects is shown these).
    pub fn snapshot(&self) -> Vec<PromptRequest> {
        let s = self.state.lock().unwrap();
        s.order.iter().filter_map(|id| s.open.get(id)).map(|o| o.request.clone()).collect()
    }

    pub async fn request_approval(&self, session: &str, tool: &str, input: &str, recommended: Option<Decision>) -> ApprovalOutcome {
        if self.is_allowed_for_session(session, tool) {
            return ApprovalOutcome { decision: Decision::AllowSession, timed_out: false };
        }
        let project = (self.project_of)(session);
        if let Some(rule) = self.rules.as_ref().and_then(|r| r.matches(project.as_deref(), tool, input)) {
            self.note(session, &format!("auto-allowed by rule {}{}", rule.tool, rule.prefix.map(|p| format!(" {p}")).unwrap_or_default()));
            return ApprovalOutcome { decision: Decision::AllowAlways, timed_out: false };
        }
        let policy = (self.policy_for)(session);
        // The timer never stores a rule or a grant: a recommended allow is auto-selected as allow once.
        let auto = recommended.map(|d| if d.allows() { Decision::AllowOnce } else { Decision::Deny });
        let now = (self.clock)();
        let request = PromptRequest {
            prompt_id: uuid::Uuid::new_v4().to_string(),
            session_id: session.into(),
            kind: kinds::TOOL_APPROVAL.into(),
            created_at: now,
            tool: Some(tool.into()),
            input: Some(summarize(input)),
            question: None,
            options: vec![],
            multi_select: false,
            allow_other: false,
            expires_at: (policy.timeout_ms > 0).then_some(now + policy.timeout_ms),
            on_timeout: Some(auto.unwrap_or(policy.on_timeout).wire().into()),
            recommended: auto.map(|d| d.wire().into()),
        };
        let (res, resolved_by) = self.wait(request, Some(input.to_string()), policy).await;
        let decision = match res {
            Resolution::Approval(d) => d,
            _ => Decision::Deny,
        };
        ApprovalOutcome { decision, timed_out: resolved_by == by::TIMEOUT }
    }

    pub async fn ask_question(&self, session: &str, spec: &QuestionSpec) -> Answer {
        let global = (self.policy_for)(session);
        let policy = TimeoutPolicy { timeout_ms: spec.timeout_ms.unwrap_or(global.timeout_ms), ..global };
        let now = (self.clock)();
        let request = PromptRequest {
            prompt_id: uuid::Uuid::new_v4().to_string(),
            session_id: session.into(),
            kind: kinds::QUESTION.into(),
            created_at: now,
            tool: None,
            input: None,
            question: Some(spec.question.clone()),
            options: spec.options.clone(),
            multi_select: spec.multi_select,
            allow_other: spec.allow_other,
            expires_at: (policy.timeout_ms > 0).then_some(now + policy.timeout_ms),
            on_timeout: None,
            recommended: spec.recommended.clone(),
        };
        match self.wait(request, None, policy).await.0 {
            Resolution::Question(a) => a,
            _ => Answer::default(),
        }
    }

    async fn wait(&self, request: PromptRequest, full_input: Option<String>, policy: TimeoutPolicy) -> (Resolution, String) {
        let (tx, rx) = oneshot::channel();
        let id = request.prompt_id.clone();
        let session = request.session_id.clone();
        {
            let mut s = self.state.lock().unwrap();
            s.order.push(id.clone());
            s.open.insert(id.clone(), Open { request: request.clone(), full_input, tx: Some(tx) });
        }
        self.emit(f::PROMPT_REQUEST, serde_json::to_string(&request).unwrap_or_default(), &session);
        // The asker went away (its future dropped): close the card everywhere.
        let guard = CancelGuard { prompts: self, id: id.clone(), armed: true };
        let got = if policy.timeout_ms <= 0 { rx.await.ok() } else { tokio::time::timeout(Duration::from_millis(policy.timeout_ms as u64), rx).await.ok().and_then(Result::ok) };
        let mut guard = guard;
        guard.armed = false;
        if let Some(r) = got {
            return r;
        }
        let expiry = on_expiry(&request, &policy);
        if self.resolve(&id, by::TIMEOUT, expiry.clone()).is_some() {
            self.note(&session, &expiry_notice(&request, &policy));
        }
        (expiry, by::TIMEOUT.into())
    }

    /// The owner's (or a tool's) answer. `resolved_by` is who answered (a client id, `owner`...).
    pub fn respond(&self, response: &PromptResponse, resolved_by: &str) -> Result<PromptResolved, String> {
        let id = response.prompt_id.trim();
        if id.is_empty() {
            return Err("promptId is required".into());
        }
        let request = {
            let s = self.state.lock().unwrap();
            match s.open.get(id) {
                Some(o) => o.request.clone(),
                None if s.resolved.iter().any(|r| r == id) => return Err(format!("prompt '{}' is already resolved", id.chars().take(80).collect::<String>())),
                None => return Err(format!("unknown prompt '{}'", id.chars().take(80).collect::<String>())),
            }
        };
        let resolution = validate(&request, response)?;
        self.resolve(id, resolved_by, resolution).ok_or_else(|| format!("prompt '{}' is already resolved", id.chars().take(80).collect::<String>()))
    }

    /// The session ended: its prompts close with `session_end` and its grants go.
    pub fn end_session(&self, session: &str) {
        self.resolve_where(|o| o.request.session_id == session, by::SESSION_END);
        self.state.lock().unwrap().session_allowed.remove(session);
    }

    pub fn close_all(&self, resolved_by: &str) {
        self.resolve_where(|_| true, resolved_by);
    }

    fn resolve_where(&self, pred: impl Fn(&Open) -> bool, resolved_by: &str) {
        let ids: Vec<(String, PromptRequest)> = self.state.lock().unwrap().open.values().filter(|o| pred(o)).map(|o| (o.request.prompt_id.clone(), o.request.clone())).collect();
        for (id, req) in ids {
            self.resolve(&id, resolved_by, no_answer(&req));
        }
    }

    fn resolve(&self, id: &str, resolved_by: &str, resolution: Resolution) -> Option<PromptResolved> {
        let mut entry = {
            let mut s = self.state.lock().unwrap();
            let e = s.open.remove(id)?;
            s.order.retain(|x| x != id);
            s.resolved.push_back(id.to_string());
            while s.resolved.len() > RESOLVED_MEMORY {
                s.resolved.pop_front();
            }
            if let (Resolution::Approval(Decision::AllowSession), Some(tool)) = (&resolution, &e.request.tool) {
                s.session_allowed.entry(e.request.session_id.clone()).or_default().insert(tool.clone());
            }
            e
        };
        // Remember the rule before the asker resumes, so its very next call already matches.
        if let (Resolution::Approval(Decision::AllowAlways), Some(tool)) = (&resolution, entry.request.tool.clone()) {
            let project = (self.project_of)(&entry.request.session_id);
            let input = entry.full_input.clone().or(entry.request.input.clone()).unwrap_or_default();
            let stored = self.rules.as_ref().is_some_and(|r| r.remember(project.as_deref(), &tool, &input));
            if !stored {
                self.state.lock().unwrap().session_allowed.entry(entry.request.session_id.clone()).or_default().insert(tool);
            }
        }
        let frame = PromptResolved { prompt_id: id.into(), resolved_by: resolved_by.into(), outcome: outcome_of(&resolution).into() };
        if let Some(tx) = entry.tx.take() {
            let _ = tx.send((resolution, resolved_by.to_string()));
        }
        self.emit(f::PROMPT_RESOLVED, serde_json::to_string(&frame).unwrap_or_default(), &entry.request.session_id);
        Some(frame)
    }
}

struct CancelGuard<'a> {
    prompts: &'a PendingPrompts,
    id: String,
    armed: bool,
}

impl Drop for CancelGuard<'_> {
    fn drop(&mut self) {
        if self.armed {
            let req = self.prompts.state.lock().unwrap().open.get(&self.id).map(|o| o.request.clone());
            if let Some(r) = req {
                self.prompts.resolve(&self.id, by::CANCELLED, no_answer(&r));
            }
        }
    }
}

fn validate(request: &PromptRequest, response: &PromptResponse) -> Result<Resolution, String> {
    if request.kind == kinds::TOOL_APPROVAL {
        if !response.answers.is_empty() || response.other_text.is_some() {
            return Err("a tool_approval answer takes only 'decision'".into());
        }
        return Decision::parse(response.decision.as_deref()).map(Resolution::Approval).ok_or_else(|| {
            format!("unknown decision '{}' (known: {})", response.decision.clone().unwrap_or_default().chars().take(40).collect::<String>(), dec::ALL.join(", "))
        });
    }
    if response.decision.is_some() {
        return Err("a question answer takes 'answers' / 'otherText', not 'decision'".into());
    }
    if let Some(u) = response.answers.iter().find(|a| !request.options.iter().any(|o| &o.label == *a)) {
        return Err(format!("unknown option '{}'", u.chars().take(80).collect::<String>()));
    }
    let uniq: HashSet<&String> = response.answers.iter().collect();
    if uniq.len() != response.answers.len() {
        return Err("an option is answered twice".into());
    }
    if !request.multi_select && response.answers.len() > 1 {
        return Err("this question takes one answer".into());
    }
    let other = response.other_text.as_deref().map(str::trim).filter(|s| !s.is_empty()).map(str::to_string);
    if response.answers.is_empty() && other.is_none() {
        return Err("an answer needs 'answers' or 'otherText'".into());
    }
    Ok(Resolution::Question(Answer { answers: response.answers.clone(), other_text: other, answered: true }))
}

fn on_expiry(request: &PromptRequest, policy: &TimeoutPolicy) -> Resolution {
    if request.kind == kinds::TOOL_APPROVAL {
        Resolution::Approval(Decision::parse(request.recommended.as_deref()).unwrap_or(policy.on_timeout))
    } else if let Some(rec) = &request.recommended {
        Resolution::Question(Answer { answers: vec![rec.clone()], other_text: None, answered: true })
    } else {
        Resolution::Question(Answer::default())
    }
}

fn no_answer(request: &PromptRequest) -> Resolution {
    if request.kind == kinds::TOOL_APPROVAL {
        Resolution::Approval(Decision::Deny)
    } else {
        Resolution::Question(Answer::default())
    }
}

fn outcome_of(r: &Resolution) -> &'static str {
    match r {
        Resolution::Approval(d) => d.wire(),
        Resolution::Question(a) if a.answered => outcomes::ANSWERED,
        Resolution::Question(_) => outcomes::NO_ANSWER,
    }
}

/// The transcript line an expired prompt leaves.
pub fn expiry_notice(request: &PromptRequest, policy: &TimeoutPolicy) -> String {
    let after = format!("timed out after {}s with no answer", policy.timeout_ms / 1000);
    let tool = request.tool.clone().unwrap_or_else(|| "a tool".into());
    let q: String = request.question.clone().unwrap_or_default().chars().take(80).collect();
    if let Some(rec) = &request.recommended {
        let what = if request.kind == kinds::TOOL_APPROVAL { format!("tool approval for {tool}") } else { format!("question \"{q}\"") };
        let label = if request.kind != kinds::TOOL_APPROVAL {
            rec.clone()
        } else if Decision::parse(Some(rec)).is_some_and(Decision::allows) {
            "Allow".into()
        } else {
            "Deny".into()
        };
        return format!("auto-selected recommended: {label} ({what} {after})");
    }
    if request.kind == kinds::TOOL_APPROVAL {
        let action = if policy.on_timeout.allows() { "auto-approved (allow once)" } else { "auto-denied" };
        let wire = if policy.on_timeout.allows() { "approve" } else { "deny" };
        format!("tool approval for {tool} {after}: {action} (prompts.onTimeout = {wire})")
    } else {
        format!("question \"{q}\" {after}: resolved as no answer")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn prompts(timeout_ms: i64) -> Arc<PendingPrompts> {
        Arc::new(PendingPrompts::new(None, Arc::new(|_| None), Arc::new(move |_| TimeoutPolicy { timeout_ms, on_timeout: Decision::Deny }), Arc::new(|| 0)))
    }

    #[tokio::test]
    async fn approval_answered_session_grant_and_timeout() {
        let p = prompts(5_000);
        let frames = Arc::new(Mutex::new(Vec::new()));
        let fr = frames.clone();
        let p2 = p.clone();
        p.set_sink(move |t, d, _| {
            fr.lock().unwrap().push(t.to_string());
            if t == f::PROMPT_REQUEST {
                let req: PromptRequest = serde_json::from_str(&d).unwrap();
                let p3 = p2.clone();
                tokio::spawn(async move {
                    let resp = |d: &str| PromptResponse { prompt_id: req.prompt_id.clone(), decision: Some(d.into()), answers: vec![], other_text: None };
                    assert!(p3.respond(&resp("yes"), "owner").unwrap_err().starts_with("unknown decision 'yes'"));
                    p3.respond(&resp("allow_session"), "owner").unwrap();
                    assert!(p3.respond(&resp("deny"), "owner").unwrap_err().ends_with("is already resolved"));
                });
            }
        });
        let out = p.request_approval("s", "Bash", "ls   -la", None).await;
        assert_eq!(out.decision, Decision::AllowSession);
        assert!(p.is_allowed_for_session("s", "Bash"));
        assert_eq!(p.request_approval("s", "Bash", "rm x", None).await.decision, Decision::AllowSession);
        assert_eq!(frames.lock().unwrap().iter().filter(|t| *t == f::PROMPT_RESOLVED).count(), 1);
        // No answer: the timer denies.
        let q = prompts(30);
        let a = q.request_approval("s", "Write", "x", None).await;
        assert!(a.timed_out && a.decision == Decision::Deny);
        assert!(q.respond(&PromptResponse { prompt_id: "nope".into(), decision: None, answers: vec![], other_text: None }, "o").unwrap_err().starts_with("unknown prompt"));
    }

    #[test]
    fn question_spec_validation() {
        let mut ask = PromptAsk { question: "Q".into(), options: vec![], multi_select: false, allow_other: false, timeout_sec: None, recommended: None };
        assert_eq!(QuestionSpec::of(&ask).unwrap_err(), "at least one option is required");
        ask.options = vec![PromptOption { label: "a".into(), description: String::new() }];
        ask.timeout_sec = Some(0);
        assert_eq!(QuestionSpec::of(&ask).unwrap_err(), "timeoutSec must be 1..86400, got 0");
        assert_eq!(summarize("a\n  b"), "a b");
    }
}
