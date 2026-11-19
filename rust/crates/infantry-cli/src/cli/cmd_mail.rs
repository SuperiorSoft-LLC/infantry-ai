// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! COMMS-1 mail: `send`, `inbox`, `read`, `threads`, `thread`, the bare `waiting` mailbox view, and the raw
//! `waiting on|off|show` flag command (WAITING-FLAG-1), as the Kotlin CLI prints them.

use super::args::{self, Parsed};
use super::cmd_sessions::{check_contract, iso, take};
use super::conn::{Envelope, FrameStream};
use super::{Command, CmdResult, Ctx, Getenv, Io, Needs, RawCommand, HUMAN_SENDER};
use serde::Deserialize;
use serde_json::{json, Value};

/// `--reply-to` takes a full id as is from this length on (a UUID is 36).
const FULL_ID_LEN: usize = 36;
/// `limits.tools.inboxLimit`'s default when the daemon's catalog does not say.
const INBOX_LIMIT_DEFAULT: i64 = 20;
const INBOX_LIMIT_KEY: &str = "limits.tools.inboxLimit";
/// `firstLineOf` keeps this many characters; the table column keeps 60 (72 with `--previews`).
const FIRST_LINE_CHARS: usize = 72;
const FIRST_LINE_COLUMN: usize = 60;
const ID_SHORT: usize = 8;
/// WaitingCli's request timeout.
const WAITING_TIMEOUT_MS: u64 = 5_000;
const SESSION_ENV: &str = "INFANTRY_SESSION_ID";

#[derive(Deserialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct Message {
    pub id: String,
    #[serde(default)]
    pub thread_id: String,
    pub from: String,
    pub to: String,
    #[serde(default)]
    pub subject: String,
    #[serde(default)]
    pub body: String,
    #[serde(default)]
    pub sent_at: i64,
    #[serde(default)]
    pub state: String,
    #[serde(default)]
    pub delivered_at: Option<i64>,
    #[serde(default)]
    pub read_at: Option<i64>,
    #[serde(default)]
    pub failure_reason: Option<String>,
}

impl Message {
    pub fn read(&self) -> bool {
        self.read_at.is_some() || matches!(self.state.as_str(), "READ" | "ANSWERED")
    }
    pub fn delivered(&self) -> bool {
        self.delivered_at.is_some() || matches!(self.state.as_str(), "DELIVERED" | "READ" | "ANSWERED")
    }
    pub fn failed(&self) -> bool {
        self.state == "UNDELIVERABLE"
    }
}

/// `firstLineOf`: the first non-blank line, trimmed, at most 72 characters.
pub fn first_line(body: &str) -> String {
    take(body.lines().map(str::trim).find(|l| !l.is_empty()).unwrap_or(""), FIRST_LINE_CHARS)
}

fn short(id: &str) -> String {
    take(id, ID_SHORT)
}

fn session_of(ctx: &Ctx) -> Option<String> {
    ctx.env(SESSION_ENV).map(|s| s.trim().to_string()).filter(|s| !s.is_empty())
}

fn fetch_inbox(ctx: &mut Ctx, who: &str, session: Option<String>) -> Result<Option<Vec<Message>>, String> {
    let a = ctx.stream().request(&Envelope::new("mail_inbox", who).with_session(session), "mails", "mail_inbox", true)?;
    match serde_json::from_str::<Vec<Message>>(&a.data) {
        Ok(m) => Ok(Some(m)),
        Err(e) => {
            ctx.err(format!("error: malformed mails answer from the daemon: {e}"));
            Ok(None)
        }
    }
}

/// Prefix match over an inbox: the one message, or the refusal ("" none, else the ambiguity text).
fn match_message<'a>(inbox: &'a [Message], prefix: &str) -> Result<&'a Message, Option<String>> {
    let hits: Vec<&Message> = inbox.iter().filter(|m| m.id.starts_with(prefix)).collect();
    match hits.len() {
        1 => Ok(hits[0]),
        0 => Err(None),
        _ => Err(Some(format!(
            "message id '{prefix}' is ambiguous: {}",
            hits.iter().map(|m| short(&m.id)).collect::<Vec<_>>().join(", ")
        ))),
    }
}

