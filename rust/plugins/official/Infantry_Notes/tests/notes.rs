// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! Light tests over a real SQLite with the core schema (31.sqm / 32.sqm / memory_notes.sq, append-only triggers included).
use infantry_notes::api::{self, ToolCtx};
use infantry_notes::db::{Db, Ports, Row, SessionInfo, V};
use rusqlite::{params_from_iter, types::ValueRef, Connection};
use std::cell::{Cell, RefCell};

const SCHEMA: &str = "
CREATE TABLE memory_notes (id TEXT NOT NULL PRIMARY KEY, project_id TEXT NOT NULL, name TEXT NOT NULL, folder TEXT NOT NULL DEFAULT '',
  description TEXT NOT NULL DEFAULT '', body TEXT NOT NULL DEFAULT '', updated_at TEXT NOT NULL, created_at INTEGER NOT NULL,
  scope TEXT NOT NULL DEFAULT 'project', owner_id TEXT NOT NULL DEFAULT '');
CREATE TABLE decisions (project_id TEXT NOT NULL, number INTEGER NOT NULL CHECK (number >= 1), summary TEXT NOT NULL, body TEXT NOT NULL DEFAULT '',
  supersedes INTEGER CHECK (supersedes IS NULL OR (supersedes >= 1 AND supersedes < number)), author_session_id TEXT, author_agent_id TEXT, author_role TEXT,
  created_at INTEGER NOT NULL, PRIMARY KEY (project_id, number));
CREATE UNIQUE INDEX d_sup ON decisions(project_id, supersedes) WHERE supersedes IS NOT NULL;
CREATE TRIGGER d_upd BEFORE UPDATE ON decisions BEGIN SELECT RAISE(ABORT, 'decisions are append-only'); END;
CREATE TRIGGER d_del BEFORE DELETE ON decisions BEGIN SELECT RAISE(ABORT, 'decisions are append-only'); END;
CREATE TABLE issues (project_id TEXT NOT NULL, number INTEGER NOT NULL CHECK (number >= 1), summary TEXT NOT NULL, location TEXT NOT NULL DEFAULT '',
  ticket_id TEXT, implicit INTEGER NOT NULL DEFAULT 0 CHECK (implicit IN (0, 1)), opened_by_session TEXT, opened_by_agent TEXT, created_at INTEGER NOT NULL,
  PRIMARY KEY (project_id, number));
CREATE TABLE issue_events (project_id TEXT NOT NULL, issue_number INTEGER NOT NULL, seq INTEGER NOT NULL CHECK (seq >= 1),
  kind TEXT NOT NULL CHECK (kind IN ('attempt', 'fix')), summary TEXT NOT NULL,
  outcome TEXT CHECK ((kind = 'attempt' AND outcome IN ('worked', 'failed', 'partial')) OR (kind = 'fix' AND outcome IS NULL)),
  location TEXT, session_id TEXT, agent_id TEXT, created_at INTEGER NOT NULL, PRIMARY KEY (project_id, issue_number, seq));
CREATE UNIQUE INDEX e_fix ON issue_events(project_id, issue_number) WHERE kind = 'fix';
CREATE TRIGGER e_after_fix BEFORE INSERT ON issue_events BEGIN SELECT RAISE(ABORT, 'issue is already fixed')
  WHERE EXISTS (SELECT 1 FROM issue_events WHERE project_id = new.project_id AND issue_number = new.issue_number AND kind = 'fix'); END;
CREATE TRIGGER i_upd BEFORE UPDATE ON issues BEGIN SELECT RAISE(ABORT, 'issues are append-only'); END;
";

struct Lite(Connection);

impl Db for Lite {
    fn exec(&self, sql: &str, p: &[V]) -> Result<u64, String> {
        self.0.execute(sql, params_from_iter(p.iter().map(to))).map(|n| n as u64).map_err(|e| format!("storage: {e}"))
    }
    fn query(&self, sql: &str, p: &[V]) -> Result<Vec<Row>, String> {
        let mut st = self.0.prepare(sql).map_err(|e| format!("storage: {e}"))?;
        let cols: Vec<String> = st.column_names().iter().map(|s| s.to_string()).collect();
        let n = cols.len();
        let rows = st
            .query_map(params_from_iter(p.iter().map(to)), |r| {
                Ok((0..n)
                    .map(|i| match r.get_ref(i).unwrap() {
                        ValueRef::Integer(x) => V::I(x),
                        ValueRef::Text(t) => V::T(String::from_utf8_lossy(t).into()),
                        _ => V::Null,
                    })
                    .collect::<Vec<_>>())
            })
            .map_err(|e| format!("storage: {e}"))?;
        Ok(rows.map(|v| Row { cols: cols.clone(), vals: v.unwrap() }).collect())
    }
}

fn to(v: &V) -> rusqlite::types::Value {
    match v {
        V::Null => rusqlite::types::Value::Null,
        V::I(n) => (*n).into(),
        V::T(s) => s.clone().into(),
    }
}

