// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Owner questions: an agent asks the owner a multiple-choice question with a recommendation and carries on; the
//! owner's pick (or the auto-pick after `defaultAfterMin`) arrives as mail on thread `question:<id>`.
//! Stored in `owner_questions`; each change is broadcast as a `question` frame.
use std::sync::{Arc, Mutex, RwLock};

use infantry_core::db::{Db, DbError};
use infantry_protocol::prompts::{question_resolved_by as by, question_states as st, OwnerQuestion, PromptOption, QuestionAnswer, QuestionAsk};
use rusqlite::{params, OptionalExtension, Row};

/// A question of a gone session that had been auto-picked: the pick stands, no override can reach anyone.
pub const STALE: &str = "stale";
pub const OWNER: &str = "owner";
pub const DEFAULT_MAX_OPEN: usize = 3;
pub const DEFAULT_MAX_PER_HOUR: usize = 6;
const MINUTE_MS: i64 = 60_000;
const HOUR_MS: i64 = 3_600_000;

const SCHEMA: &str = "CREATE TABLE IF NOT EXISTS owner_questions (id TEXT NOT NULL PRIMARY KEY, project_id TEXT, session_id TEXT NOT NULL, agent_id TEXT, role TEXT, \
question TEXT NOT NULL, options TEXT NOT NULL, recommended TEXT NOT NULL, reason TEXT NOT NULL, allow_other INTEGER NOT NULL DEFAULT 0, \
default_after_min INTEGER, state TEXT NOT NULL CHECK (state IN ('open', 'answered', 'auto', 'cancelled', 'stale')), answer TEXT, other_text TEXT, \
resolved_by TEXT, created_at INTEGER NOT NULL, resolved_at INTEGER, overridden INTEGER NOT NULL DEFAULT 0)";
const COLS: &str = "id, project_id, session_id, agent_id, role, question, options, recommended, reason, allow_other, default_after_min, state, answer, other_text, resolved_by, created_at, resolved_at, overridden";

pub fn thread_of(question_id: &str) -> String {
    format!("question:{question_id}")
}

/// Who asks.
#[derive(Debug, Clone, PartialEq, Default)]
pub struct Asker {
    pub session_id: String,
    pub agent_id: Option<String>,
    pub role: Option<String>,
    pub project_id: Option<String>,
}

impl Asker {
    /// The rate limits count per agent (its sessions share them), else per session.
    pub fn address(&self) -> &str {
        self.agent_id.as_deref().unwrap_or(&self.session_id)
    }
}

/// What the questions service asks of the daemon.
pub trait QuestionHost: Send + Sync {
    /// Mails the asker; false = not delivered (the session is gone).
    fn notify(&self, session: &str, subject: &str, body: &str, thread: &str) -> bool;
    fn session_exists(&self, session: &str) -> bool;
    fn session_busy(&self, _session: &str) -> bool {
        false
    }
    /// A role's default `defaultAfterMin` when the ask gives none.
    fn default_after_min_for(&self, _session: &str) -> Option<i32> {
        None
    }
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct QuestionLimits {
    pub max_open: usize,
    pub max_per_hour: usize,
    /// 0 = no TTL.
    pub ttl_minutes: i64,
}

impl Default for QuestionLimits {
    fn default() -> Self {
        QuestionLimits { max_open: DEFAULT_MAX_OPEN, max_per_hour: DEFAULT_MAX_PER_HOUR, ttl_minutes: 0 }
    }
}

fn row(r: &Row) -> rusqlite::Result<OwnerQuestion> {
    let options: String = r.get(6)?;
    Ok(OwnerQuestion {
        id: r.get(0)?,
        project_id: r.get(1)?,
        session_id: r.get(2)?,
        agent_id: r.get(3)?,
        role: r.get(4)?,
        question: r.get(5)?,
        options: serde_json::from_str(&options).unwrap_or_default(),
        recommended: r.get(7)?,
        reason: r.get(8)?,
        allow_other: r.get::<_, i64>(9)? != 0,
        default_after_min: r.get(10)?,
        state: r.get(11)?,
        answer: r.get(12)?,
        other_text: r.get(13)?,
        resolved_by: r.get(14)?,
        created_at: r.get(15)?,
        resolved_at: r.get(16)?,
        overridden: r.get::<_, i64>(17)? != 0,
    })
}

type Sink = Arc<dyn Fn(&OwnerQuestion) + Send + Sync>;

pub struct OwnerQuestions {
    db: Arc<Db>,
    host: Arc<dyn QuestionHost>,
    limits: Arc<dyn Fn() -> QuestionLimits + Send + Sync>,
    clock: Arc<dyn Fn() -> i64 + Send + Sync>,
    sink: RwLock<Option<Sink>>,
    lock: Mutex<()>,
}

impl OwnerQuestions {
    pub fn new(db: Arc<Db>, host: Arc<dyn QuestionHost>, limits: Arc<dyn Fn() -> QuestionLimits + Send + Sync>, clock: Arc<dyn Fn() -> i64 + Send + Sync>) -> Result<Self, DbError> {
        db.write(|c| c.execute_batch(SCHEMA))?;
        Ok(OwnerQuestions { db, host, limits, clock, sink: RwLock::new(None), lock: Mutex::new(()) })
    }