/// The `mail_send` body, in the Kotlin field order (encodeDefaults: every field written).
pub fn send_request(session: Option<&str>, to: &str, body: &str, reply_to: Option<&str>, cross: bool) -> String {
    let from = if session.is_none() { HUMAN_SENDER } else { "" };
    format!(
        "{{\"from\":{},\"to\":{},\"subject\":\"\",\"body\":{},\"threadId\":null,\"replyTo\":{},\"checkpoint\":null,\"needsInput\":false,\"allowCrossProject\":{cross}}}",
        Value::from(from),
        Value::from(to),
        Value::from(body),
        reply_to.map(Value::from).unwrap_or(Value::Null)
    )
}

fn send(ctx: &mut Ctx, p: &Parsed) -> CmdResult {
    check_contract(ctx)?;
    let to = p.args[0].trim().to_string();
    let body = if p.stdin {
        ctx.io.stdin().trim().to_string()
    } else if let Some(f) = p.val("file") {
        match std::fs::read_to_string(f) {
            Ok(t) => t.trim().to_string(),
            Err(e) => {
                ctx.err(format!("error: cannot read --file {f}: {e}"));
                return Ok(2);
            }
        }
    } else {
        p.args[1..].join(" ").trim().to_string()
    };
    if body.trim().is_empty() {
        ctx.err("error: the message is empty");
        return Ok(2);
    }
    if body.chars().count() > 1 && body.starts_with('\'') && body.ends_with('\'') {
        ctx.err("warning: the message is wrapped in '...'; cmd.exe keeps those quotes, use \"double quotes\"");
    }
    let session = session_of(ctx);
    let sender = session.clone().unwrap_or_else(|| HUMAN_SENDER.to_string());
    let reply_to = match p.val("reply-to").map(str::trim) {
        None => None,
        Some(id) if id.chars().count() >= FULL_ID_LEN => Some(id.to_string()),
        Some(id) => {
            let Some(inbox) = fetch_inbox(ctx, &sender, session.clone())? else { return Ok(1) };
            match match_message(&inbox, id) {
                Ok(m) => Some(m.id.clone()),
                Err(None) => {
                    ctx.err(format!(
                        "error: no message '{id}' in my inbox to reply to; --reply-to takes an id from `infantry inbox`, or the full id from the message"
                    ));
                    return Ok(1);
                }
                Err(Some(e)) => {
                    ctx.err(format!("error: {e}"));
                    return Ok(1);
                }
            }
        }
    };
    let req = send_request(session.as_deref(), &to, &body, reply_to.as_deref(), p.flag("cross-project"));
    let env = Envelope::new("mail_send", req).with_session(session.clone());
    let fence = Envelope::new("mail_outbox", sender.clone());
    let a = ctx.stream().request_last_of(
        &env,
        &["mail", "mail_refused"],
        &fence,
        "mails",
        "mail_send",
        "Do not re-send unless a duplicate message is acceptable",
    )?;
    if a.kind == "mail_refused" {
        let reason = serde_json::from_str::<Value>(&a.data)
            .ok()
            .and_then(|v| v.get("reason").and_then(Value::as_str).map(str::to_string))
            .unwrap_or_else(|| format!("mail_send refused: {}", a.data));
        ctx.err(format!("error: {reason}"));
        return Ok(1);
    }
    let m: Message = match serde_json::from_str(&a.data) {
        Ok(m) => m,
        Err(e) => {
            ctx.err(format!("error: malformed mail answer from the daemon: {e}"));
            return Ok(1);
        }
    };
    if m.failed() {
        ctx.err(format!("error: {}", m.failure_reason.as_deref().unwrap_or("the message could not be delivered")));
        return Ok(1);
    }
    ctx.out(format!("sent {}", m.id));
    Ok(0)
}

