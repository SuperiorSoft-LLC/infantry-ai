// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Pods (Kotlin `Orchestrator` + `PodSpec` + `PodValidator` + `RetryFeedback`): a pod is a DAG of nodes, each node a
//! prompt run as an unattended session. Ready nodes start up to the pod's concurrency; a node whose upstream failed,
//! was cancelled or skipped is SKIPPED; a FAILED attempt is retried up to `maxAttempts` with the previous attempt's
//! failure fed back; a kill is a decision, never retried. A pod whose nodes all SUCCEEDED is CONVERGED and its nodes
//! wait for review (APPROVED / REJECTED). A daemon restart leaves every open pod INTERRUPTED.
//!
//! Frames: `pod_submit` (a spec) / `pod_query` (`<podId>`; empty = every recent pod) / `pod_cancel` (`<podId> [force]`)
//! / `pod_list` -> `pods` / `pod_review` (`{nodeId, decision}`) answer `pod` (the PodStatus); a change pushes `pod`.
//! Session events are handed to one worker thread: a session never starts from inside a session callback.
use infantry_core::db::Db;
use infantry_core::frames::{Envelope, FrameCtx, FrameUnit, Refusal};
use infantry_protocol::session::SessionRecord;
use rusqlite::{params, OptionalExtension};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::collections::{BTreeMap, HashMap, HashSet};
use std::sync::{mpsc, Arc, Mutex};

pub mod frames {
    pub const SUBMIT: &str = "pod_submit";
    pub const QUERY: &str = "pod_query";
    pub const CANCEL: &str = "pod_cancel";
    pub const LIST: &str = "pod_list";
    pub const REVIEW: &str = "pod_review";
    pub const POD: &str = "pod";
    pub const PODS: &str = "pods";
    pub const ALL: &[&str] = &[SUBMIT, QUERY, CANCEL, LIST, REVIEW];
}

/// Pods a list (and an empty `pod_query`) returns, newest first.
pub const LIST_MAX: i64 = 50;
pub const NODES_MAX: usize = 100;
pub const MAX_ATTEMPTS: i64 = 10;
pub const MAX_CONCURRENCY: i64 = 16;
pub const NAME_MAX: usize = 64;
pub const RETRY_MARKER: &str = "INFANTRY RETRY FEEDBACK";
pub const RETRY_ERROR_CHARS: usize = 2_000;
const SKIP_CAUSES: [&str; 3] = ["FAILED", "CANCELLED", "SKIPPED"];

fn one() -> i64 {
    1
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NodeSpec {
    pub name: String,
    pub prompt: String,
    #[serde(default)]
    pub depends_on: Vec<String>,
    #[serde(default = "one")]
    pub max_attempts: i64,
    #[serde(default)]
    pub agent_id: Option<String>,
    #[serde(default)]
    pub provider: Option<String>,
    #[serde(default)]
    pub model: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PodSpec {
    pub goal: String,
    pub nodes: Vec<NodeSpec>,
    #[serde(default = "one")]
    pub max_concurrency: i64,
    #[serde(default)]
    pub project_id: Option<String>,
    #[serde(default)]
    pub working_dir: Option<String>,
}

/// Refuses a spec nothing can run (Kotlin `PodValidator`).
pub fn validate(s: &PodSpec) -> Result<(), String> {
    if s.goal.trim().is_empty() {
        return Err("a pod needs a goal".into());
    }
    if s.nodes.is_empty() {
        return Err("a pod needs at least one node".into());
    }
    if s.nodes.len() > NODES_MAX {
        return Err(format!("a pod has at most {NODES_MAX} nodes"));
    }
    if !(1..=MAX_CONCURRENCY).contains(&s.max_concurrency) {
        return Err(format!("maxConcurrency must be 1..{MAX_CONCURRENCY}"));
    }
    let mut names = HashSet::new();
    for n in &s.nodes {
        if n.name.trim().is_empty() || n.name.len() > NAME_MAX {
            return Err(format!("a node name must be 1..{NAME_MAX} characters"));
        }
        if !names.insert(n.name.as_str()) {
            return Err(format!("duplicate node name '{}'", n.name));
        }
        if n.prompt.trim().is_empty() {
            return Err(format!("node '{}' has a blank prompt", n.name));
        }
        if !(1..=MAX_ATTEMPTS).contains(&n.max_attempts) {
            return Err(format!("node '{}': maxAttempts must be 1..{MAX_ATTEMPTS}", n.name));
        }
    }
    for n in &s.nodes {
        for d in &n.depends_on {
            if d == &n.name {
                return Err(format!("node '{}' depends on itself", n.name));
            }
            if !names.contains(d.as_str()) {
                return Err(format!("node '{}' depends on unknown node '{d}'", n.name));
            }
        }
    }
    // Kahn: whatever never becomes ready is on a cycle.
    let mut done: HashSet<&str> = HashSet::new();
    loop {
        let next: Vec<&str> = s.nodes.iter().filter(|n| !done.contains(n.name.as_str()) && n.depends_on.iter().all(|d| done.contains(d.as_str()))).map(|n| n.name.as_str()).collect();
        if next.is_empty() {
            break;
        }
        done.extend(next);
    }
    if done.len() < s.nodes.len() {
        let stuck: Vec<&str> = s.nodes.iter().map(|n| n.name.as_str()).filter(|n| !done.contains(n)).collect();
        return Err(format!("dependency cycle; unschedulable nodes: {}", stuck.join(", ")));
    }
    Ok(())
}

#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct PodRecord {
    pub id: String,
    pub goal: String,
    pub state: String,
    pub max_concurrency: i64,
    pub created_at: i64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub ended_at: Option<i64>,
}

#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct NodeRecord {
    pub id: String,
    pub pod_id: String,
    pub name: String,
    pub spec_index: i64,
    pub prompt: String,
    pub state: String,
    pub max_attempts: i64,
    pub attempts: i64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub review_state: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub detail: Option<String>,
    pub created_at: i64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub ended_at: Option<i64>,
}

