// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! Infantry_Chat, daemon half: the COMMS mail tools `infantry_inbox`, `infantry_read`, `infantry_message`,
//! `infantry_announce` and `infantry_projects`. Each is the same frame the CLI sends (`mail_inbox`, `mail_read`,
//! `mail_send`, `sessions`, `project_list`) on the caller's connection, so the daemon stays the single owner of the
//! mailbox and every policy gate still applies. WHO I AM is `client_session_id`, else the human handle `user`.
//!
//! The `image_upload` / `image_fetch` frames ([`images`]) go through the host's `project_files` frame. The chat image retention
//! sweep ([`retention`]) runs on the daemon's `clock.tick`. `infantry_chat_note` / `infantry_chat_image` are served by `infantry-scripting` (`chat.rs`), next to the timeline they post to.
pub mod images;
pub mod retention;

use infantry_plugin_sdk::types::{CallContext, Caller, Frame};
use infantry_plugin_sdk::{export_plugin, host, Plugin};
use serde_json::{json, Map, Value};

const HUMAN: &str = "user";
const TIMEOUT_MS: u32 = 30_000;
const DEFAULT_ITEMS: usize = 20;
const PREVIEW: usize = 120;
const SUBJECT: usize = 80;

pub struct Chat;

pub type Ask<'a> = &'a dyn Fn(&str, &str, Option<&str>) -> Result<Frame, String>;

fn text<'a>(o: &'a Value, k: &str) -> &'a str {
    o.get(k).and_then(|v| v.as_str()).unwrap_or("")
}

fn arg(a: &Value, k: &str) -> Option<String> {
    a.get(k).and_then(|v| v.as_str()).map(str::trim).filter(|s| !s.is_empty()).map(String::from)
}

fn first_line(body: &str, n: usize) -> String {
    body.lines().find(|l| !l.trim().is_empty()).map(|l| l.trim().chars().take(n).collect()).unwrap_or_default()
}

#[derive(Clone)]
struct Mail(Value);

impl Mail {
    fn state(&self) -> &str {
        self.0.get("state").and_then(|v| v.as_str()).unwrap_or("QUEUED")
    }
    fn has(&self, k: &str) -> bool {
        self.0.get(k).map(|v| !v.is_null()).unwrap_or(false)
    }
    fn read(&self) -> bool {
        self.has("readAt") || matches!(self.state(), "READ" | "ANSWERED")
    }
    fn delivered(&self) -> bool {
        self.has("deliveredAt") || matches!(self.state(), "DELIVERED" | "READ" | "ANSWERED")
    }
    fn failed(&self) -> bool {
        self.state() == "UNDELIVERABLE"
    }
    fn sent_at(&self) -> i64 {
        self.0.get("sentAt").and_then(|v| v.as_i64()).unwrap_or(0)
    }
    fn row(&self) -> Map<String, Value> {
        let o = &self.0;
        let mut m = Map::new();
        for k in ["id", "threadId", "from", "to", "subject"] {
            m.insert(k.into(), text(o, k).into());
        }
        m.insert("sentAt".into(), self.sent_at().into());
        m.insert("state".into(), self.state().into());
        m.insert("read".into(), self.read().into());
        m.insert("preview".into(), first_line(text(o, "body"), PREVIEW).into());
        m
    }
}

fn who(session: Option<&str>) -> String {
    session.map(String::from).unwrap_or_else(|| HUMAN.into())
}

fn fetch_inbox(session: Option<&str>, ask: Ask) -> Result<Vec<Mail>, String> {
    let r = ask("mail_inbox", &who(session), session)?;
    if r.type_ == "error" {
        return Err(if r.data.trim().is_empty() { "mail_inbox failed".into() } else { r.data });
    }
    let v: Value = serde_json::from_str(&r.data).map_err(|e| format!("mail_inbox: {e}"))?;
    Ok(v.as_array().cloned().unwrap_or_default().into_iter().map(Mail).collect())
}