struct Host {
    t: Cell<i64>,
    id: Cell<u32>,
    sessions: Vec<SessionInfo>,
    log: RefCell<Vec<String>>,
}

impl Ports for Host {
    fn now_ms(&self) -> i64 {
        self.t.set(self.t.get() + 1);
        1_700_000_000_000 + self.t.get()
    }
    fn now_iso(&self) -> String {
        infantry_notes::iso(self.now_ms())
    }
    fn new_id(&self) -> String {
        self.id.set(self.id.get() + 1);
        format!("{:08x}", self.id.get())
    }
    fn session(&self, id: &str) -> Option<SessionInfo> {
        self.sessions.iter().find(|s| s.id == id).cloned()
    }
    fn global_write_roles(&self) -> Vec<String> {
        vec!["pm".into()]
    }
    fn audit(&self, l: &str) {
        self.log.borrow_mut().push(l.into());
    }
}

fn setup() -> (Lite, Host) {
    let c = Connection::open_in_memory().unwrap();
    c.execute_batch(SCHEMA).unwrap();
    let s = |id: &str, role: &str| SessionInfo { id: id.into(), agent_id: Some(format!("agent-{id}")), role: role.into() };
    (Lite(c), Host { t: Cell::new(0), id: Cell::new(0), sessions: vec![s("s1", "backend"), s("s2", "pm")], log: RefCell::new(vec![]) })
}

fn frame(db: &Lite, h: &Host, ty: &str, data: serde_json::Value) -> (String, serde_json::Value) {
    let r = api::handle(db, h, ty, &data.to_string()).expect("a notes frame");
    let (t, d) = r.reply.or_else(|| r.push.into_iter().next()).expect("a reply or a push");
    (t, serde_json::from_str(&d).unwrap_or(serde_json::Value::String(d)))
}

#[test]
fn decisions_number_supersede_and_stay_append_only() {
    let (db, h) = setup();
    let (t, d1) = frame(&db, &h, "decision_add", serde_json::json!({"projectId":"p","summary":"use rust","sessionId":"s1"}));
    assert_eq!((t.as_str(), d1["ref"].as_str(), d1["authorRole"].as_str()), ("decision_item", Some("D1"), Some("backend")));
    let (_, d2) = frame(&db, &h, "decision_add", serde_json::json!({"projectId":"p","summary":"use wasm","supersedes":"D1"}));
    assert_eq!(d2["number"], 2);
    let (t, e) = frame(&db, &h, "decision_add", serde_json::json!({"projectId":"p","summary":"again","supersedes":"1"}));
    assert_eq!(t, "error");
    assert!(e.as_str().unwrap().contains("refused: already_superseded"), "{e}");
    let (_, e) = frame(&db, &h, "decision_add", serde_json::json!({"projectId":"q","summary":"x","supersedes":"D1"}));
    assert!(e.as_str().unwrap().contains("decision_not_found"));
    let (_, active) = frame(&db, &h, "decision_list", serde_json::json!({"projectId":"p"}));
    assert_eq!(active.as_array().unwrap().len(), 1);
    let (_, all) = frame(&db, &h, "decision_list", serde_json::json!({"projectId":"p","includeSuperseded":true}));
    assert_eq!(all[0]["supersededBy"], 2);
    assert!(db.0.execute("DELETE FROM decisions", []).is_err(), "append-only trigger");
}

#[test]
fn issues_active_attempt_fix_and_precheck() {
    let (db, h) = setup();
    frame(&db, &h, "issue_open", serde_json::json!({"projectId":"p","summary":"flaky","location":"src/a/b.rs:12","sessionId":"s1"}));
    let (_, i) = frame(&db, &h, "issue_attempt", serde_json::json!({"projectId":"p","summary":"retry","sessionId":"s1"}));
    assert_eq!((i["number"].clone(), i["events"].as_array().unwrap().len()), (1.into(), 1));
    // another session with nothing active opens an implicit issue from its attempt
    let (_, imp) = frame(&db, &h, "issue_attempt", serde_json::json!({"projectId":"p","summary":"first line\nsecond","outcome":"partial","sessionId":"s2"}));
    assert_eq!((imp["number"].clone(), imp["implicit"].clone(), imp["summary"].as_str()), (2.into(), true.into(), Some("first line")));
    let (_, fixed) = frame(&db, &h, "issue_fix", serde_json::json!({"projectId":"p","summary":"done","sessionId":"s1"}));
    assert_eq!(fixed["status"], "fixed");
    let (_, e) = frame(&db, &h, "issue_attempt", serde_json::json!({"projectId":"p","summary":"more","issue":"I1"}));
    assert!(e.as_str().unwrap().contains("already_fixed"));
    let (_, e) = frame(&db, &h, "issue_fix", serde_json::json!({"projectId":"p","summary":"x"}));
    assert!(e.as_str().unwrap().contains("no_active_issue"));
    let (_, pre) = frame(&db, &h, "issue_precheck", serde_json::json!({"projectId":"p","path":"src/a"}));
    assert_eq!(pre.as_array().unwrap().len(), 1);
    let (_, open) = frame(&db, &h, "issue_list", serde_json::json!({"projectId":"p"}));
    assert_eq!(open.as_array().unwrap().len(), 1);
}