#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct RunRecord {
    pub id: String,
    pub session_id: String,
    pub provider: String,
    pub working_dir: String,
    pub prompt: String,
    pub state: String,
    pub started_at: i64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub ended_at: Option<i64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub exit_code: Option<i64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub pod_id: Option<String>,
}

/// One session a node attempt started: its id, harness and directory.
#[derive(Debug, Clone)]
pub struct Started {
    pub session_id: String,
    pub provider: String,
    pub working_dir: String,
}

/// What a node attempt asks the session manager for.
#[derive(Debug, Clone)]
pub struct Launch {
    pub prompt: String,
    pub project_id: Option<String>,
    pub working_dir: Option<String>,
    pub agent_id: Option<String>,
    pub provider: Option<String>,
    pub model: Option<String>,
}

/// The session manager as pods reach it.
pub trait PodSessions: Send + Sync {
    fn start(&self, l: &Launch) -> Result<Started, String>;
    fn kill(&self, session_id: &str, force: bool);
    /// Tells every client a pod changed.
    fn push(&self, status: &Value);
}

struct Live {
    pod: String,
    node: String,
    run: String,
}

#[derive(Default)]
struct Inner {
    by_session: HashMap<String, Live>,
    by_node: HashMap<String, String>,
    launches: HashMap<String, PodSpec>,
    cancel_requested: HashSet<String>,
}

pub struct Pods {
    db: Arc<Db>,
    sessions: Arc<dyn PodSessions>,
    inner: Mutex<Inner>,
    clock: fn() -> i64,
}

fn now_ms() -> i64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0)
}

fn id() -> String {
    uuid::Uuid::new_v4().to_string()
}

type R<T> = Result<T, String>;

fn db_err(e: impl std::fmt::Display) -> String {
    e.to_string()
}

fn pod_row(r: &rusqlite::Row) -> rusqlite::Result<PodRecord> {
    Ok(PodRecord { id: r.get(0)?, goal: r.get(1)?, state: r.get(2)?, max_concurrency: r.get(3)?, created_at: r.get(4)?, ended_at: r.get(5)? })
}
const POD_COLS: &str = "id, goal, state, max_concurrency, created_at, ended_at";

fn node_row(r: &rusqlite::Row) -> rusqlite::Result<NodeRecord> {
    Ok(NodeRecord {
        id: r.get(0)?,
        pod_id: r.get(1)?,
        name: r.get(2)?,
        spec_index: r.get(3)?,
        prompt: r.get(4)?,
        state: r.get(5)?,
        max_attempts: r.get(6)?,
        attempts: r.get(7)?,
        review_state: r.get(8)?,
        detail: r.get(9)?,
        created_at: r.get(10)?,
        ended_at: r.get(11)?,
    })
}
const NODE_COLS: &str = "id, pod_id, name, spec_index, prompt, state, max_attempts, attempts, review_state, detail, created_at, ended_at";

