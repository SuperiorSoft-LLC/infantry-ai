// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The Notifications settings card's data: the kinds senders declare, the wake policy resolved for a target, and the
//! per-kind counters (checks run vs turns spent). `notify_kinds` -> `notify_kinds_list`; `notify_kind_set` writes the
//! `notify.wake.kinds` list at a scope and answers the same list. The UI holds no list of kinds: it draws this answer.
use std::sync::Arc;

use infantry_core::frames::{Envelope, FrameCtx, FrameUnit, Refusal};
use infantry_mail::notify::wake::{KindRegistry, WakeGate, WakePolicy, DEFAULT_WAKE_KINDS, WAKE_KINDS_SETTING};
use infantry_protocol::frames as f;
use serde_json::{json, Value};

use super::settings::SettingsUnit;

/// The kinds the daemon's own senders raise (board comments and moves, worker failures, builds, owner messages, prompts).
pub fn declare_builtin(r: &KindRegistry) {
    r.declare(
        "board",
        &[
            ("comment.done", "A worker posts a done comment on a ticket"),
            ("comment.issue", "A worker posts an issue comment (it is blocked or found a problem)"),
            ("comment.note", "A worker posts a plain note comment"),
            ("comment.handoff", "A worker posts a handoff comment"),
            ("ticket.done.auto", "A ticket moved to done (the automatic one-line notice)"),
            ("worker.failed", "A worker failed, was killed or ended without a done comment"),
            ("worker.idle", "A worker went idle with its ticket still open"),
        ],
    );
    r.declare("builds", &[("build.failed", "A build failed"), ("build.success", "A green build")]);
    r.declare("app", &[("owner.message", "The owner sends a message")]);
    r.declare("prompts", &[("session.needs_input", "A session is waiting for an answer to a question or an approval")]);
}

pub struct NotifyKindsUnit {
    pub settings: SettingsUnit,
    pub gate: Arc<WakeGate>,
    pub kinds: Arc<KindRegistry>,
}

fn text(d: &Value, k: &str) -> Option<String> {
    d.get(k).and_then(Value::as_str).map(str::trim).filter(|s| !s.is_empty()).map(str::to_string)
}

impl NotifyKindsUnit {
    /// The answer for the target in `d` (project, role, session): the effective list, where it comes from, each kind with its counters.
    pub fn answer(&self, d: &Value) -> Value {
        let r = self.settings.resolve(WAKE_KINDS_SETTING, d);
        let raw = r["value"].as_str().map(str::to_string);
        let policy = WakePolicy::parse(raw.as_deref());
        let stats = self.gate.stats();
        let seen: Vec<String> = stats.keys().cloned().collect();
        let kinds: Vec<Value> = self
            .kinds
            .list(&seen)
            .into_iter()
            .map(|k| {
                let s = stats.get(&k.kind).copied().unwrap_or_default();
                json!({"kind": k.kind, "description": k.description, "source": k.source, "wake": policy.wakes(&k.kind),
                       "checks": s.checks, "wakes": s.wakes, "inbox": s.inbox, "duplicates": s.duplicates})
            })
            .collect();
        json!({"setting": WAKE_KINDS_SETTING, "value": raw, "effective": raw.clone().unwrap_or_else(|| DEFAULT_WAKE_KINDS.to_string()),
               "from": r["scope"], "layers": r["layers"], "kinds": kinds})
    }

    fn known(&self) -> Vec<String> {
        self.kinds.list(&self.gate.stats().keys().cloned().collect::<Vec<_>>()).into_iter().map(|k| k.kind).collect()
    }

    /// Turns [kind] on or off at the scope in `d`; the list it writes starts from what that target resolves to now.
    pub fn set_kind(&self, d: &Value) -> Result<(), String> {
        let kind = text(d, "kind").ok_or("a kind is required")?;
        let wake = d.get("wake").and_then(Value::as_bool).ok_or("wake (true or false) is required")?;
        let current = self.settings.resolve(WAKE_KINDS_SETTING, d)["value"].as_str().map(str::to_string);
        let next = WakePolicy::with_kind(current.as_deref().or(Some(DEFAULT_WAKE_KINDS)), &kind, wake, &self.known());
        self.settings.set(WAKE_KINDS_SETTING, Some(&json!(next)), d)
    }
}