pub fn inbox(a: &Value, session: Option<&str>, default_limit: Option<usize>, ask: Ask) -> Result<String, String> {
    let unread_only = a.get("unreadOnly").and_then(|v| v.as_bool()) == Some(true);
    let previews = a.get("previews").and_then(|v| v.as_bool()) == Some(true);
    let all = fetch_inbox(session, ask)?;
    let mut matching: Vec<&Mail> = all.iter().filter(|m| !unread_only || !m.read()).collect();
    let limit = a.get("limit").and_then(|v| v.as_u64()).filter(|n| *n > 0).map(|n| n as usize).or(default_limit).unwrap_or(DEFAULT_ITEMS);
    let offset = a.get("offset").and_then(|v| v.as_u64()).unwrap_or(0) as usize;
    matching.sort_by(|x, y| x.read().cmp(&y.read()).then(y.sent_at().cmp(&x.sent_at())));
    let total = matching.len();
    let page: Vec<&Mail> = matching.into_iter().skip(offset).take(limit).collect();
    let items: Vec<Value> = page
        .iter()
        .map(|m| {
            let o = &m.0;
            let mut l = format!("{} | {} | {} | {}", text(o, "id"), text(o, "from"), text(o, "subject").chars().take(SUBJECT).collect::<String>(), m.state());
            if previews {
                l.push_str(&format!(" | {}", first_line(text(o, "body"), PREVIEW)));
            }
            Value::String(l)
        })
        .collect();
    let more = if offset + page.len() < total { format!("; more: offset={}", offset + page.len()) } else { String::new() };
    Ok(json!({
        "unread": all.iter().filter(|m| !m.read()).count(), "total": all.len(), "shown": page.len(), "items": items,
        "hint": format!("read one with infantry_read <id>{more}")
    })
    .to_string())
}

pub fn read(a: &Value, session: Option<&str>, ask: Ask) -> Result<String, String> {
    let prefix = arg(a, "messageId").ok_or("infantry_read needs a messageId (the full id, or a unique prefix of one from infantry_inbox)")?;
    let matches: Vec<Mail> = fetch_inbox(session, ask)?.into_iter().filter(|m| text(&m.0, "id").starts_with(&prefix)).collect();
    let found = match matches.len() {
        1 => matches[0].clone(),
        0 => return Err(format!("infantry_read: no such message '{prefix}' in this inbox")),
        _ => return Err(format!("infantry_read: message id '{prefix}' is ambiguous: {}", matches.iter().map(|m| text(&m.0, "id")).collect::<Vec<_>>().join(", "))),
    };
    let id = text(&found.0, "id").to_string();
    if found.failed() {
        let why = found.0.get("failureReason").and_then(|v| v.as_str()).unwrap_or("the delivery failed");
        return Err(format!("infantry_read: message '{id}' was never delivered: {why}"));
    }
    if !found.delivered() {
        return Err(format!(
            "infantry_read: message '{id}' has not reached a console yet (state {}), so it cannot be marked read; it is delivered when your current turn ends (see it now with: infantry thread \"{}\")",
            found.state(),
            text(&found.0, "threadId")
        ));
    }
    let r = ask("mail_read", &id, session)?;
    if r.type_ == "error" {
        return Err(if r.data.trim().is_empty() { "mail_read failed".into() } else { r.data });
    }
    let updated = Mail(serde_json::from_str(&r.data).map_err(|e| format!("mail_read: {e}"))?);
    let mut m = updated.row();
    let body = text(&updated.0, "body");
    let shown = match a.get("bodyChars").and_then(|v| v.as_u64()).filter(|n| *n > 0) {
        Some(n) if body.chars().count() > n as usize => format!("{}...{} chars cut", body.chars().take(n as usize).collect::<String>(), body.chars().count() - n as usize),
        _ => body.to_string(),
    };
    m.insert("body".into(), shown.into());
    if let Some(r) = updated.0.get("replyTo").filter(|v| !v.is_null()) {
        m.insert("replyTo".into(), r.clone());
    }
    Ok(Value::Object(m).to_string())
}

