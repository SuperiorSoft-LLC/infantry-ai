// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The usage meter: `usage-meter.db`, its own SQLite file (same schema as the Kotlin daemon's, so an existing file
//! opens as is). Per model request a row, per compaction an event, per ended run a row; weekly retention rolls old
//! runs into one row per day per (role, provider, model, source).
//!
//! Its whole surface: [`UsageMeter::on_turn`] / [`UsageMeter::on_stdout`] / [`UsageMeter::on_exit`] (feed it the
//! session events), [`UsageMeter::report`] (the `usage_meter` frame body) and [`UsageMeter::prune`].
use std::collections::{BTreeMap, HashMap};
use std::path::Path;
use std::sync::{Arc, Mutex};

use chrono::{Datelike, Duration as CDuration, NaiveDate, TimeZone, Utc};
use rusqlite::{params, Connection};
use serde::{Deserialize, Serialize};

use crate::stream::{StreamEvent, StreamUsage, Totals, Turn};
use crate::util::{local_day, Clock};

pub const DEFAULT_KEEP_WEEKS: i32 = 4;
pub const KEEP_WEEKS_ENV: &str = "INFANTRY_USAGE_METER_KEEP_WEEKS";
pub const REQUESTS_IN_REPORT: i64 = 600;
pub const EVENTS_IN_REPORT: i64 = 100;
pub const PRUNE_EVERY_MS: i64 = 6 * 3_600_000;

/// The session a stream belongs to (whatever the daemon's session record holds).
#[derive(Debug, Clone, Default, PartialEq)]
pub struct SessionInfo {
    pub id: String,
    pub run_count: i64,
    pub ticket_id: Option<String>,
    pub role: Option<String>,
    pub provider: String,
    pub model: Option<String>,
}

/// What started a turn (the message origin, reduced to what the meter needs).
#[derive(Debug, Clone, PartialEq)]
pub enum TurnOrigin {
    Owner,
    Agent,
    /// Outside mail; `sender` is its label (`trigger:...` = a trigger checkpoint).
    External { sender: String },
    /// A system message of `kind` (`mail`, `scheduler`, ...).
    System { kind: String },
}