fn inbox_limit(ctx: &mut Ctx) -> i64 {
    let Ok(a) = ctx.stream().request(&Envelope::new("limit_catalog", ""), "limit_catalog", "limit_catalog", true) else {
        return INBOX_LIMIT_DEFAULT;
    };
    serde_json::from_str::<Value>(&a.data)
        .ok()
        .and_then(|v| {
            v.get("limits")?.as_array()?.iter().find(|l| l.get("key").and_then(Value::as_str) == Some(INBOX_LIMIT_KEY))?.get("value")?.as_i64()
        })
        .unwrap_or(INBOX_LIMIT_DEFAULT)
}

/// The `inbox` table for a filtered list (unread first, newest first), or its empty line.
pub fn inbox_lines(mut list: Vec<Message>, unread_only: bool, offset: usize, limit: usize, previews: bool) -> Vec<String> {
    if unread_only {
        list.retain(|m| !m.read());
    }
    if list.is_empty() {
        return vec![if unread_only { "No unread messages." } else { "No messages." }.to_string()];
    }
    list.sort_by(|a, b| a.read().cmp(&b.read()).then(b.sent_at.cmp(&a.sent_at)));
    let unread = list.iter().filter(|m| !m.read()).count();
    let page: Vec<&Message> = list.iter().skip(offset).take(limit).collect();
    let width = if previews { FIRST_LINE_CHARS } else { FIRST_LINE_COLUMN };
    let mut out = vec![
        format!(
            "inbox: {unread} unread, {} total; showing {} from {offset}. Read one with: infantry read <id>",
            list.len(),
            page.len()
        ),
        "ID | FROM | WHEN | READ | FIRST LINE".to_string(),
    ];
    for m in page {
        out.push(format!(
            "{} | {} | {} | {} | {}",
            short(&m.id),
            m.from,
            iso(m.sent_at),
            if m.read() { "yes" } else { "no" },
            take(&first_line(&m.body), width)
        ));
    }
    out
}

fn inbox(ctx: &mut Ctx, p: &Parsed) -> CmdResult {
    check_contract(ctx)?;
    let session = session_of(ctx);
    let who = session.clone().unwrap_or_else(|| HUMAN_SENDER.to_string());
    let Some(list) = fetch_inbox(ctx, &who, session)? else { return Ok(1) };
    let offset = p.val("offset").and_then(|v| v.trim().parse::<i64>().ok()).unwrap_or(0).max(0) as usize;
    let limit = match p.val("limit").and_then(|v| v.trim().parse::<i64>().ok()).filter(|n| *n > 0) {
        Some(n) => n,
        None => inbox_limit(ctx),
    }
    .max(0) as usize;
    for l in inbox_lines(list, p.flag("unread"), offset, limit, p.flag("previews")) {
        ctx.out(l);
    }
    Ok(0)
}

pub fn read_lines(m: &Message) -> Vec<String> {
    let mut out = vec![
        format!("[mail] {}", m.id),
        format!("[mail] from {} -> {} · thread {} · {}", m.from, m.to, m.thread_id, if m.read() { "read" } else { "unread" }),
    ];
    if !m.subject.trim().is_empty() {
        out.push(format!("[mail] subject {}", m.subject));
    }
    out.push(format!("[mail] sent {} · state {}", iso(m.sent_at), m.state));
    out.push(String::new());
    out.push(m.body.clone());
    out
}