impl Pods {
    pub fn new(db: Arc<Db>, sessions: Arc<dyn PodSessions>) -> Arc<Pods> {
        Arc::new(Pods { db, sessions, inner: Mutex::new(Inner::default()), clock: now_ms })
    }

    // ---- store ------------------------------------------------------------------------------------------------

    fn pod(&self, id: &str) -> Option<PodRecord> {
        self.db.read(|c| c.query_row(&format!("SELECT {POD_COLS} FROM pods WHERE id = ?1"), [id], pod_row).optional()).ok().flatten()
    }

    fn nodes(&self, pod: &str) -> Vec<NodeRecord> {
        self.db
            .read(|c| {
                let mut st = c.prepare(&format!("SELECT {NODE_COLS} FROM pod_nodes WHERE pod_id = ?1 ORDER BY spec_index"))?;
                let rows = st.query_map([pod], node_row)?.collect::<rusqlite::Result<Vec<_>>>();
                rows
            })
            .unwrap_or_default()
    }

    fn deps(&self, pod: &str) -> HashMap<String, Vec<String>> {
        let pairs: Vec<(String, String)> = self
            .db
            .read(|c| {
                let mut st = c.prepare("SELECT d.node_id, d.depends_on FROM node_dependencies d JOIN pod_nodes n ON n.id = d.node_id WHERE n.pod_id = ?1")?;
                let rows = st.query_map([pod], |r| Ok((r.get(0)?, r.get(1)?)))?.collect::<rusqlite::Result<Vec<_>>>();
                rows
            })
            .unwrap_or_default();
        let mut m: HashMap<String, Vec<String>> = HashMap::new();
        for (n, d) in pairs {
            m.entry(n).or_default().push(d);
        }
        m
    }

    fn update_node(&self, n: &NodeRecord) -> R<()> {
        self.db
            .write(|c| {
                c.execute(
                    "UPDATE pod_nodes SET state = ?2, attempts = ?3, review_state = ?4, detail = ?5, ended_at = ?6 WHERE id = ?1",
                    params![n.id, n.state, n.attempts, n.review_state, n.detail, n.ended_at],
                )
            })
            .map(|_| ())
            .map_err(db_err)
    }

    fn runs_of(&self, node: &str) -> Vec<RunRecord> {
        self.db
            .read(|c| {
                let mut st = c.prepare(
                    "SELECT r.id, r.session_id, r.provider, r.working_dir, r.prompt, r.state, r.started_at, r.ended_at, r.exit_code, r.error, r.pod_id \
                     FROM node_attempts a JOIN runs r ON r.id = a.run_id WHERE a.node_id = ?1 ORDER BY a.attempt",
                )?;
                let rows = st
                    .query_map([node], |r| {
                        Ok(RunRecord {
                            id: r.get(0)?,
                            session_id: r.get(1)?,
                            provider: r.get(2)?,
                            working_dir: r.get(3)?,
                            prompt: r.get(4)?,
                            state: r.get(5)?,
                            started_at: r.get(6)?,
                            ended_at: r.get(7)?,
                            exit_code: r.get(8)?,
                            error: r.get(9)?,
                            pod_id: r.get(10)?,
                        })
                    })?
                    .collect::<rusqlite::Result<Vec<_>>>();
                rows
            })
            .unwrap_or_default()
    }

    /// The `pod` answer: the pod, its nodes, each node's runs in attempt order.
    pub fn status(&self, pod: &str) -> Option<Value> {
        let p = self.pod(pod)?;
        let nodes = self.nodes(pod);
        let runs: BTreeMap<String, Vec<RunRecord>> = nodes.iter().map(|n| (n.id.clone(), self.runs_of(&n.id))).collect();
        Some(json!({ "pod": p, "nodes": nodes, "runs": runs }))
    }

    pub fn recent(&self) -> Vec<String> {
        self.db
            .read(|c| {
                let mut st = c.prepare("SELECT id FROM pods ORDER BY created_at DESC LIMIT ?1")?;
                let rows = st.query_map([LIST_MAX], |r| r.get::<_, String>(0))?.collect::<rusqlite::Result<Vec<_>>>();
                rows
            })
            .unwrap_or_default()
    }