    pub fn set_sink(&self, f: impl Fn(&OwnerQuestion) + Send + Sync + 'static) {
        *self.sink.write().unwrap() = Some(Arc::new(f));
    }

    /// Newest first.
    pub fn list(&self) -> Vec<OwnerQuestion> {
        self.db.read(|c| c.prepare(&format!("SELECT {COLS} FROM owner_questions ORDER BY created_at DESC, id"))?.query_map([], row)?.collect()).unwrap_or_default()
    }

    pub fn get(&self, id: &str) -> Option<OwnerQuestion> {
        self.db.read(|c| c.query_row(&format!("SELECT {COLS} FROM owner_questions WHERE id = ?1"), [id], row).optional()).ok().flatten()
    }

    fn save(&self, q: &OwnerQuestion, insert: bool) -> Result<(), String> {
        let opts = serde_json::to_string(&q.options).unwrap_or_else(|_| "[]".into());
        self.db
            .write(|c| {
                if insert {
                    c.execute(
                        &format!("INSERT INTO owner_questions({COLS}) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15, ?16, ?17, ?18)"),
                        params![
                            q.id, q.project_id, q.session_id, q.agent_id, q.role, q.question, opts, q.recommended, q.reason, q.allow_other as i64, q.default_after_min, q.state, q.answer,
                            q.other_text, q.resolved_by, q.created_at, q.resolved_at, q.overridden as i64
                        ],
                    )
                } else {
                    c.execute(
                        "UPDATE owner_questions SET state = ?1, answer = ?2, other_text = ?3, resolved_by = ?4, resolved_at = ?5, overridden = ?6 WHERE id = ?7",
                        params![q.state, q.answer, q.other_text, q.resolved_by, q.resolved_at, q.overridden as i64, q.id],
                    )
                }
            })
            .map(|_| ())
            .map_err(|e| e.to_string())
    }

    fn publish(&self, q: &OwnerQuestion) {
        if let Some(s) = self.sink.read().unwrap().clone() {
            s(q);
        }
    }

    fn mail(&self, q: &OwnerQuestion, (subject, body): (String, String)) {
        let thread = thread_of(&q.id);
        if !self.host.notify(&q.session_id, &subject, &format!("{body}\n(thread {thread})"), &thread) {
            eprintln!("[infantry] question {} mail not delivered (the asking session is gone)", q.id);
        }
    }