pub fn message(a: &Value, session: Option<&str>, ask: Ask) -> Result<String, String> {
    let to = arg(a, "to").ok_or("infantry_message needs a recipient (to)")?;
    let body = arg(a, "body").ok_or("infantry_message needs a body")?;
    let mut req = json!({
        "from": if session.is_none() { HUMAN } else { "" }, "to": to, "subject": arg(a, "subject").unwrap_or_default(), "body": body,
        "allowCrossProject": a.get("allowCrossProject").and_then(|v| v.as_bool()) == Some(true)
    });
    if let Some(r) = arg(a, "replyTo") {
        req["replyTo"] = r.into();
    }
    let r = ask("mail_send", &req.to_string(), session)?;
    match r.type_.as_str() {
        "mail_refused" => {
            let reason = serde_json::from_str::<Value>(&r.data).ok().and_then(|v| v.get("reason").and_then(|x| x.as_str().map(String::from))).unwrap_or(r.data);
            Err(format!("infantry_message refused: {reason}"))
        }
        "error" => Err(format!("infantry_message refused: {}", if r.data.trim().is_empty() { "mail_send failed" } else { &r.data })),
        "mail" => {
            let m = Mail(serde_json::from_str(&r.data).map_err(|e| format!("mail_send: {e}"))?);
            if m.failed() {
                Err(format!("infantry_message: {}", m.0.get("failureReason").and_then(|v| v.as_str()).unwrap_or("the message could not be delivered")))
            } else {
                Ok(format!("ok {} {}", text(&m.0, "id").chars().take(8).collect::<String>(), m.state()))
            }
        }
        t => Err(format!("infantry_message: unexpected answer {t}")),
    }
}

pub fn projects(a: &Value, ask: Ask) -> Result<String, String> {
    let q = arg(a, "query").map(|s| s.to_lowercase());
    let sessions: Vec<Value> = serde_json::from_str::<Value>(&ask("sessions", "", None)?.data).ok().and_then(|v| v.as_array().cloned()).unwrap_or_default();
    let r = ask("project_list", "", None)?;
    if r.type_ == "error" {
        return Err(r.data);
    }
    let list: Vec<Value> = serde_json::from_str::<Value>(&r.data).ok().and_then(|v| v.as_array().cloned()).unwrap_or_default();
    let rows: Vec<Value> = list
        .iter()
        .filter(|p| q.as_ref().map(|q| ["name", "id", "description"].iter().any(|k| text(p, k).to_lowercase().contains(q))).unwrap_or(true))
        .map(|p| {
            let mut roles: Vec<String> = sessions
                .iter()
                .filter(|s| text(s, "projectId") == text(p, "id") && !text(s, "role").is_empty() && s.get("neverRun").and_then(|v| v.as_bool()) != Some(true) && !matches!(text(s, "state"), "FAILED" | "ORPHANED"))
                .map(|s| text(s, "role").to_string())
                .collect();
            roles.sort();
            roles.dedup();
            json!({"id": text(p, "id"), "name": text(p, "name"), "description": text(p, "description"), "liveRoles": roles})
        })
        .collect();
    Ok(json!({"count": rows.len(), "projects": rows, "hint": "mail a role there: infantry_message to=\"role@<id or name>\" allowCrossProject=true"}).to_string())
}

pub fn announce(a: &Value, session: Option<&str>, project_hint: Option<&str>, allowed_setting: Option<String>, ask: Ask) -> Result<String, String> {
    const N: &str = "infantry_announce";
    let body = arg(a, "body").ok_or(format!("{N} needs a body"))?;
    let subject = arg(a, "subject").unwrap_or_else(|| "Announcement".into());
    let sessions: Vec<Value> = serde_json::from_str::<Value>(&ask("sessions", "", None)?.data).ok().and_then(|v| v.as_array().cloned()).unwrap_or_default();
    let sender = match session {
        Some(id) => Some(sessions.iter().find(|s| text(s, "id") == id).cloned().ok_or(format!("{N} refused: unknown sender session '{id}'"))?),
        None => None,
    };
    let role = sender.as_ref().map(|s| text(s, "role").to_lowercase()).unwrap_or_else(|| "owner".into());
    let allowed: Vec<String> = allowed_setting
        .and_then(|s| serde_json::from_str::<Vec<String>>(&s).ok())
        .unwrap_or_else(|| vec!["owner".into(), "pm".into()])
        .into_iter()
        .map(|s| s.trim().to_lowercase())
        .collect();
    if !allowed.contains(&role) {
        let mut sorted = allowed.clone();
        sorted.sort();
        return Err(format!("{N} refused: role '{}' may not announce; allowed: {} (setting announce.allowedRoles)", if role.is_empty() { "none" } else { &role }, sorted.join(", ")));
    }
    let project = sender.as_ref().map(|s| text(s, "projectId").to_string()).filter(|p| !p.is_empty()).or_else(|| arg(a, "project")).or_else(|| project_hint.map(String::from)).ok_or(format!("{N} refused: no project (pass project)"))?;
    let mut sent = 0;
    let mut skipped = 0;
    let mut failed = vec![];
    for t in sessions.iter().filter(|s| text(s, "projectId") == project && s.get("global").and_then(|v| v.as_bool()) != Some(true) && Some(text(s, "id")) != session) {
        let live = t.get("neverRun").and_then(|v| v.as_bool()) != Some(true) && matches!(text(t, "state"), "IDLE" | "BUSY" | "STARTING" | "WAITING_INPUT");
        if !live {
            skipped += 1;
            continue;
        }
        let req = json!({"from": if session.is_none() { HUMAN } else { "" }, "to": text(t, "id"), "subject": subject, "body": body, "allowCrossProject": false});
        match ask("mail_send", &req.to_string(), session) {
            Ok(r) if r.type_ == "mail" => sent += 1,
            _ => failed.push(text(t, "id").chars().take(8).collect::<String>()),
        }
    }
    let tail = if failed.is_empty() { String::new() } else { format!("; failed {}: {}", failed.len(), failed.join(",")) };
    Ok(format!("sent {sent}, skipped {skipped}{tail}"))
}