    fn push(&self, pod: &str) {
        if let Some(s) = self.status(pod) {
            self.sessions.push(&s);
        }
    }

    // ---- operations -------------------------------------------------------------------------------------------

    pub fn submit(&self, spec: PodSpec) -> R<String> {
        validate(&spec)?;
        let now = (self.clock)();
        let pod_id = id();
        let ids: HashMap<String, String> = spec.nodes.iter().map(|n| (n.name.clone(), id())).collect();
        self.db
            .write(|c| {
                let tx = c.transaction()?;
                tx.execute("INSERT INTO pods(id, goal, state, max_concurrency, created_at) VALUES (?1, ?2, 'RUNNING', ?3, ?4)", params![pod_id, spec.goal, spec.max_concurrency, now])?;
                for (i, n) in spec.nodes.iter().enumerate() {
                    tx.execute(
                        "INSERT INTO pod_nodes(id, pod_id, name, spec_index, prompt, state, max_attempts, attempts, created_at) VALUES (?1, ?2, ?3, ?4, ?5, 'PENDING', ?6, 0, ?7)",
                        params![ids[&n.name], pod_id, n.name, i as i64, n.prompt, n.max_attempts, now],
                    )?;
                }
                for n in &spec.nodes {
                    let mut d = n.depends_on.clone();
                    d.sort();
                    d.dedup();
                    for dep in d {
                        tx.execute("INSERT INTO node_dependencies(node_id, depends_on) VALUES (?1, ?2)", params![ids[&n.name], ids[&dep]])?;
                    }
                }
                tx.commit()
            })
            .map_err(db_err)?;
        let mut inner = self.inner.lock().unwrap();
        inner.launches.insert(pod_id.clone(), spec);
        self.schedule(&mut inner, &pod_id);
        drop(inner);
        self.push(&pod_id);
        Ok(pod_id)
    }

    pub fn cancel(&self, pod_id: &str, force: bool) -> R<()> {
        let pod = self.pod(pod_id).ok_or_else(|| format!("unknown pod '{pod_id}'"))?;
        if pod.ended_at.is_some() {
            return Ok(());
        }
        let now = (self.clock)();
        let mut inner = self.inner.lock().unwrap();
        self.db.write(|c| c.execute("UPDATE pods SET state = 'CANCELLING' WHERE id = ?1", [pod_id])).map_err(db_err)?;
        for n in self.nodes(pod_id).into_iter().filter(|n| n.state == "PENDING") {
            self.update_node(&NodeRecord { state: "CANCELLED".into(), detail: Some("pod cancelled before start".into()), ended_at: Some(now), ..n })?;
        }
        let live: Vec<String> = inner.by_session.iter().filter(|(_, l)| l.pod == pod_id).map(|(s, _)| s.clone()).collect();
        self.maybe_finish(&mut inner, pod_id);
        drop(inner);
        for s in live {
            self.sessions.kill(&s, force);
        }
        self.push(pod_id);
        Ok(())
    }

    pub fn review(&self, node_id: &str, decision: &str) -> R<String> {
        let d = decision.trim().to_ascii_uppercase();
        if d != "APPROVED" && d != "REJECTED" {
            return Err(format!("decision must be APPROVED or REJECTED, was {decision}"));
        }
        let node: NodeRecord = self
            .db
            .read(|c| c.query_row(&format!("SELECT {NODE_COLS} FROM pod_nodes WHERE id = ?1"), [node_id], node_row).optional())
            .map_err(db_err)?
            .ok_or_else(|| format!("unknown node '{node_id}'"))?;
        if node.review_state.as_deref() != Some("PENDING") {
            return Err(format!("node {node_id} is not awaiting review (reviewState={})", node.review_state.as_deref().unwrap_or("null")));
        }
        let pod = node.pod_id.clone();
        self.update_node(&NodeRecord { review_state: Some(d), ..node })?;
        self.push(&pod);
        Ok(pod)
    }