/// Who caused a run: `trigger`, `mail`, else `pm` or `worker`.
pub fn source_of(role: Option<&str>, origin: &TurnOrigin) -> &'static str {
    match origin {
        TurnOrigin::External { sender } if sender.starts_with("trigger:") => "trigger",
        TurnOrigin::Agent | TurnOrigin::External { .. } => "mail",
        TurnOrigin::System { kind } if kind == "mail" => "mail",
        TurnOrigin::System { kind } if kind == "scheduler" => "trigger",
        _ if role.is_some_and(|r| r.eq_ignore_ascii_case("pm")) => "pm",
        _ => "worker",
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Row {
    pub day: String,
    pub role: String,
    pub provider: String,
    pub model: String,
    pub source: String,
    pub runs: i64,
    pub turns: i64,
    pub input: i64,
    pub output: i64,
    pub cache_read: i64,
    pub cache_write: i64,
    pub context_sum: i64,
    pub cost: Option<f64>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RequestRow {
    pub ts: i64,
    pub session_id: String,
    pub ticket_id: Option<String>,
    pub role: String,
    pub harness: String,
    pub model: Option<String>,
    pub source: String,
    pub input: Option<i64>,
    pub output: Option<i64>,
    pub cache_read: Option<i64>,
    pub cache_write: Option<i64>,
    pub context: Option<i64>,
    pub cost: Option<f64>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EventRow {
    pub ts: i64,
    pub session_id: String,
    pub ticket_id: Option<String>,
    pub harness: String,
    pub kind: String,
    pub trigger: Option<String>,
    pub tokens_before: Option<i64>,
}

/// USD per million tokens for models whose id contains `match` (case-insensitive); the first match wins.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Price {
    #[serde(rename = "match")]
    pub match_: String,
    pub input: f64,
    pub output: f64,
    pub cache_read: f64,
    pub cache_write: f64,
}

/// The shipped defaults (`usage-prices.json` overrides them).
pub fn default_prices() -> Vec<Price> {
    let p = |m: &str, i, o, r, w| Price { match_: m.into(), input: i, output: o, cache_read: r, cache_write: w };
    vec![p("haiku", 1.0, 5.0, 0.10, 1.25), p("sonnet", 3.0, 15.0, 0.30, 3.75), p("opus", 5.0, 25.0, 0.50, 6.25)]
}

/// `usage-prices.json` in the state dir (a list of [`Price`]), else the defaults.
pub fn prices_from(state_dir: &Path) -> Vec<Price> {
    std::fs::read_to_string(state_dir.join("usage-prices.json")).ok().and_then(|t| serde_json::from_str(&t).ok()).unwrap_or_else(default_prices)
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Report {
    pub rows: Vec<Row>,
    pub keep_weeks: i32,
    pub generated_at: i64,
    pub requests: Vec<RequestRow>,
    pub events: Vec<EventRow>,
    pub prices: Vec<Price>,
}

struct Pending {
    source: String,
    rec: SessionInfo,
    acc: StreamUsage,
}

pub struct UsageMeter {
    conn: Mutex<Connection>,
    pending: Mutex<HashMap<String, Pending>>,
    keep_weeks: Arc<dyn Fn() -> i32 + Send + Sync>,
    clock: Clock,
    prices: Arc<dyn Fn() -> Vec<Price> + Send + Sync>,
    on_recorded: Mutex<Option<Arc<dyn Fn() + Send + Sync>>>,
}

fn sql(e: rusqlite::Error) -> String {
    e.to_string()
}

impl UsageMeter {
    pub fn keep_weeks_from_env(env: &crate::credentials::Env) -> i32 {
        env(KEEP_WEEKS_ENV).and_then(|v| v.trim().parse().ok()).filter(|n| (0..=520).contains(n)).unwrap_or(DEFAULT_KEEP_WEEKS)
    }

    pub fn open(file: &Path, keep_weeks: Arc<dyn Fn() -> i32 + Send + Sync>, clock: Clock, prices: Arc<dyn Fn() -> Vec<Price> + Send + Sync>) -> Result<Self, String> {
        if let Some(d) = file.parent() {
            std::fs::create_dir_all(d).map_err(|e| e.to_string())?;
        }
        Self::with_conn(Connection::open(file).map_err(sql)?, keep_weeks, clock, prices)
    }

    pub fn in_memory(clock: Clock) -> Self {
        Self::with_conn(Connection::open_in_memory().expect("sqlite in memory"), Arc::new(|| DEFAULT_KEEP_WEEKS), clock, Arc::new(default_prices)).expect("schema")
    }

    fn with_conn(conn: Connection, keep_weeks: Arc<dyn Fn() -> i32 + Send + Sync>, clock: Clock, prices: Arc<dyn Fn() -> Vec<Price> + Send + Sync>) -> Result<Self, String> {
        let _ = conn.pragma_update(None, "journal_mode", "WAL");
        conn.execute_batch(
            "CREATE TABLE IF NOT EXISTS meter_run (
                run_id TEXT PRIMARY KEY, session_id TEXT NOT NULL, ended_at INTEGER NOT NULL,
                role TEXT NOT NULL, provider TEXT NOT NULL, model TEXT NOT NULL, source TEXT NOT NULL,
                turns INTEGER NOT NULL, input INTEGER NOT NULL, output INTEGER NOT NULL,
                cache_read INTEGER NOT NULL, cache_write INTEGER NOT NULL, context_sum INTEGER NOT NULL, cost REAL);
             CREATE INDEX IF NOT EXISTS meter_run_ended ON meter_run(ended_at);
             CREATE TABLE IF NOT EXISTS meter_request (
                id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, session_id TEXT NOT NULL, run_no INTEGER NOT NULL,
                ticket_id TEXT, role TEXT NOT NULL, harness TEXT NOT NULL, model TEXT, source TEXT NOT NULL,
                input INTEGER, output INTEGER, cache_read INTEGER, cache_write INTEGER, context INTEGER, cost REAL);
             CREATE INDEX IF NOT EXISTS meter_request_ts ON meter_request(ts);
             CREATE TABLE IF NOT EXISTS meter_event (
                id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, session_id TEXT NOT NULL, ticket_id TEXT,
                harness TEXT NOT NULL, kind TEXT NOT NULL, trigger_kind TEXT, tokens_before INTEGER);
             CREATE TABLE IF NOT EXISTS meter_day (
                day TEXT NOT NULL, role TEXT NOT NULL, provider TEXT NOT NULL, model TEXT NOT NULL, source TEXT NOT NULL,
                runs INTEGER NOT NULL, turns INTEGER NOT NULL, input INTEGER NOT NULL, output INTEGER NOT NULL,
                cache_read INTEGER NOT NULL, cache_write INTEGER NOT NULL, context_sum INTEGER NOT NULL, cost REAL,
                PRIMARY KEY (day, role, provider, model, source));",
        )
        .map_err(sql)?;
        for col in ["thinking INTEGER", "msg_id TEXT"] {
            let _ = conn.execute(&format!("ALTER TABLE meter_request ADD COLUMN {col}"), []);
        }
        Ok(UsageMeter { conn: Mutex::new(conn), pending: Mutex::new(HashMap::new()), keep_weeks, clock, prices, on_recorded: Mutex::new(None) })
    }

    /// Fired after every recorded turn / compaction (the daemon pushes a `usage_meter` frame from it).
    pub fn set_on_recorded(&self, f: Arc<dyn Fn() + Send + Sync>) {
        *self.on_recorded.lock().unwrap_or_else(|p| p.into_inner()) = Some(f);
    }

    fn conn(&self) -> std::sync::MutexGuard<'_, Connection> {
        self.conn.lock().unwrap_or_else(|p| p.into_inner())
    }

    /// A new (not mid-turn) turn started on `session`.
    pub fn on_turn(&self, session: &SessionInfo, origin: &TurnOrigin) {
        let source = source_of(session.role.as_deref(), origin).to_string();
        self.pending.lock().unwrap_or_else(|p| p.into_inner()).insert(session.id.clone(), Pending { source, rec: session.clone(), acc: StreamUsage::new() });
    }

    /// One stdout line. A line seen with no turn before it (a wake of a kept process) still counts.
    pub fn on_stdout(&self, session: &SessionInfo, line: &str) {
        if line.len() < 4 || !(line.contains("usage") || line.contains("tokens") || line.contains("compact")) {
            return;
        }
        let (events, source, rec) = {
            let mut pending = self.pending.lock().unwrap_or_else(|p| p.into_inner());
            let p = pending.entry(session.id.clone()).or_insert_with(|| Pending {
                source: source_of(session.role.as_deref(), &TurnOrigin::Owner).into(),
                rec: session.clone(),
                acc: StreamUsage::new(),
            });
            (p.acc.accept(line), p.source.clone(), p.rec.clone())
        };
        if events.is_empty() {
            return;
        }
        for e in events {
            let r = match e {
                StreamEvent::Turn(t) => self.record_turn(&rec, &source, &t),
                StreamEvent::OutputRaised { msg_id, output } => self.update_output(&rec.id, &msg_id, output),
                StreamEvent::Compaction { trigger, pre_tokens } => self.record_compaction(&rec, &trigger, pre_tokens),
            };
            if let Err(e) = r {
                eprintln!("[infantry] usage meter did not store a record of {}: {e}", rec.id);
            }
        }
        if let Some(f) = self.on_recorded.lock().unwrap_or_else(|p| p.into_inner()).clone() {
            f();
        }
    }

    /// The session's process ended: its run is stored. Returns the run's totals (the operator budget counts them).
    pub fn on_exit(&self, session: &SessionInfo) -> Option<Totals> {
        let p = self.pending.lock().unwrap_or_else(|p| p.into_inner()).remove(&session.id)?;
        let t = p.acc.totals()?;
        if let Err(e) = self.insert_run(session, &p.source, &t, (self.clock)()) {
            eprintln!("[infantry] usage meter did not store run of {}: {e}", session.id);
        }
        Some(t)
    }

    fn record_turn(&self, rec: &SessionInfo, source: &str, t: &Turn) -> Result<(), String> {
        let model = rec.model.clone().or_else(|| (!t.model.is_empty()).then(|| t.model.clone()));
        self.conn()
            .execute(
                "INSERT INTO meter_request (ts, session_id, run_no, ticket_id, role, harness, model, source, input, output, cache_read, cache_write, context, cost, thinking, msg_id)
                 VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,?12,?13,?14,?15,?16)",
                params![
                    (self.clock)(), rec.id, rec.run_count, rec.ticket_id, rec.role.as_deref().unwrap_or("none"), rec.provider, model, source,
                    t.input, t.output, t.cache_read, t.cache_write, t.context(), t.cost_usd, t.thinking, t.msg_id
                ],
            )
            .map(drop)
            .map_err(sql)
    }

    fn update_output(&self, session_id: &str, msg_id: &str, output: i64) -> Result<(), String> {
        self.conn()
            .execute("UPDATE meter_request SET output=?1 WHERE session_id=?2 AND msg_id=?3 AND (output IS NULL OR output<?1)", params![output, session_id, msg_id])
            .map(drop)
            .map_err(sql)
    }

    fn record_compaction(&self, rec: &SessionInfo, trigger: &str, pre: Option<i64>) -> Result<(), String> {
        self.conn()
            .execute(
                "INSERT INTO meter_event (ts, session_id, ticket_id, harness, kind, trigger_kind, tokens_before) VALUES (?1,?2,?3,?4,'compaction',?5,?6)",
                params![(self.clock)(), rec.id, rec.ticket_id, rec.provider, trigger, pre],
            )
            .map(drop)
            .map_err(sql)
    }

    pub fn insert_run(&self, rec: &SessionInfo, source: &str, t: &Totals, ended_at: i64) -> Result<(), String> {
        let model = rec.model.clone().or_else(|| (!t.model.is_empty()).then(|| t.model.clone())).unwrap_or_else(|| "default".into());
        self.conn()
            .execute(
                "INSERT OR REPLACE INTO meter_run VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,?12,?13,?14)",
                params![
                    format!("{}:{}", rec.id, rec.run_count), rec.id, ended_at, rec.role.as_deref().unwrap_or("none"), rec.provider, model, source,
                    t.turns, t.input, t.output, t.cache_read, t.cache_write, t.context_sum, t.cost_usd
                ],
            )
            .map(drop)
            .map_err(sql)
    }

    /// Weekly retention (weeks start Monday 00:00 UTC): runs before last week's Monday roll into `meter_day`, older
    /// requests and events go, and day rows older than `keep_weeks` before that cutoff go. One transaction; idempotent.
    /// Returns (runs rolled up, day rows deleted).
    pub fn prune(&self) -> Result<(usize, usize), String> {
        let today = Utc.timestamp_millis_opt((self.clock)()).single().unwrap_or_else(Utc::now).date_naive();
        let monday = today - CDuration::days(today.weekday().num_days_from_monday() as i64);
        let cutoff_day: NaiveDate = monday - CDuration::weeks(1);
        let cutoff = cutoff_day.and_hms_opt(0, 0, 0).expect("midnight").and_utc().timestamp_millis();
        let drop_before = (cutoff_day - CDuration::weeks((self.keep_weeks)().max(0) as i64)).format("%Y-%m-%d").to_string();
        let mut conn = self.conn();
        let tx = conn.transaction().map_err(sql)?;
        tx.execute(
            "INSERT INTO meter_day SELECT strftime('%Y-%m-%d', ended_at/1000, 'unixepoch') d, role, provider, model, source,
                COUNT(*), SUM(turns), SUM(input), SUM(output), SUM(cache_read), SUM(cache_write), SUM(context_sum), SUM(cost)
                FROM meter_run WHERE ended_at < ?1 GROUP BY d, role, provider, model, source
                ON CONFLICT(day, role, provider, model, source) DO UPDATE SET
                runs=runs+excluded.runs, turns=turns+excluded.turns, input=input+excluded.input, output=output+excluded.output,
                cache_read=cache_read+excluded.cache_read, cache_write=cache_write+excluded.cache_write,
                context_sum=context_sum+excluded.context_sum,
                cost=CASE WHEN cost IS NULL THEN excluded.cost WHEN excluded.cost IS NULL THEN cost ELSE cost+excluded.cost END",
            params![cutoff],
        )
        .map_err(sql)?;
        let runs = tx.execute("DELETE FROM meter_run WHERE ended_at < ?1", params![cutoff]).map_err(sql)?;
        tx.execute("DELETE FROM meter_request WHERE ts < ?1", params![cutoff]).map_err(sql)?;
        tx.execute("DELETE FROM meter_event WHERE ts < ?1", params![cutoff]).map_err(sql)?;
        let days = tx.execute("DELETE FROM meter_day WHERE day < ?1", params![drop_before]).map_err(sql)?;
        tx.commit().map_err(sql)?;
        Ok((runs, days))
    }

    /// One row per day per (role, provider, model, source): ended runs by local day, the daily rollups, and runs still in
    /// flight (counted today, so the pane never lags until the process exits).
    pub fn report(&self) -> Result<Report, String> {
        let now = (self.clock)();
        let mut rows: Vec<Row> = Vec::new();
        {
            let conn = self.conn();
            let mut st = conn
                .prepare("SELECT ended_at, role, provider, model, source, turns, input, output, cache_read, cache_write, context_sum, cost FROM meter_run")
                .map_err(sql)?;
            let it = st
                .query_map([], |r| {
                    Ok(Row {
                        day: local_day(r.get(0)?),
                        role: r.get(1)?,
                        provider: r.get(2)?,
                        model: r.get(3)?,
                        source: r.get(4)?,
                        runs: 1,
                        turns: r.get(5)?,
                        input: r.get(6)?,
                        output: r.get(7)?,
                        cache_read: r.get(8)?,
                        cache_write: r.get(9)?,
                        context_sum: r.get(10)?,
                        cost: r.get(11)?,
                    })
                })
                .map_err(sql)?;
            for r in it {
                rows.push(r.map_err(sql)?);
            }
            let mut st = conn
                .prepare("SELECT day, role, provider, model, source, runs, turns, input, output, cache_read, cache_write, context_sum, cost FROM meter_day")
                .map_err(sql)?;
            let it = st
                .query_map([], |r| {
                    Ok(Row {
                        day: r.get(0)?,
                        role: r.get(1)?,
                        provider: r.get(2)?,
                        model: r.get(3)?,
                        source: r.get(4)?,
                        runs: r.get(5)?,
                        turns: r.get(6)?,
                        input: r.get(7)?,
                        output: r.get(8)?,
                        cache_read: r.get(9)?,
                        cache_write: r.get(10)?,
                        context_sum: r.get(11)?,
                        cost: r.get(12)?,
                    })
                })
                .map_err(sql)?;
            for r in it {
                rows.push(r.map_err(sql)?);
            }
        }
        rows.sort_by(|a, b| a.day.cmp(&b.day));
        let mut merged: BTreeMap<(String, String, String, String, String), Row> = BTreeMap::new();
        for r in rows {
            let k = (r.day.clone(), r.role.clone(), r.provider.clone(), r.model.clone(), r.source.clone());
            match merged.get_mut(&k) {
                None => {
                    merged.insert(k, r);
                }
                Some(o) => {
                    o.runs += r.runs;
                    o.turns += r.turns;
                    o.input += r.input;
                    o.output += r.output;
                    o.cache_read += r.cache_read;
                    o.cache_write += r.cache_write;
                    o.context_sum += r.context_sum;
                    o.cost = match (o.cost, r.cost) {
                        (Some(a), Some(b)) => Some(a + b),
                        (a, b) => a.or(b),
                    };
                }
            }
        }
        let mut rows: Vec<Row> = merged.into_values().collect();
        let today = local_day(now);
        for p in self.pending.lock().unwrap_or_else(|p| p.into_inner()).values() {
            let Some(t) = p.acc.totals() else { continue };
            rows.push(Row {
                day: today.clone(),
                role: p.rec.role.clone().unwrap_or_else(|| "none".into()),
                provider: p.rec.provider.clone(),
                model: p.rec.model.clone().or_else(|| (!t.model.is_empty()).then(|| t.model.clone())).unwrap_or_else(|| "default".into()),
                source: p.source.clone(),
                runs: 0,
                turns: t.turns,
                input: t.input,
                output: t.output,
                cache_read: t.cache_read,
                cache_write: t.cache_write,
                context_sum: t.context_sum,
                cost: t.cost_usd,
            });
        }
        Ok(Report { rows, keep_weeks: (self.keep_weeks)(), generated_at: now, requests: self.requests()?, events: self.events()?, prices: (self.prices)() })
    }

    fn requests(&self) -> Result<Vec<RequestRow>, String> {
        self.request_rows(0, REQUESTS_IN_REPORT as i64)
    }

    /// The recorded requests at or after `since` (ms), oldest first, at most `limit` (the newest kept).
    pub fn requests_since(&self, since: i64, limit: i64) -> Result<Vec<RequestRow>, String> {
        self.request_rows(since, limit)
    }

    fn request_rows(&self, since: i64, limit: i64) -> Result<Vec<RequestRow>, String> {
        let conn = self.conn();
        let mut st = conn
            .prepare("SELECT ts, session_id, ticket_id, role, harness, model, source, input, output, cache_read, cache_write, context, cost FROM meter_request WHERE ts >= ?1 ORDER BY id DESC LIMIT ?2")
            .map_err(sql)?;
        let mut out: Vec<RequestRow> = st
            .query_map(params![since, limit], |r| {
                Ok(RequestRow {
                    ts: r.get(0)?,
                    session_id: r.get(1)?,
                    ticket_id: r.get(2)?,
                    role: r.get(3)?,
                    harness: r.get(4)?,
                    model: r.get(5)?,
                    source: r.get(6)?,
                    input: r.get(7)?,
                    output: r.get(8)?,
                    cache_read: r.get(9)?,
                    cache_write: r.get(10)?,
                    context: r.get(11)?,
                    cost: r.get(12)?,
                })
            })
            .map_err(sql)?
            .collect::<Result<_, _>>()
            .map_err(sql)?;
        out.reverse();
        Ok(out)
    }

    fn events(&self) -> Result<Vec<EventRow>, String> {
        let conn = self.conn();
        let mut st = conn
            .prepare("SELECT ts, session_id, ticket_id, harness, kind, trigger_kind, tokens_before FROM meter_event ORDER BY id DESC LIMIT ?1")
            .map_err(sql)?;
        let mut out: Vec<EventRow> = st
            .query_map(params![EVENTS_IN_REPORT], |r| {
                Ok(EventRow { ts: r.get(0)?, session_id: r.get(1)?, ticket_id: r.get(2)?, harness: r.get(3)?, kind: r.get(4)?, trigger: r.get(5)?, tokens_before: r.get(6)? })
            })
            .map_err(sql)?
            .collect::<Result<_, _>>()
            .map_err(sql)?;
        out.reverse();
        Ok(out)
    }

}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicI64, Ordering};

    #[test]
    fn turns_requests_runs_and_weekly_prune() {
        let now = Arc::new(AtomicI64::new(1_800_000_000_000));
        let n = now.clone();
        let m = UsageMeter::in_memory(Arc::new(move || n.load(Ordering::SeqCst)));
        let s = SessionInfo { id: "s1".into(), run_count: 1, role: Some("pm".into()), provider: "claude".into(), ..Default::default() };
        m.on_turn(&s, &TurnOrigin::Owner);
        let line = r#"{"type":"assistant","message":{"id":"a","model":"claude-opus","usage":{"input_tokens":3,"output_tokens":1}}}"#;
        m.on_stdout(&s, line);
        m.on_stdout(&s, &line.replace("\"output_tokens\":1", "\"output_tokens\":4"));
        let r = m.report().unwrap();
        assert_eq!((r.requests.len(), r.requests[0].output, r.rows[0].runs, r.rows[0].source.as_str()), (1, Some(4), 0, "pm"));
        assert_eq!(m.on_exit(&s).unwrap().output, 4);
        let r = m.report().unwrap();
        assert_eq!((r.rows.len(), r.rows[0].runs, r.rows[0].model.as_str()), (1, 1, "claude-opus"));
        now.fetch_add(30 * 86_400_000, Ordering::SeqCst);
        assert_eq!(m.prune().unwrap().0, 1);
        let r = m.report().unwrap();
        assert!(r.requests.is_empty() && r.rows[0].runs == 1, "rolled into meter_day");
        assert_eq!(source_of(None, &TurnOrigin::External { sender: "trigger:nightly".into() }), "trigger");
    }
}