#[test]
fn memory_modes_scopes_search_and_global() {
    let (db, h) = setup();
    let save = |v: serde_json::Value| frame(&db, &h, "memory_save", v);
    let (t, n) = save(serde_json::json!({"projectId":"p","name":"alpha","description":"first","body":"hello world"}));
    assert_eq!((t.as_str(), n["id"].as_str()), ("memory_note", Some("note-00000001")));
    save(serde_json::json!({"projectId":"p","name":"alpha","mode":"append","content":"more"}));
    save(serde_json::json!({"projectId":"p","name":"alpha","mode":"edit","find":"hello","content":"howdy"}));
    let (_, got) = frame(&db, &h, "memory_get", serde_json::json!({"projectId":"p","name":"alpha"}));
    assert_eq!(got["body"], "howdy world\n\nmore");
    let (_, e) = save(serde_json::json!({"projectId":"p","name":"alpha","mode":"edit","find":"o","content":"0"}));
    assert!(e.as_str().unwrap().contains("anchor_ambiguous"));
    let (_, e) = save(serde_json::json!({"projectId":"p","name":"alpha","mode":"edit","find":"zzz","content":"0"}));
    assert!(e.as_str().unwrap().contains("anchor_not_found"));
    let (_, e) = save(serde_json::json!({"projectId":"p","name":"../x","description":"d","body":"b"}));
    assert!(e.as_str().unwrap().contains("path"));
    // role scope: a worker may not address another role; the pm may
    let (_, e) = save(serde_json::json!({"projectId":"p","name":"r","description":"d","body":"b","scope":"role","role":"qa","sessionId":"s1"}));
    assert!(e.as_str().unwrap().contains("may only address its own role notes"));
    let (t, _) = save(serde_json::json!({"projectId":"p","name":"r","description":"d","body":"b","scope":"role","role":"qa","sessionId":"s2"}));
    assert_eq!(t, "memory_note");
    // global: only the configured writer roles; project reads fall back to global
    let (_, e) = save(serde_json::json!({"projectId":"p","name":"g","description":"d","body":"shared","scope":"global","sessionId":"s1"}));
    assert!(e.as_str().unwrap().contains("may not write global notes"));
    save(serde_json::json!({"projectId":"p","name":"g","description":"d","body":"shared","scope":"global","sessionId":"s2"}));
    let (_, g) = frame(&db, &h, "memory_get", serde_json::json!({"projectId":"other","name":"g"}));
    assert_eq!(g["body"], "shared");
    // search ranks a name match above a body match; LIKE wildcards are literal
    save(serde_json::json!({"projectId":"p","name":"zeta","description":"d","body":"mentions howdy here"}));
    save(serde_json::json!({"projectId":"p","name":"howdy-note","description":"d","body":"x"}));
    let (_, hits) = frame(&db, &h, "memory_search", serde_json::json!({"projectId":"p","query":"HOWDY"}));
    let names: Vec<_> = hits.as_array().unwrap().iter().map(|x| x["name"].as_str().unwrap().to_string()).collect();
    assert_eq!(names, vec!["howdy-note", "alpha", "zeta"]);
    let (_, none) = frame(&db, &h, "memory_search", serde_json::json!({"projectId":"p","query":"%"}));
    assert!(none.as_array().unwrap().is_empty());
    // delete answers the index
    let (t, idx) = frame(&db, &h, "memory_delete", serde_json::json!({"projectId":"p","name":"zeta"}));
    assert_eq!(t, "memory_notes");
    assert!(idx.as_array().unwrap().iter().all(|x| x["name"] != "zeta"));
}

#[test]
fn tool_matches_the_cli_actions() {
    let (db, h) = setup();
    let ctx = ToolCtx { session_id: Some("s1".into()), role: Some("backend".into()), project_id: Some("p".into()) };
    let call = |a: serde_json::Value| api::call_tool(&db, &h, &a.to_string(), &ctx);
    assert_eq!(call(serde_json::json!({"action":"write","name":"n","description":"d","body":"B","scope":"session"})).unwrap(), "saved session note 'n'");
    assert_eq!(call(serde_json::json!({"action":"show","name":"n"})).unwrap(), "SCOPE: session\nNAME: n\n\nB");
    assert!(call(serde_json::json!({"action":"show","name":"missing"})).is_err());
    assert!(call(serde_json::json!({"action":"write","name":"n"})).unwrap_err().contains("needs description"));
    assert!(call(serde_json::json!({"action":"nope"})).unwrap_err().contains("action must be one of"));
    assert_eq!(call(serde_json::json!({"action":"delete","name":"n","scope":"session"})).unwrap(), "deleted session note 'n'");
}

#[test]
fn iso_matches_instant_to_string() {
    assert_eq!(infantry_notes::iso(0), "1970-01-01T00:00:00.000Z");
    assert_eq!(infantry_notes::iso(1_700_000_000_123), "2023-11-14T22:13:20.123Z");
}