fn read(ctx: &mut Ctx, p: &Parsed) -> CmdResult {
    check_contract(ctx)?;
    let prefix = p.args[0].trim().to_string();
    let session = session_of(ctx);
    let who = session.clone().unwrap_or_else(|| HUMAN_SENDER.to_string());
    let Some(list) = fetch_inbox(ctx, &who, session.clone())? else { return Ok(1) };
    let m = match match_message(&list, &prefix) {
        Ok(m) => m.clone(),
        Err(e) => {
            ctx.err(format!("error: {}", e.unwrap_or_else(|| format!("no such message '{prefix}'"))));
            return Ok(1);
        }
    };
    if m.failed() {
        ctx.err(format!(
            "error: message '{}' was never delivered: {}",
            short(&m.id),
            m.failure_reason.as_deref().unwrap_or("the delivery failed")
        ));
        return Ok(1);
    }
    if !m.delivered() {
        ctx.err(format!(
            "error: message '{}' has not reached a console yet (state {}), so it cannot be marked read",
            short(&m.id),
            m.state
        ));
        ctx.err(format!("hint: it is delivered when your current turn ends. Read it now with: infantry thread \"{}\"", m.thread_id));
        return Ok(1);
    }
    let a = ctx.stream().request(&Envelope::new("mail_read", m.id.clone()).with_session(session), "mail", "mail_read", true)?;
    let read: Message = match serde_json::from_str(&a.data) {
        Ok(r) => r,
        Err(e) => {
            ctx.err(format!("error: malformed mail answer from the daemon: {e}"));
            return Ok(1);
        }
    };
    for l in read_lines(&read) {
        ctx.out(l);
    }
    Ok(0)
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ThreadSummary {
    pub id: String,
    #[serde(default)]
    pub participants: Vec<String>,
    #[serde(default)]
    pub message_count: i64,
    #[serde(default)]
    pub last_at: i64,
}

pub fn threads_lines(all: &[ThreadSummary], me: &str) -> Vec<String> {
    let mine: Vec<&ThreadSummary> = all.iter().filter(|t| t.participants.iter().any(|p| p == me)).collect();
    if mine.is_empty() {
        return vec!["No threads.".into()];
    }
    let mut out = vec!["THREAD | WITH | MESSAGES | LAST".to_string()];
    for t in mine {
        let with: Vec<&str> = t.participants.iter().filter(|p| *p != me).map(String::as_str).collect();
        out.push(format!("{} | {} | {} | {}", t.id, with.join(", "), t.message_count, iso(t.last_at)));
    }
    out
}

fn threads(ctx: &mut Ctx, _: &Parsed) -> CmdResult {
    check_contract(ctx)?;
    let session = session_of(ctx);
    let me = session.clone().unwrap_or_else(|| HUMAN_SENDER.to_string());
    let a = ctx.stream().request(&Envelope::new("mail_threads", "").with_session(session), "mail_threads", "mail_threads", true)?;
    let all: Vec<ThreadSummary> = match serde_json::from_str(&a.data) {
        Ok(v) => v,
        Err(e) => {
            ctx.err(format!("error: malformed mail_threads answer from the daemon: {e}"));
            return Ok(1);
        }
    };
    for l in threads_lines(&all, &me) {
        ctx.out(l);
    }
    Ok(0)
}

pub fn thread_lines(id: &str, msgs: &[Message]) -> Vec<String> {
    let n = msgs.len();
    let mut out = vec![format!("THREAD {id} · {n} messages")];
    for (i, m) in msgs.iter().enumerate() {
        out.push(format!("[mail] {}/{n} {} from {} -> {} · {} · {}", i + 1, m.id, m.from, m.to, m.state, iso(m.sent_at)));
        out.push(m.body.clone());
        out.push(String::new());
    }
    out
}

fn thread(ctx: &mut Ctx, p: &Parsed) -> CmdResult {
    check_contract(ctx)?;
    let id = p.args[0].trim().to_string();
    let session = session_of(ctx);
    let a = ctx.stream().request(&Envelope::new("mail_thread", id.clone()).with_session(session), "mails", "mail_thread", true)?;
    let msgs: Vec<Message> = match serde_json::from_str(&a.data) {
        Ok(v) => v,
        Err(e) => {
            ctx.err(format!("error: malformed mails answer from the daemon: {e}"));
            return Ok(1);
        }
    };
    if msgs.is_empty() {
        ctx.err(format!("error: no such thread '{id}'"));
        if id.starts_with("pair:") && !id.contains('|') {
            ctx.err("hint: a pair thread id contains '|'; put the whole id in \"double quotes\" (see: infantry threads)");
        }
        return Ok(1);
    }
    for l in thread_lines(&id, &msgs) {
        ctx.out(l);
    }
    Ok(0)
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WaitingOn {
    pub session: String,
    pub message_id: String,
    #[serde(default)]
    pub waiting_on: String,
    #[serde(default)]
    pub since: i64,
    #[serde(default)]
    pub subject: String,
}

pub fn waiting_lines(rows: &[WaitingOn]) -> Vec<String> {
    if rows.is_empty() {
        return vec!["Nothing is waiting.".into()];
    }
    let mut out = vec!["ID | SESSION | WAITING ON | SINCE | SUBJECT".to_string()];
    for w in rows {
        out.push(format!("{} | {} | {} | {} | {}", short(&w.message_id), w.session, w.waiting_on, iso(w.since), w.subject));
    }
    out
}

fn waiting(ctx: &mut Ctx, _: &Parsed) -> CmdResult {
    check_contract(ctx)?;
    let session = session_of(ctx);
    let a = ctx.stream().request(&Envelope::new("mail_waiting", "").with_session(session), "mail_waiting", "mail_waiting", true)?;
    let rows: Vec<WaitingOn> = match serde_json::from_str(&a.data) {
        Ok(v) => v,
        Err(e) => {
            ctx.err(format!("error: malformed mail_waiting answer from the daemon: {e}"));
            return Ok(1);
        }
    };
    for l in waiting_lines(&rows) {
        ctx.out(l);
    }
    Ok(0)
}

fn validate_send(p: &Parsed, _: Getenv) -> Option<String> {
    if p.args.first().is_none_or(|a| a.trim().is_empty()) {
        return Some("send requires a recipient: infantry send <agentId|role> \"message\"".into());
    }
    if p.args[1..].iter().all(|a| a.trim().is_empty()) && !p.stdin && p.val("file").is_none() {
        return Some("send requires a message: infantry send <agentId|role> \"message\"".into());
    }
    None
}

fn validate_read(p: &Parsed, _: Getenv) -> Option<String> {
    p.args.first().is_none_or(|a| a.trim().is_empty()).then(|| "read requires a message id: infantry read <msgId> (see: infantry inbox)".into())
}

fn validate_thread(p: &Parsed, _: Getenv) -> Option<String> {
    p.args.first().is_none_or(|a| a.trim().is_empty()).then(|| "thread requires a thread id: infantry thread <id> (see: infantry threads)".into())
}

pub static SEND: Command = Command { name: "send", needs: Needs::Stream, validate: validate_send, run: send };
pub static INBOX: Command = Command { name: "inbox", needs: Needs::Stream, validate: super::no_validation, run: inbox };
pub static READ: Command = Command { name: "read", needs: Needs::Stream, validate: validate_read, run: read };
pub static THREADS: Command = Command { name: "threads", needs: Needs::Stream, validate: super::no_validation, run: threads };
pub static THREAD: Command = Command { name: "thread", needs: Needs::Stream, validate: validate_thread, run: thread };
pub static WAITING: Command = Command { name: "waiting", needs: Needs::Stream, validate: super::no_validation, run: waiting };

// ---- WAITING-FLAG-1: `waiting on|off|show` (raw: never meets the global parser) ----

/// `WaitingCli.OPS`.
const WAITING_OPS: [&str; 4] = ["on", "off", "show", "get"];

pub const WAITING_USAGE: &str = "infantry waiting on --reason \"what you wait for\" [--minutes N]   flag this session \"active but waiting\" (default N: setting limits.waiting.defaultMinutes)
infantry waiting off                                            turn the flag off
infantry waiting show                                           print the flag
The flag also clears by itself when your next turn starts, and expires N minutes (+ grace) after it was set.";

/// `WaitingCli.parse`: the `waiting` frame body (explicitNulls=false: absent fields are left out).
pub fn waiting_request(op: &str, rest: &[String]) -> Result<String, String> {
    if !WAITING_OPS.contains(&op) {
        return Err(format!("unknown command '{}' (on, off, show)", take(op, 40)));
    }
    if op == "off" {
        return Ok(json!({"op": "off"}).to_string());
    }
    if op != "on" {
        return Ok(json!({"op": "get"}).to_string());
    }
    let mut reason: Option<String> = None;
    let mut minutes: Option<i32> = None;
    let mut free: Vec<String> = Vec::new();
    let mut i = 0;
    while i < rest.len() {
        let a = &rest[i];
        match a.as_str() {
            "--reason" => {
                i += 1;
                reason = Some(rest.get(i).cloned().ok_or("--reason needs a value")?);
            }
            "--minutes" => {
                i += 1;
                minutes = Some(rest.get(i).and_then(|v| v.parse::<i32>().ok()).ok_or("--minutes needs a whole number")?);
            }
            _ if a.starts_with("--") => return Err(format!("unknown option '{}'", take(a, 40))),
            _ => free.push(a.clone()),
        }
        i += 1;
    }
    if reason.is_none() && !free.is_empty() {
        reason = Some(free.join(" "));
    }
    let reason = reason.filter(|r| !r.trim().is_empty()).ok_or("on needs --reason \"what you are waiting for\"")?;
    let mut v = json!({"op": "on", "reason": reason});
    if let Some(m) = minutes {
        v["minutes"] = json!(m);
    }
    Ok(v.to_string())
}

/// `WaitingRules.line`.
pub fn waiting_flag_line(flag: Option<&Value>, now: i64) -> String {
    let Some(w) = flag else { return "not waiting".into() };
    let n = |k: &str| w.get(k).and_then(Value::as_i64).unwrap_or(0);
    let reason = w.get("reason").and_then(Value::as_str).unwrap_or("");
    if now > n("expiresAt") {
        format!("waiting EXPIRED {} min past the expected end: {reason}", (now - n("untilAt")) / 60_000)
    } else {
        format!("waiting {} min of ~{}: {reason}", (now - n("since")) / 60_000, (n("untilAt") - n("since")) / 60_000)
    }
}

fn waiting_matches(args: &[String]) -> bool {
    args.first().map(String::as_str) == Some("waiting") && args.get(1).is_some_and(|a| WAITING_OPS.contains(&a.to_lowercase().as_str()))
}

fn waiting_flag(args: &[String], io: &mut dyn Io, getenv: Getenv) -> i32 {
    let rest = &args[1..];
    let op = rest.first().map(|a| a.to_lowercase());
    let Some(op) = op.filter(|o| !matches!(o.as_str(), "--help" | "-h" | "help")) else {
        io.out(WAITING_USAGE);
        return if rest.is_empty() { 2 } else { 0 };
    };
    let body = match waiting_request(&op, &rest[1..]) {
        Ok(b) => b,
        Err(e) => {
            io.err(&format!("error: waiting: {e}"));
            io.err(WAITING_USAGE);
            return 2;
        }
    };
    let Some(session) = getenv(SESSION_ENV).map(|s| s.trim().to_string()).filter(|s| !s.is_empty()) else {
        io.err(&format!("error: waiting: not running inside an Infantry AI session (no {SESSION_ENV}); nothing flagged"));
        return 1;
    };
    let answer = (|| -> Result<String, String> {
        let parsed = args::parse(&[])?;
        let ep = super::resolve_endpoint(&parsed, getenv, false)?;
        let sink: Box<dyn Fn(&str)> = Box::new(|l: &str| eprintln!("{l}"));
        let mut stream = FrameStream::open(&ep, sink)?;
        let a = stream.request_within(&Envelope::new("waiting", body).with_session(Some(session)), "waiting", "waiting", false, WAITING_TIMEOUT_MS)?;
        Ok(a.data)
    })();
    match answer {
        Ok(data) => {
            let t = data.trim();
            let flag = if t.is_empty() || t == "null" {
                None
            } else {
                match serde_json::from_str::<Value>(t) {
                    Ok(v) => Some(v),
                    Err(e) => {
                        io.err(&format!("error: waiting: {e}"));
                        return 1;
                    }
                }
            };
            let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0);
            io.out(&waiting_flag_line(flag.as_ref(), now));
            0
        }
        Err(e) => {
            io.err(&format!("error: waiting: {e}"));
            1
        }
    }
}

pub const WAITING_FLAG: RawCommand = RawCommand { matches: waiting_matches, run: waiting_flag };

#[cfg(test)]
mod tests {
    use super::*;

    fn msg(id: &str, state: &str, sent: i64, body: &str) -> Message {
        Message {
            id: id.into(),
            thread_id: "t1".into(),
            from: "pm".into(),
            to: "me".into(),
            subject: String::new(),
            body: body.into(),
            sent_at: sent,
            state: state.into(),
            delivered_at: None,
            read_at: None,
            failure_reason: None,
        }
    }

    #[test]
    fn send_body_golden() {
        assert_eq!(
            send_request(None, "pm", "hi \"x\"", None, false),
            r#"{"from":"user","to":"pm","subject":"","body":"hi \"x\"","threadId":null,"replyTo":null,"checkpoint":null,"needsInput":false,"allowCrossProject":false}"#
        );
        assert!(send_request(Some("s1"), "pm", "b", Some("m1"), true).starts_with(r#"{"from":"","to":"pm""#));
    }

    #[test]
    fn first_line_and_inbox_order() {
        assert_eq!(first_line("\n  \n  hello world  \nsecond"), "hello world");
        let list = vec![msg("aaaaaaaaaa", "READ", 3, "old"), msg("bbbbbbbbbb", "DELIVERED", 1, "new\nmore")];
        let lines = inbox_lines(list.clone(), false, 0, 20, false);
        assert!(lines[0].starts_with("inbox: 1 unread, 2 total; showing 2 from 0."));
        assert_eq!(lines[1], "ID | FROM | WHEN | READ | FIRST LINE");
        assert!(lines[2].starts_with("bbbbbbbb | pm | ") && lines[2].ends_with(" | no | new"));
        assert_eq!(inbox_lines(vec![msg("a", "READ", 1, "x")], true, 0, 20, false), ["No unread messages."]);
        assert_eq!(inbox_lines(vec![], false, 0, 20, false), ["No messages."]);
    }

    #[test]
    fn validation_texts() {
        let p = args::parse(&["send".to_string(), "pm".to_string()]).unwrap();
        assert_eq!(validate_send(&p, &|_| None).unwrap(), "send requires a message: infantry send <agentId|role> \"message\"");
        let p = args::parse(&["read".to_string()]).unwrap();
        assert_eq!(validate_read(&p, &|_| None).unwrap(), "read requires a message id: infantry read <msgId> (see: infantry inbox)");
    }

    #[test]
    fn waiting_flag_golden() {
        assert_eq!(waiting_request("off", &[]).unwrap(), r#"{"op":"off"}"#);
        assert_eq!(waiting_request("show", &[]).unwrap(), r#"{"op":"get"}"#);
        let on = waiting_request("on", &["review".into(), "--minutes".into(), "5".into()]).unwrap();
        let v: Value = serde_json::from_str(&on).unwrap();
        assert_eq!((v["reason"].as_str(), v["minutes"].as_i64()), (Some("review"), Some(5)));
        assert_eq!(waiting_request("on", &[]).unwrap_err(), "on needs --reason \"what you are waiting for\"");
        assert_eq!(waiting_flag_line(None, 0), "not waiting");
        let w = json!({"reason":"r","since":0,"untilAt":600_000,"expiresAt":900_000});
        assert_eq!(waiting_flag_line(Some(&w), 120_000), "waiting 2 min of ~10: r");
        assert_eq!(waiting_flag_line(Some(&w), 960_000), "waiting EXPIRED 6 min past the expected end: r");
        assert!(waiting_matches(&["waiting".into(), "ON".into()]));
        assert!(!waiting_matches(&["waiting".into()]));
    }
}