    pub fn ask(&self, asker: &Asker, request: &QuestionAsk) -> Result<OwnerQuestion, String> {
        let question = request.question.trim();
        if question.is_empty() {
            return Err("a question is required".into());
        }
        if question.chars().count() > QuestionAsk::QUESTION_MAX {
            return Err(format!("the question is longer than {} characters", QuestionAsk::QUESTION_MAX));
        }
        let options: Vec<PromptOption> = request.options.iter().map(|o| PromptOption { label: o.label.trim().into(), description: o.description.trim().into() }).collect();
        if options.len() < 2 {
            return Err("at least two options are required (a question with one answer is not a choice)".into());
        }
        if options.len() > QuestionAsk::OPTIONS_MAX {
            return Err(format!("at most {} options", QuestionAsk::OPTIONS_MAX));
        }
        if options.iter().any(|o| o.label.is_empty()) {
            return Err("an option label cannot be blank".into());
        }
        if let Some(dup) = options.iter().enumerate().find(|(i, o)| options[..*i].iter().any(|p| p.label == o.label)) {
            return Err(format!("option '{}' is given twice", dup.1.label.chars().take(80).collect::<String>()));
        }
        let recommended = request.recommended.trim();
        if recommended.is_empty() {
            return Err("exactly one recommended option is required (recommended = one option's label)".into());
        }
        if !options.iter().any(|o| o.label == recommended) {
            return Err(format!("recommended '{}' is not one of the options", recommended.chars().take(80).collect::<String>()));
        }
        let reason = request.reason.trim();
        if reason.is_empty() {
            return Err("a reason for the recommendation is required".into());
        }
        if reason.chars().count() > QuestionAsk::REASON_MAX {
            return Err(format!("the reason is longer than {} characters", QuestionAsk::REASON_MAX));
        }
        let range = QuestionAsk::DEFAULT_AFTER_MIN_RANGE;
        if let Some(m) = request.default_after_min {
            if !range.contains(&m) {
                return Err(format!("defaultAfterMin must be {}..{} minutes, got {m}", range.start(), range.end()));
            }
        }
        let created = {
            let _g = self.lock.lock().unwrap();
            let now = (self.clock)();
            let lim = (self.limits)();
            let mine: Vec<OwnerQuestion> = self.list().into_iter().filter(|q| q.agent_id.as_deref().unwrap_or(&q.session_id) == asker.address()).collect();
            let open = mine.iter().filter(|q| q.state == st::OPEN).count();
            if open >= lim.max_open {
                return Err(format!(
                    "rate limit: you already have {open} open questions for the owner (max {}). Do not wait: carry on with other work, and batch further choices into one question once an answer arrives",
                    lim.max_open
                ));
            }
            let last_hour = mine.iter().filter(|q| q.created_at > now - HOUR_MS).count();
            if last_hour >= lim.max_per_hour {
                return Err(format!("rate limit: you asked the owner {last_hour} questions in the last hour (max {}). Decide yourself where you can, and carry on", lim.max_per_hour));
            }
            let q = OwnerQuestion {
                id: uuid::Uuid::new_v4().simple().to_string()[..12].to_string(),
                project_id: asker.project_id.clone(),
                session_id: asker.session_id.clone(),
                agent_id: asker.agent_id.clone(),
                role: asker.role.clone(),
                question: question.into(),
                options,
                recommended: recommended.into(),
                reason: reason.into(),
                // The owner may always type an own answer.
                allow_other: true,
                default_after_min: request.default_after_min.or_else(|| self.host.default_after_min_for(&asker.session_id).map(|m| m.clamp(*range.start(), *range.end()))),
                state: st::OPEN.into(),
                answer: None,
                other_text: None,
                resolved_by: None,
                created_at: now,
                resolved_at: None,
                overridden: false,
            };
            self.save(&q, true)?;
            q
        };
        self.publish(&created);
        Ok(created)
    }

    fn known(&self, id: &str) -> Result<OwnerQuestion, String> {
        let id = id.trim();
        if id.is_empty() {
            return Err("questionId is required".into());
        }
        self.get(id).ok_or_else(|| format!("unknown question '{}'", id.chars().take(80).collect::<String>()))
    }

    /// The owner's pick (an option label) or own text.
    pub fn answer(&self, request: &QuestionAnswer) -> Result<OwnerQuestion, String> {
        let label = request.answer.as_deref().map(str::trim).filter(|s| !s.is_empty());
        let other = request.other_text.as_deref().map(str::trim).filter(|s| !s.is_empty());
        let (previous, updated) = {
            let _g = self.lock.lock().unwrap();
            let cur = self.known(&request.question_id)?;
            if cur.state != st::OPEN && cur.state != st::AUTO {
                return Err(format!("question '{}' is already {}", cur.id, cur.state));
            }
            if let Some(l) = label {
                if !cur.options.iter().any(|o| o.label == l) {
                    return Err(format!("unknown option '{}'", l.chars().take(80).collect::<String>()));
                }
            }
            if label.is_none() && other.is_none() {
                return Err("an answer needs 'answer' (an option label) or 'otherText'".into());
            }
            let upd = OwnerQuestion {
                state: st::ANSWERED.into(),
                answer: label.map(str::to_string),
                other_text: other.map(str::to_string),
                resolved_by: Some(by::OWNER.into()),
                resolved_at: Some((self.clock)()),
                overridden: cur.state == st::AUTO,
                ..cur.clone()
            };
            self.save(&upd, false)?;
            (cur, upd)
        };
        self.publish(&updated);
        self.mail(&updated, answer_mail(&updated, (previous.state == st::AUTO).then_some(&previous)));
        Ok(updated)
    }