    /// Startup: every open pod becomes INTERRUPTED (its running nodes FAILED, its pending nodes SKIPPED).
    pub fn recover(&self) -> Vec<String> {
        let now = (self.clock)();
        let open: Vec<String> = self
            .db
            .read(|c| {
                let mut st = c.prepare("SELECT id FROM pods WHERE ended_at IS NULL")?;
                let rows = st.query_map([], |r| r.get::<_, String>(0))?.collect::<rusqlite::Result<Vec<_>>>();
                rows
            })
            .unwrap_or_default();
        for p in &open {
            for n in self.nodes(p) {
                let next = match n.state.as_str() {
                    "RUNNING" => Some(("FAILED", "daemon restarted mid-run")),
                    "PENDING" => Some(("SKIPPED", "daemon restarted before start")),
                    _ => None,
                };
                if let Some((st, why)) = next {
                    let _ = self.update_node(&NodeRecord { state: st.into(), detail: Some(why.into()), ended_at: Some(now), ..n });
                }
            }
            let _ = self.db.write(|c| c.execute("UPDATE pods SET state = 'INTERRUPTED', ended_at = ?2 WHERE id = ?1", params![p, now]));
        }
        open
    }

    // ---- scheduling -------------------------------------------------------------------------------------------

    fn schedule(&self, inner: &mut Inner, pod_id: &str) {
        let Some(pod) = self.pod(pod_id) else { return };
        if pod.state != "RUNNING" {
            self.maybe_finish(inner, pod_id);
            return;
        }
        let deps = self.deps(pod_id);
        // Skip propagation to a fixed point.
        loop {
            let nodes = self.nodes(pod_id);
            let by: HashMap<&str, &NodeRecord> = nodes.iter().map(|n| (n.id.as_str(), n)).collect();
            let skip = nodes.iter().filter(|n| n.state == "PENDING").find_map(|n| {
                let mut ups: Vec<&NodeRecord> = deps.get(&n.id).into_iter().flatten().filter_map(|d| by.get(d.as_str()).copied()).collect();
                ups.sort_by_key(|u| u.spec_index);
                ups.into_iter().find(|u| SKIP_CAUSES.contains(&u.state.as_str())).map(|bad| (n.clone(), format!("upstream '{}' {}", bad.name, bad.state)))
            });
            match skip {
                Some((n, why)) => {
                    let _ = self.update_node(&NodeRecord { state: "SKIPPED".into(), detail: Some(why), ended_at: Some((self.clock)()), ..n });
                }
                None => break,
            }
        }
        let nodes = self.nodes(pod_id);
        let by: HashMap<&str, &NodeRecord> = nodes.iter().map(|n| (n.id.as_str(), n)).collect();
        let running = nodes.iter().filter(|n| n.state == "RUNNING").count() as i64;
        let ready: Vec<NodeRecord> = nodes
            .iter()
            .filter(|n| n.state == "PENDING" && deps.get(&n.id).into_iter().flatten().all(|d| by.get(d.as_str()).is_some_and(|u| u.state == "SUCCEEDED")))
            .take((pod.max_concurrency - running).max(0) as usize)
            .cloned()
            .collect();
        let mut failed = false;
        for n in ready {
            if !self.start_attempt(inner, &pod, n) {
                failed = true;
            }
        }
        if failed {
            return self.schedule(inner, pod_id);
        }
        self.maybe_finish(inner, pod_id);
    }

    fn start_attempt(&self, inner: &mut Inner, pod: &PodRecord, node: NodeRecord) -> bool {
        let spec = inner.launches.get(&pod.id).cloned();
        let attempt = node.attempts + 1;
        let prompt = if attempt == 1 {
            node.prompt.clone()
        } else {
            let prev = self.runs_of(&node.id).pop();
            retry_feedback(&node.prompt, attempt, node.max_attempts, prev.as_ref())
        };
        let ns = spec.as_ref().and_then(|s| s.nodes.iter().find(|n| n.name == node.name).cloned());
        let running = NodeRecord { state: "RUNNING".into(), attempts: attempt, detail: None, ..node.clone() };
        let _ = self.update_node(&running);
        let launch = Launch {
            prompt: prompt.clone(),
            project_id: spec.as_ref().and_then(|s| s.project_id.clone()),
            working_dir: spec.as_ref().and_then(|s| s.working_dir.clone()),
            agent_id: ns.as_ref().and_then(|n| n.agent_id.clone()),
            provider: ns.as_ref().and_then(|n| n.provider.clone()),
            model: ns.as_ref().and_then(|n| n.model.clone()),
        };
        let started = match self.sessions.start(&launch) {
            Ok(s) => s,
            Err(e) => {
                let _ = self.update_node(&NodeRecord { state: "FAILED".into(), detail: Some(format!("could not start: {e}")), ended_at: Some((self.clock)()), ..running });
                return false;
            }
        };
        let run = id();
        let now = (self.clock)();
        let _ = self.db.write(|c| {
            let tx = c.transaction()?;
            tx.execute(
                "INSERT INTO runs(id, session_id, provider, working_dir, prompt, state, started_at, pod_id) VALUES (?1, ?2, ?3, ?4, ?5, 'STARTING', ?6, ?7)",
                params![run, started.session_id, started.provider, started.working_dir, prompt, now, pod.id],
            )?;
            tx.execute("INSERT INTO node_attempts(run_id, node_id, attempt) VALUES (?1, ?2, ?3)", params![run, node.id, attempt])?;
            tx.commit()
        });
        inner.by_node.insert(node.id.clone(), started.session_id.clone());
        inner.by_session.insert(started.session_id, Live { pod: pod.id.clone(), node: node.id, run });
        true
    }