impl FrameUnit for NotifyKindsUnit {
    fn name(&self) -> &'static str {
        "notify_kinds"
    }
    fn frames(&self) -> &'static [&'static str] {
        &[f::NOTIFY_KINDS, f::NOTIFY_KIND_SET]
    }
    fn handle(&self, env: &Envelope, _ctx: &FrameCtx) -> Result<Vec<Envelope>, Refusal> {
        let ty = env.r#type.as_str();
        let d: Value = serde_json::from_str(if env.data.trim().is_empty() { "{}" } else { &env.data }).map_err(|e| Refusal::new(ty, format!("bad payload ({e})")))?;
        if ty == f::NOTIFY_KIND_SET {
            self.set_kind(&d).map_err(|e| Refusal::new(ty, e))?;
        }
        let mut e = Envelope::new(f::NOTIFY_KINDS_LIST, self.answer(&d).to_string());
        e.request_id = env.request_id.clone();
        Ok(vec![e])
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use infantry_core::db::Db;

    fn unit() -> (NotifyKindsUnit, std::path::PathBuf) {
        let dir = std::env::temp_dir().join(format!("nk-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&dir).unwrap();
        let db = Arc::new(Db::open(dir.join("infantry.db")).unwrap());
        db.write(|c| c.execute_batch("CREATE TABLE IF NOT EXISTS settings (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL, updated_at INTEGER NOT NULL)")).unwrap();
        let kinds = Arc::new(KindRegistry::default());
        declare_builtin(&kinds);
        (NotifyKindsUnit { settings: SettingsUnit { db }, gate: Arc::new(WakeGate::new(50)), kinds }, dir)
    }

    fn wake_of(a: &Value, kind: &str) -> bool {
        a["kinds"].as_array().unwrap().iter().find(|k| k["kind"] == kind).unwrap()["wake"].as_bool().unwrap()
    }

    #[test]
    fn registry_is_served_with_the_default_policy_and_counters() {
        let (u, dir) = unit();
        let a = u.answer(&json!({}));
        assert_eq!(a["from"], "default");
        assert!(wake_of(&a, "comment.done") && !wake_of(&a, "build.success"));
        assert!(a["kinds"].as_array().unwrap().iter().all(|k| !k["description"].as_str().unwrap().is_empty()));
        u.gate.decide(&WakePolicy::parse(None), "s", "build.success", "r1");
        let b = u.answer(&json!({}));
        let row = b["kinds"].as_array().unwrap().iter().find(|k| k["kind"] == "build.success").unwrap().clone();
        assert_eq!((row["checks"].as_u64(), row["wakes"].as_u64(), row["inbox"].as_u64()), (Some(1), Some(0), Some(1)));
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn the_setting_round_trips_per_scope_and_the_source_is_reported() {
        let (u, dir) = unit();
        u.set_kind(&json!({"kind": "build.success", "wake": true})).unwrap();
        let app = u.answer(&json!({}));
        assert!(wake_of(&app, "build.success") && wake_of(&app, "comment.done"));
        assert_eq!(app["from"], "app");
        u.set_kind(&json!({"kind": "comment.done", "wake": false, "scope": "role", "project": "p", "role": "pm"})).unwrap();
        let pm = u.answer(&json!({"project": "p", "role": "pm"}));
        assert!(!wake_of(&pm, "comment.done") && wake_of(&pm, "build.success"));
        assert_eq!(pm["from"], "role");
        assert!(wake_of(&u.answer(&json!({"project": "p", "role": "dev"})), "comment.done"));
        assert!(u.set_kind(&json!({"kind": "x"})).is_err());
        let _ = std::fs::remove_dir_all(dir);
    }
}