    /// Withdraws an open question. `by_session` None = the owner (the asker is told); else it must be the asker.
    pub fn cancel(&self, question_id: &str, by_session: Option<&str>) -> Result<OwnerQuestion, String> {
        let updated = {
            let _g = self.lock.lock().unwrap();
            let cur = self.known(question_id)?;
            if by_session.is_some_and(|s| s != cur.session_id) {
                return Err(format!("only the owner or the asking session can cancel question '{}'", cur.id));
            }
            if cur.state != st::OPEN {
                return Err(format!("question '{}' is already {}", cur.id, cur.state));
            }
            let upd = OwnerQuestion {
                state: st::CANCELLED.into(),
                resolved_by: Some(if by_session.is_none() { by::OWNER } else { by::ASKER }.into()),
                resolved_at: Some((self.clock)()),
                ..cur
            };
            self.save(&upd, false)?;
            upd
        };
        self.publish(&updated);
        if by_session.is_none() {
            self.mail(&updated, cancel_mail(&updated));
        }
        Ok(updated)
    }

    /// A session ended: its open questions are cancelled as orphaned (no mail: nobody is there).
    pub fn orphan_session(&self, session: &str) -> Vec<OwnerQuestion> {
        self.orphan((self.clock)(), false, |q| q.session_id == session)
    }

    fn orphan(&self, now: i64, include_auto: bool, matches: impl Fn(&OwnerQuestion) -> bool) -> Vec<OwnerQuestion> {
        let closed: Vec<OwnerQuestion> = {
            let _g = self.lock.lock().unwrap();
            self.list()
                .into_iter()
                .filter(|q| (q.state == st::OPEN || (include_auto && q.state == st::AUTO)) && matches(q))
                .map(|q| {
                    let upd = if q.state == st::AUTO {
                        OwnerQuestion { state: STALE.into(), resolved_at: Some(now), ..q }
                    } else {
                        OwnerQuestion { state: st::CANCELLED.into(), resolved_by: Some(by::ORPHANED.into()), resolved_at: Some(now), ..q }
                    };
                    let _ = self.save(&upd, false);
                    upd
                })
                .collect()
        };
        closed.iter().for_each(|q| self.publish(q));
        closed
    }