    /// A session's turn ended (worker thread): the attempt's outcome moves its node, then the pod is rescheduled.
    pub fn on_turn_end(&self, session: &str, state: &str, outcome: &str, exit_code: Option<i64>, error: Option<String>) {
        let mut inner = self.inner.lock().unwrap();
        let Some(live) = inner.by_session.remove(session) else { return };
        inner.by_node.remove(&live.node);
        let now = (self.clock)();
        let _ = self.db.write(|c| c.execute("UPDATE runs SET state = ?2, ended_at = ?3, exit_code = ?4, error = ?5 WHERE id = ?1", params![live.run, state, now, exit_code, error]));
        let Some(node) = self.db.read(|c| c.query_row(&format!("SELECT {NODE_COLS} FROM pod_nodes WHERE id = ?1"), [&live.node], node_row).optional()).ok().flatten() else { return };
        let pod_running = self.pod(&live.pod).is_some_and(|p| p.state == "RUNNING");
        let cancelled = inner.cancel_requested.remove(&live.node);
        let done = |st: &str, why: Option<String>| NodeRecord { state: st.into(), detail: why, ended_at: Some(now), ..node.clone() };
        let next = match outcome {
            "SUCCEEDED" => done("SUCCEEDED", None),
            "KILLED_GRACEFUL" => done("CANCELLED", Some("killed (graceful)".into())),
            "KILLED_FORCED" => done("CANCELLED", Some("killed (forced)".into())),
            "ORPHANED" => done("FAILED", Some("orphaned".into())),
            _ if cancelled => done("CANCELLED", Some("cancelled by request".into())),
            _ if !pod_running => done("FAILED", Some("not retried: pod cancelling".into())),
            _ if node.attempts < node.max_attempts => NodeRecord { state: "PENDING".into(), detail: None, ended_at: None, ..node.clone() },
            _ => done("FAILED", Some(format!("attempts exhausted ({}/{})", node.attempts, node.max_attempts))),
        };
        let _ = self.update_node(&next);
        self.schedule(&mut inner, &live.pod);
        drop(inner);
        self.push(&live.pod);
    }

    fn maybe_finish(&self, inner: &mut Inner, pod_id: &str) {
        let Some(pod) = self.pod(pod_id) else { return };
        if pod.ended_at.is_some() {
            return;
        }
        let nodes = self.nodes(pod_id);
        if nodes.iter().any(|n| n.state == "PENDING" || n.state == "RUNNING") {
            return;
        }
        let fin = if pod.state == "CANCELLING" {
            "CANCELLED"
        } else if nodes.iter().all(|n| n.state == "SUCCEEDED") {
            "CONVERGED"
        } else {
            "FAILED"
        };
        let now = (self.clock)();
        let _ = self.db.write(|c| {
            let tx = c.transaction()?;
            tx.execute("UPDATE pods SET state = ?2, ended_at = ?3 WHERE id = ?1", params![pod_id, fin, now])?;
            if fin == "CONVERGED" {
                tx.execute("UPDATE pod_nodes SET review_state = 'PENDING' WHERE pod_id = ?1", [pod_id])?;
            }
            tx.commit()
        });
        inner.launches.remove(pod_id);
    }
}