impl Plugin for Chat {
    fn start() -> Result<(), String> {
        host::events::subscribe("clock.tick").map(|_| ())
    }

    fn on_event(_subscription: u64, _event: infantry_plugin_sdk::types::NamedEvent) {
        let ask = |ty: &str, data: &str, sess: Option<&str>| host::frames::request(ty, data, sess, TIMEOUT_MS);
        retention::on_tick(&ask, |k| host::settings::get(k));
    }

    fn call_tool(name: String, args: String, ctx: CallContext) -> Result<String, String> {
        let a: Value = serde_json::from_str(if args.trim().is_empty() { "{}" } else { &args }).map_err(|e| format!("{name}: arguments are not JSON: {e}"))?;
        let session = ctx.client_session_id.as_deref().map(str::trim).filter(|s| !s.is_empty());
        let ask = |ty: &str, data: &str, sess: Option<&str>| host::frames::request(ty, data, sess, TIMEOUT_MS);
        match name.as_str() {
            "infantry_inbox" => inbox(&a, session, host::settings::get("limits.tools.inboxLimit").and_then(|v| v.parse::<usize>().ok()).filter(|n| *n > 0), &ask),
            "infantry_read" => read(&a, session, &ask),
            "infantry_message" => message(&a, session, &ask),
            "infantry_projects" => projects(&a, &ask),
            "infantry_announce" => announce(&a, session, ctx.client_project_id.as_deref(), host::settings::get("announce.allowedRoles"), &ask),
            other => Err(format!("unknown tool {other}")),
        }
    }

    fn handle_frame(frame: Frame, _caller: Caller) -> Result<Option<Frame>, String> {
        let ask = |ty: &str, data: &str, sess: Option<&str>| host::frames::request(ty, data, sess, TIMEOUT_MS);
        let (ok, r) = match frame.type_.as_str() {
            "image_upload" => ("image", images::upload(&frame.data, &ask)),
            "image_fetch" => ("image_bytes", images::fetch(&frame.data, &ask)),
            other => return Err(format!("this plugin does not answer {other}")),
        };
        Ok(Some(match r {
            Ok(v) => Frame { type_: ok.into(), data: v.to_string(), session_id: None, request_id: frame.request_id },
            Err(why) => Frame { type_: "error".into(), data: format!("{} refused: {why}", frame.type_), session_id: None, request_id: frame.request_id },
        }))
    }
}

export_plugin!(Chat);

#[cfg(test)]
mod tests {
    use super::*;

    fn frame(t: &str, d: &str) -> Frame {
        Frame { type_: t.into(), data: d.into(), session_id: None, request_id: None }
    }

    #[test]
    fn inbox_lists_unread_first() {
        let ask = |_: &str, _: &str, _: Option<&str>| Ok(frame("mail_inbox", r#"[{"id":"a","from":"x","subject":"old","state":"READ","sentAt":1},{"id":"b","from":"y","subject":"new","state":"DELIVERED","sentAt":2}]"#));
        let out: Value = serde_json::from_str(&inbox(&json!({}), Some("s"), None, &ask).unwrap()).unwrap();
        assert_eq!(out["unread"], 1);
        assert_eq!(out["items"][0], "b | y | new | DELIVERED");
    }
}