    /// Orphans questions of gone sessions (and past the TTL with an idle asker), then auto-picks every open
    /// question whose `defaultAfterMin` has run out. Returns the picked ones.
    pub fn sweep(&self) -> Vec<OwnerQuestion> {
        let now = (self.clock)();
        let ttl = (self.limits)().ttl_minutes;
        let ttl_ms = if ttl <= 0 { i64::MAX } else { ttl * MINUTE_MS };
        self.orphan(now, true, |q| !self.host.session_exists(&q.session_id) || (q.state == st::OPEN && now - q.created_at >= ttl_ms && !self.host.session_busy(&q.session_id)));
        let picked: Vec<OwnerQuestion> = {
            let _g = self.lock.lock().unwrap();
            self.list()
                .into_iter()
                .filter(|q| q.state == st::OPEN && q.default_after_min.is_some_and(|m| now >= q.created_at + m as i64 * MINUTE_MS))
                .map(|q| {
                    let upd = OwnerQuestion { state: st::AUTO.into(), answer: Some(q.recommended.clone()), resolved_by: Some(by::AUTO.into()), resolved_at: Some(now), ..q };
                    let _ = self.save(&upd, false);
                    upd
                })
                .collect()
        };
        for q in &picked {
            self.publish(q);
            self.mail(q, answer_mail(q, None));
        }
        picked
    }
}

/// Subject and body of the mail that tells the asker the pick; `replaced_auto` = the auto-pick an override replaces.
pub fn answer_mail(q: &OwnerQuestion, replaced_auto: Option<&OwnerQuestion>) -> (String, String) {
    let custom = q.answer.is_none() && q.other_text.is_some();
    let choice = q.answer.clone().or_else(|| q.other_text.clone()).unwrap_or_default();
    let subject = format!("{} to question {}: {}", if custom { "Custom answer" } else { "Answer" }, q.id, choice.chars().take(80).collect::<String>());
    let mut body = format!("Question {}: {}\n", q.id, q.question);
    if let Some(label) = &q.answer {
        let d = q.options.iter().find(|o| &o.label == label).map(|o| o.description.clone()).unwrap_or_default();
        body.push_str(&format!("Chosen option: {label}{}\n", if d.is_empty() { String::new() } else { format!(" ({d})") }));
    }
    if let Some(t) = &q.other_text {
        body.push_str(&if custom { format!("Custom answer from the owner: {t}\n") } else { format!("Owner's answer: {t}\n") });
    }
    if custom {
        body.push_str("This is the owner's OWN text, not one of your options; do not read it as a chosen option.\n");
    }
    if q.state == st::AUTO {
        body.push_str(&format!(
            "auto-selected after {} min, owner did not answer (your recommended option). The owner may still override it; an override arrives as another mail in this thread.\n",
            q.default_after_min.unwrap_or(0)
        ));
    } else if let Some(prev) = replaced_auto {
        body.push_str(&format!("OVERRIDE: the owner replaced the earlier auto-selection '{}'. Follow this answer instead.\n", prev.answer.clone().unwrap_or_default()));
    } else {
        body.push_str("Chosen by the owner.\n");
    }
    body.push_str("Carry on with this choice; no reply is needed.");
    (subject, body)
}

pub fn cancel_mail(q: &OwnerQuestion) -> (String, String) {
    (
        format!("Question {} cancelled by the owner", q.id),
        format!(
            "Question {}: {}\nThe owner cancelled it: no answer is coming. Decide with your own judgment (your recommendation was '{}') and carry on.",
            q.id, q.question, q.recommended
        ),
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicI64, Ordering};

    #[derive(Default)]
    struct Host(Mutex<Vec<(String, String)>>);
    impl QuestionHost for Host {
        fn notify(&self, s: &str, subject: &str, _b: &str, _t: &str) -> bool {
            self.0.lock().unwrap().push((s.into(), subject.into()));
            true
        }
        fn session_exists(&self, _s: &str) -> bool {
            true
        }
    }

    fn ask(rec: &str) -> QuestionAsk {
        QuestionAsk {
            question: "Which db?".into(),
            options: vec![PromptOption { label: "sqlite".into(), description: "embedded".into() }, PromptOption { label: "pg".into(), description: String::new() }],
            recommended: rec.into(),
            reason: "simplest".into(),
            allow_other: true,
            default_after_min: Some(5),
        }
    }

    #[test]
    fn ask_validate_auto_pick_and_override() {
        let dir = tempfile::tempdir().unwrap();
        let db = Arc::new(Db::open(dir.path().join("q.db")).unwrap());
        let host = Arc::new(Host::default());
        let t = Arc::new(AtomicI64::new(0));
        let tc = t.clone();
        let qs = OwnerQuestions::new(db, host.clone(), Arc::new(QuestionLimits::default), Arc::new(move || tc.load(Ordering::SeqCst))).unwrap();
        let asker = Asker { session_id: "s1".into(), ..Default::default() };
        assert_eq!(qs.ask(&asker, &ask("mysql")).unwrap_err(), "recommended 'mysql' is not one of the options");
        let q = qs.ask(&asker, &ask("sqlite")).unwrap();
        t.store(5 * MINUTE_MS, Ordering::SeqCst);
        let picked = qs.sweep();
        assert_eq!(picked[0].state, st::AUTO);
        let a = qs.answer(&QuestionAnswer { question_id: q.id.clone(), answer: Some("pg".into()), other_text: None }).unwrap();
        assert!(a.overridden);
        let mails = host.0.lock().unwrap();
        assert_eq!(mails.len(), 2);
        assert_eq!(mails[1].1, format!("Answer to question {}: pg", q.id));
    }
}