/// A retry's prompt: the original, then the previous attempt's failure (Kotlin `RetryFeedback`, minus the event log).
pub fn retry_feedback(original: &str, next: i64, max: i64, prev: Option<&RunRecord>) -> String {
    let exit = prev.and_then(|r| r.exit_code).map(|c| c.to_string()).unwrap_or_else(|| "none (the run could not be started)".into());
    let err = prev.and_then(|r| r.error.clone()).unwrap_or_default();
    let tail: String = err.chars().rev().take(RETRY_ERROR_CHARS).collect::<Vec<_>>().into_iter().rev().collect();
    let mut s = format!("{original}\n\n---\n{RETRY_MARKER} (attempt {next} of {max})\nThe previous attempt (attempt {}) did not succeed.\nexit code: {exit}", next - 1);
    if !tail.is_empty() {
        s.push_str(&format!("\nerror: {tail}"));
    }
    s.push_str("\nFix the cause and finish the task.");
    s
}

/// The session stream into the pods, through one worker thread (a session never starts inside a session callback).
pub struct PodObserver {
    tx: Mutex<mpsc::Sender<(String, String, String, Option<i64>, Option<String>)>>,
}

impl PodObserver {
    pub fn new(pods: Arc<Pods>) -> PodObserver {
        let (tx, rx) = mpsc::channel::<(String, String, String, Option<i64>, Option<String>)>();
        std::thread::Builder::new()
            .name("infantry-pods".into())
            .spawn(move || {
                for (sid, state, outcome, code, err) in rx {
                    pods.on_turn_end(&sid, &state, &outcome, code, err);
                }
            })
            .expect("pods worker");
        PodObserver { tx: Mutex::new(tx) }
    }
}

impl crate::sessions::SessionObserver for PodObserver {
    fn on_turn_end(&self, rec: &SessionRecord, outcome: &str, detail: &str) {
        let state = serde_json::to_value(rec.state).ok().and_then(|v| v.as_str().map(String::from)).unwrap_or_default();
        let err = (!detail.is_empty()).then(|| detail.to_string());
        let _ = self.tx.lock().unwrap().send((rec.id.clone(), state, outcome.to_string(), rec.last_exit_code.map(i64::from), err));
    }
}

pub struct PodsUnit {
    pub pods: Arc<Pods>,
}

fn reply(env: &Envelope, ty: &str, v: &Value) -> Envelope {
    let mut e = Envelope::new(ty, v.to_string());
    e.request_id = env.request_id.clone();
    e
}

impl FrameUnit for PodsUnit {
    fn name(&self) -> &'static str {
        "pods"
    }
    fn frames(&self) -> &'static [&'static str] {
        frames::ALL
    }
    fn handle(&self, env: &Envelope, ctx: &FrameCtx) -> Result<Vec<Envelope>, Refusal> {
        let ty = env.r#type.as_str();
        let refuse = |m: String| Refusal::new(ty, m);
        let status = |id: &str| self.pods.status(id).ok_or_else(|| refuse(format!("unknown pod '{id}'")));
        let data = env.data.trim();
        match ty {
            frames::SUBMIT => {
                if !ctx.owner {
                    return Err(refuse("owner only".into()));
                }
                let spec: PodSpec = serde_json::from_str(data).map_err(|_| refuse("malformed pod_submit payload".into()))?;
                let id = self.pods.submit(spec).map_err(refuse)?;
                Ok(vec![reply(env, frames::POD, &status(&id)?)])
            }
            frames::QUERY if data.is_empty() => Ok(self.pods.recent().iter().filter_map(|id| self.pods.status(id)).map(|s| reply(env, frames::POD, &s)).collect()),
            frames::QUERY => {
                let id = data.split_whitespace().next().ok_or_else(|| refuse("pod_query expects '<podId>'".into()))?;
                Ok(vec![reply(env, frames::POD, &status(id)?)])
            }
            frames::CANCEL => {
                if !ctx.owner {
                    return Err(refuse("owner only".into()));
                }
                let mut w = data.split_whitespace();
                let id = w.next().ok_or_else(|| refuse("pod_cancel expects '<podId> [force]'".into()))?;
                let force = w.next() == Some("force");
                self.pods.cancel(id, force).map_err(refuse)?;
                Ok(vec![reply(env, frames::POD, &status(id)?)])
            }
            frames::LIST => {
                let all: Vec<Value> = self.pods.recent().iter().filter_map(|id| self.pods.status(id)).collect();
                Ok(vec![reply(env, frames::PODS, &json!(all))])
            }
            frames::REVIEW => {
                if !ctx.owner {
                    return Err(refuse("owner only".into()));
                }
                let v: Value = serde_json::from_str(data).map_err(|_| refuse("pod_review expects {\"nodeId\", \"decision\"}".into()))?;
                let node = v.get("nodeId").and_then(Value::as_str).ok_or_else(|| refuse("pod_review needs nodeId".into()))?;
                let decision = v.get("decision").and_then(Value::as_str).unwrap_or("");
                let pod = self.pods.review(node, decision).map_err(refuse)?;
                Ok(vec![reply(env, frames::POD, &status(&pod)?)])
            }
            other => Err(refuse(format!("not a pod frame: {other}"))),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[derive(Default)]
    struct Fake {
        started: Mutex<Vec<Launch>>,
        refuse: bool,
    }
    impl PodSessions for Fake {
        fn start(&self, l: &Launch) -> Result<Started, String> {
            if self.refuse {
                return Err("at capacity".into());
            }
            let mut s = self.started.lock().unwrap();
            s.push(l.clone());
            Ok(Started { session_id: format!("s{}", s.len()), provider: "fake".into(), working_dir: "/w".into() })
        }
        fn kill(&self, _s: &str, _f: bool) {}
        fn push(&self, _v: &Value) {}
    }

    fn pods(fake: Arc<Fake>) -> Arc<Pods> {
        let dir = std::env::temp_dir().join(format!("infantry-pods-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&dir).unwrap();
        let db = Arc::new(Db::open(dir.join("infantry.db")).unwrap());
        crate::fresh_schema::ensure(&db).unwrap();
        Pods::new(db, fake)
    }

    fn spec(v: Value) -> PodSpec {
        serde_json::from_value(v).unwrap()
    }

    #[test]
    fn a_dag_runs_in_order_retries_then_converges_for_review() {
        let fake = Arc::new(Fake::default());
        let p = pods(fake.clone());
        let id = p
            .submit(spec(json!({"goal": "g", "nodes": [{"name": "a", "prompt": "do a", "maxAttempts": 2}, {"name": "b", "prompt": "do b", "dependsOn": ["a"]}]})))
            .unwrap();
        assert_eq!(fake.started.lock().unwrap().len(), 1, "b waits for a");
        p.on_turn_end("s1", "FAILED", "FAILED", Some(1), Some("boom".into()));
        let retry = fake.started.lock().unwrap()[1].prompt.clone();
        assert!(retry.contains(RETRY_MARKER) && retry.contains("boom"));
        p.on_turn_end("s2", "IDLE", "SUCCEEDED", Some(0), None);
        p.on_turn_end("s3", "IDLE", "SUCCEEDED", Some(0), None);
        let s = p.status(&id).unwrap();
        assert_eq!(s["pod"]["state"], "CONVERGED");
        assert_eq!(s["nodes"][1]["reviewState"], "PENDING");
        let node = s["nodes"][1]["id"].as_str().unwrap().to_string();
        p.review(&node, "approved").unwrap();
        assert_eq!(p.status(&id).unwrap()["nodes"][1]["reviewState"], "APPROVED");
        assert!(p.review(&node, "approved").unwrap_err().contains("not awaiting review"));
    }

    #[test]
    fn a_failed_upstream_skips_its_dependents_and_bad_specs_are_refused() {
        let fake = Arc::new(Fake::default());
        let p = pods(fake);
        let id = p.submit(spec(json!({"goal": "g", "nodes": [{"name": "a", "prompt": "x"}, {"name": "b", "prompt": "y", "dependsOn": ["a"]}]}))).unwrap();
        p.on_turn_end("s1", "FAILED", "FAILED", Some(2), None);
        let s = p.status(&id).unwrap();
        assert_eq!((s["pod"]["state"].as_str(), s["nodes"][1]["state"].as_str()), (Some("FAILED"), Some("SKIPPED")));
        assert!(validate(&spec(json!({"goal": "g", "nodes": [{"name": "a", "prompt": "x", "dependsOn": ["b"]}, {"name": "b", "prompt": "y", "dependsOn": ["a"]}]}))).unwrap_err().contains("cycle"));
    }
}
