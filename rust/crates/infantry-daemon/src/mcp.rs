// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The MCP registry (Kotlin `McpService` + `McpRegistry` + `McpOwnedServers` + `McpGrants` + `McpSelection`): the
//! servers an Infantry session may be handed, the owner's grants, and the per-session plan.
//!
//! WALLED BY DEFAULT: a session gets the daemon's own `infantry` server, and any other server only when a grant names
//! it for the session's role, agent or ticket (and the grant's project, when it names one, is the session's). There is
//! no "all" grant. Servers come from, in precedence order: `infantry`, the Infantry-owned servers
//! (`<state>/mcp/servers.json`, a project server shadowing an app one), the plugins' `daemon.mcpServers`. A CLI's own
//! servers (a harness target) are not listed by this daemon yet: such a target is refused by name.
//!
//! Frames: `mcp_list|get|add|update|remove|set_enabled|test|assign|declare|revoke|grants|plan`; every change pushes
//! `mcp_changed` to every client. Views carry keys only, never a secret value.
use infantry_core::frames::{Envelope, FrameCtx, FrameUnit, Refusal};
use infantry_harness::{McpPlan, McpServerSpec, McpTransport, INFANTRY_MCP_SERVER};
use infantry_protocol::session::SessionRecord;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::collections::{BTreeMap, HashSet};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};

pub const TARGET_INFANTRY: &str = "infantry";
pub const SCOPE_APP: &str = "app";
pub const SCOPE_PROJECT: &str = "project";
/// Infantry-owned servers per scope (`limits.mcp.serversMax`).
pub const SERVERS_MAX: usize = 200;
/// Grants in all (`limits.mcp.grantsMax`).
pub const GRANTS_MAX: usize = 1_000;
/// A grant's purpose (`limits.mcp.purposeChars`).
pub const PURPOSE_CHARS: usize = 500;
pub const NAME_MAX: usize = 64;
pub const DIR: &str = "mcp";
pub const SERVERS_FILE: &str = "servers.json";
pub const GRANTS_FILE: &str = "grants.json";
pub const GRANT_ID_PREFIX: &str = "mcpg-";
const GRANT_SCOPES: [&str; 3] = ["role", "agent", "ticket"];
const CREDENTIAL_WORDS: [&str; 12] = ["token", "secret", "password", "passwd", "apikey", "api_key", "api-key", "private", "credential", "authorization", "cookie", "bearer"];

pub mod frames {
    pub const LIST: &str = "mcp_list";
    pub const GET: &str = "mcp_get";
    pub const ADD: &str = "mcp_add";
    pub const UPDATE: &str = "mcp_update";
    pub const REMOVE: &str = "mcp_remove";
    pub const SET_ENABLED: &str = "mcp_set_enabled";
    pub const TEST: &str = "mcp_test";
    pub const ASSIGN: &str = "mcp_assign";
    pub const DECLARE: &str = "mcp_declare";
    pub const REVOKE: &str = "mcp_revoke";
    pub const GRANTS: &str = "mcp_grants";
    pub const PLAN: &str = "mcp_plan";
    pub const SERVERS: &str = "mcp_servers";
    pub const SERVER: &str = "mcp_server";
    pub const STATUS: &str = "mcp_status";
    pub const CHANGED: &str = "mcp_changed";
    pub const ALL: &[&str] = &[LIST, GET, ADD, UPDATE, REMOVE, SET_ENABLED, TEST, ASSIGN, DECLARE, REVOKE, GRANTS, PLAN];
}

fn is_false(b: &bool) -> bool {
    !*b
}
fn yes() -> bool {
    true
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ServerInput {
    pub name: String,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub command: Vec<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub working_dir: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub url: Option<String>,
    #[serde(default, skip_serializing_if = "BTreeMap::is_empty")]
    pub env: BTreeMap<String, String>,
    #[serde(default, skip_serializing_if = "BTreeMap::is_empty")]
    pub env_secrets: BTreeMap<String, String>,
    #[serde(default, skip_serializing_if = "BTreeMap::is_empty")]
    pub headers: BTreeMap<String, String>,
    #[serde(default, skip_serializing_if = "BTreeMap::is_empty")]
    pub header_secrets: BTreeMap<String, String>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub provides: Vec<String>,
    #[serde(default, skip_serializing_if = "is_false")]
    pub local_only: bool,
    #[serde(default = "yes")]
    pub enabled: bool,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct GrantInput {
    pub server: String,
    pub scope: String,
    pub target: String,
    #[serde(default)]
    pub project_id: Option<String>,
    pub purpose: String,
    pub approved_by: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Grant {
    pub id: String,
    pub server: String,
    pub scope: String,
    pub target: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub project_id: Option<String>,
    pub purpose: String,
    pub approved_by: String,
    pub approved_at: i64,
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DeclareInput {
    pub scope: String,
    pub target: String,
    #[serde(default)]
    pub project_id: Option<String>,
    #[serde(default)]
    pub servers: Vec<String>,
    #[serde(default)]
    pub purpose: String,
    #[serde(default)]
    pub declared_by: Option<String>,
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Request {
    #[serde(default)]
    pub target: Option<String>,
    #[serde(default)]
    pub project_id: Option<String>,
    #[serde(default)]
    pub scope: Option<String>,
    #[serde(default)]
    pub name: Option<String>,
    #[serde(default)]
    pub server: Option<ServerInput>,
    #[serde(default)]
    pub enabled: Option<bool>,
    #[serde(default)]
    pub grant: Option<GrantInput>,
    #[serde(default)]
    pub grant_id: Option<String>,
    #[serde(default)]
    pub declare: Option<DeclareInput>,
    #[serde(default)]
    pub session_id: Option<String>,
    #[serde(default)]
    pub role: Option<String>,
    #[serde(default)]
    pub agent_id: Option<String>,
    #[serde(default)]
    pub ticket_id: Option<String>,
    #[serde(default)]
    pub remote: bool,
}

/// Who a plan is for.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct SessionCtx {
    pub harness: String,
    pub project_id: Option<String>,
    pub role: Option<String>,
    pub agent_id: Option<String>,
    pub ticket_id: Option<String>,
    pub remote: bool,
}

impl SessionCtx {
    pub fn of(rec: &SessionRecord) -> SessionCtx {
        SessionCtx {
            harness: rec.provider.clone(),
            project_id: rec.project_id.clone(),
            role: rec.role.clone(),
            agent_id: rec.agent_id.clone(),
            ticket_id: rec.ticket_id.clone(),
            remote: rec.runner != "local",
        }
    }
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
struct ServersDoc {
    #[serde(default)]
    app: Vec<ServerInput>,
    #[serde(default)]
    projects: BTreeMap<String, Vec<ServerInput>>,
}

/// One registry row: a server, where it came from, and whether a session could be handed it.
#[derive(Debug, Clone)]
pub struct Entry {
    pub input: ServerInput,
    /// `infantry`, `infantry_user`, `plugin`.
    pub source: &'static str,
    pub project_id: Option<String>,
    pub shadowed_by: Option<&'static str>,
    /// Why it cannot be built (a missing secret), never a value.
    pub problem: Option<String>,
}

impl Entry {
    fn state(&self) -> &'static str {
        if self.source == "infantry" {
            "configured"
        } else if !self.input.enabled {
            "disabled"
        } else if self.problem.is_some() {
            "failed"
        } else {
            "configured"
        }
    }

    pub fn view(&self) -> Value {
        let s = &self.input;
        let mut v = json!({
            "name": s.name, "source": self.source, "target": TARGET_INFANTRY,
            "transport": if s.url.is_some() { "http" } else { "stdio" },
            "enabled": s.enabled, "state": self.state(),
        });
        let o = v.as_object_mut().expect("object");
        if self.source == "infantry_user" {
            o.insert("scope".into(), (if self.project_id.is_some() { SCOPE_PROJECT } else { SCOPE_APP }).into());
        }
        if !s.command.is_empty() {
            o.insert("command".into(), json!(s.command));
        }
        if let Some(u) = &s.url {
            o.insert("url".into(), json!(u));
        }
        let env: Vec<&String> = s.env.keys().chain(s.env_secrets.keys()).collect();
        if !env.is_empty() {
            o.insert("envKeys".into(), json!(env));
        }
        let hdr: Vec<&String> = s.headers.keys().chain(s.header_secrets.keys()).collect();
        if !hdr.is_empty() {
            o.insert("headerKeys".into(), json!(hdr));
        }
        if !s.provides.is_empty() {
            o.insert("provides".into(), json!(s.provides));
        }
        if s.local_only {
            o.insert("localOnly".into(), true.into());
        }
        if let Some(p) = &self.problem {
            o.insert("detail".into(), json!(p));
        }
        if let Some(by) = self.shadowed_by {
            o.insert("shadowedBy".into(), json!(by));
        }
        if let Some(p) = &self.project_id {
            o.insert("projectId".into(), json!(p));
        }
        v
    }
}

/// Resolves a secret's value (name, project); never logged.
pub type SecretFn = Arc<dyn Fn(&str, Option<&str>) -> Option<String> + Send + Sync>;
/// The plugin servers declared now (plugin id, its `daemon.mcpServers`).
pub type PluginServersFn = Arc<dyn Fn() -> Vec<(String, Vec<ServerInput>)> + Send + Sync>;
/// A session's context by id.
pub type SessionFn = Arc<dyn Fn(&str) -> Option<SessionCtx> + Send + Sync>;

pub struct Registry {
    dir: PathBuf,
    lock: Mutex<()>,
    secret: SecretFn,
    plugin_servers: PluginServersFn,
    session: SessionFn,
    clock: fn() -> i64,
}

fn now_ms() -> i64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0)
}

fn bad<T>(m: impl Into<String>) -> Result<T, String> {
    Err(m.into())
}

fn trimmed(s: &Option<String>) -> Option<String> {
    s.as_deref().map(str::trim).filter(|s| !s.is_empty()).map(String::from)
}

/// Refuses a server that cannot be stored (Kotlin `McpOwnedServers.validate`).
pub fn validate(s: &ServerInput) -> Result<(), String> {
    let name_ok = !s.name.is_empty()
        && s.name.len() <= NAME_MAX
        && s.name.chars().next().is_some_and(|c| c.is_ascii_alphanumeric())
        && s.name.chars().all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-');
    if !name_ok {
        return bad(format!("MCP server name '{}' must be letters, digits, '_' or '-' (at most {NAME_MAX})", s.name));
    }
    if s.name == INFANTRY_MCP_SERVER {
        return bad(format!("'{INFANTRY_MCP_SERVER}' is the daemon's own MCP server"));
    }
    let stdio = !s.command.is_empty();
    let http = s.url.as_deref().is_some_and(|u| !u.trim().is_empty());
    if stdio == http {
        return bad(format!("MCP server '{}' needs exactly one of a command (stdio) or a url (http)", s.name));
    }
    if http && !s.url.as_deref().is_some_and(|u| u.starts_with("http://") || u.starts_with("https://")) {
        return bad("MCP server url must start with http:// or https://");
    }
    if !stdio && (!s.env.is_empty() || !s.env_secrets.is_empty() || s.working_dir.is_some()) {
        return bad("env and workingDir apply to a stdio server only");
    }
    if !http && (!s.headers.is_empty() || !s.header_secrets.is_empty()) {
        return bad("headers apply to an http server only");
    }
    if let Some(k) = s.env.keys().chain(s.headers.keys()).find(|k| CREDENTIAL_WORDS.iter().any(|w| k.to_lowercase().contains(w))) {
        return bad(format!("'{k}' looks like a credential: store it in Secrets and name it in envSecrets / headerSecrets, never as a plain value"));
    }
    if let Some(k) = s.env_secrets.keys().find(|k| s.env.contains_key(*k)) {
        return bad(format!("env key '{k}' is given twice"));
    }
    if let Some(k) = s.header_secrets.keys().find(|k| s.headers.contains_key(*k)) {
        return bad(format!("header key '{k}' is given twice"));
    }
    Ok(())
}

impl Registry {
    pub fn new(state_dir: &Path, secret: SecretFn, plugin_servers: PluginServersFn, session: SessionFn) -> Registry {
        Registry { dir: state_dir.join(DIR), lock: Mutex::new(()), secret, plugin_servers, session, clock: now_ms }
    }

    fn read<T: for<'a> Deserialize<'a> + Default>(&self, file: &str) -> T {
        std::fs::read_to_string(self.dir.join(file)).ok().and_then(|t| serde_json::from_str(&t).ok()).unwrap_or_default()
    }

    /// Written whole through a temp file and a rename, so a crash leaves the old file or the new one.
    fn write<T: Serialize>(&self, file: &str, v: &T) -> Result<(), String> {
        std::fs::create_dir_all(&self.dir).map_err(|e| e.to_string())?;
        let tmp = self.dir.join(format!("{file}.tmp"));
        std::fs::write(&tmp, serde_json::to_string_pretty(v).map_err(|e| e.to_string())?).map_err(|e| e.to_string())?;
        std::fs::rename(&tmp, self.dir.join(file)).map_err(|e| e.to_string())
    }

    // ---- servers --------------------------------------------------------------------------------------------

    fn spec(&self, s: &ServerInput, project: Option<&str>, source: &str) -> Result<McpServerSpec, String> {
        let resolve = |refs: &BTreeMap<String, String>| -> Result<Vec<(String, String)>, String> {
            refs.iter().map(|(k, name)| (self.secret)(name, project).map(|v| (k.clone(), v)).ok_or_else(|| format!("secret '{name}' (for {k}) is not set"))).collect()
        };
        let transport = if !s.command.is_empty() {
            let mut env: Vec<(String, String)> = s.env.iter().map(|(k, v)| (k.clone(), v.clone())).collect();
            env.extend(resolve(&s.env_secrets)?);
            McpTransport::Stdio { command: s.command.clone(), env, working_dir: s.working_dir.clone() }
        } else {
            let mut headers: Vec<(String, String)> = s.headers.iter().map(|(k, v)| (k.clone(), v.clone())).collect();
            headers.extend(resolve(&s.header_secrets)?);
            McpTransport::Http { url: s.url.clone().unwrap_or_default(), headers }
        };
        Ok(McpServerSpec { name: s.name.clone(), transport, source: source.into(), local_only: s.local_only })
    }

    /// Every server visible for `project` (None = the app scope only), in precedence order; a loser of a name keeps
    /// its row with `shadowed_by`.
    pub fn entries(&self, project: Option<&str>) -> Vec<Entry> {
        let mut out = vec![Entry {
            input: ServerInput { name: INFANTRY_MCP_SERVER.into(), command: vec!["infantry".into(), "mcp".into(), "serve".into()], ..ServerInput::named("") },
            source: "infantry",
            project_id: None,
            shadowed_by: None,
            problem: None,
        }];
        let doc: ServersDoc = self.read(SERVERS_FILE);
        let mut owned: Vec<(ServerInput, Option<String>)> = Vec::new();
        if let Some(p) = project {
            owned.extend(doc.projects.get(p).cloned().unwrap_or_default().into_iter().map(|s| (s, Some(p.to_string()))));
        }
        owned.extend(doc.app.into_iter().map(|s| (s, None)));
        for (s, p) in owned {
            let problem = self.spec(&s, p.as_deref(), "infantry_user").err();
            out.push(Entry { input: s, source: "infantry_user", project_id: p, shadowed_by: None, problem });
        }
        for (_plugin, list) in (self.plugin_servers)() {
            for s in list {
                let problem = self.spec(&s, None, "plugin").err();
                out.push(Entry { input: s, source: "plugin", project_id: None, shadowed_by: None, problem });
            }
        }
        let mut seen: BTreeMap<String, &'static str> = BTreeMap::new();
        for e in out.iter_mut() {
            match seen.get(&e.input.name) {
                Some(winner) => e.shadowed_by = Some(winner),
                None => {
                    seen.insert(e.input.name.clone(), e.source);
                }
            }
        }
        out
    }

    fn mutate_servers(&self, project: Option<&str>, f: impl FnOnce(&mut Vec<ServerInput>) -> Result<(), String>) -> Result<(), String> {
        let _g = self.lock.lock().unwrap();
        let mut doc: ServersDoc = self.read(SERVERS_FILE);
        let list = match project {
            Some(p) => doc.projects.entry(p.to_string()).or_default(),
            None => &mut doc.app,
        };
        f(list)?;
        doc.projects.retain(|_, l| !l.is_empty());
        self.write(SERVERS_FILE, &doc)
    }

    fn owned_project(r: &Request) -> Result<Option<String>, String> {
        match r.scope.as_deref().map(str::trim).unwrap_or(SCOPE_APP) {
            "" | SCOPE_APP => Ok(None),
            SCOPE_PROJECT => trimmed(&r.project_id).map(Some).ok_or_else(|| "scope project needs a projectId".to_string()),
            other => bad(format!("an Infantry MCP server's scope is app or project; got '{other}'")),
        }
    }

    fn scope_label(p: &Option<String>) -> String {
        p.as_ref().map(|p| format!("project {p}")).unwrap_or_else(|| "the app scope".into())
    }

    pub fn put(&self, server: ServerInput, project: Option<String>, replace: bool) -> Result<(), String> {
        validate(&server)?;
        let label = Self::scope_label(&project);
        self.mutate_servers(project.as_deref(), |list| {
            let exists = list.iter().any(|s| s.name == server.name);
            if exists && !replace {
                return bad(format!("an Infantry MCP server named '{}' already exists in {label}; use update", server.name));
            }
            if !exists && replace {
                return bad(format!("no Infantry MCP server named '{}' in {label}", server.name));
            }
            if !exists && list.len() >= SERVERS_MAX {
                return bad(format!("{label} already holds {} Infantry MCP servers (setting 'limits.mcp.serversMax')", list.len()));
            }
            list.retain(|s| s.name != server.name);
            list.push(server);
            Ok(())
        })
    }

    pub fn remove(&self, name: &str, project: Option<String>) -> Result<(), String> {
        let label = Self::scope_label(&project);
        self.mutate_servers(project.as_deref(), |list| {
            let before = list.len();
            list.retain(|s| s.name != name);
            if list.len() == before {
                return bad(format!("no Infantry MCP server named '{name}' in {label}"));
            }
            Ok(())
        })
    }

    pub fn set_enabled(&self, name: &str, project: Option<String>, enabled: bool) -> Result<(), String> {
        let label = Self::scope_label(&project);
        self.mutate_servers(project.as_deref(), |list| match list.iter_mut().find(|s| s.name == name) {
            Some(s) => {
                s.enabled = enabled;
                Ok(())
            }
            None => bad(format!("no Infantry MCP server named '{name}' in {label}")),
        })
    }

    // ---- grants ---------------------------------------------------------------------------------------------

    pub fn grants(&self) -> Vec<Grant> {
        self.read(GRANTS_FILE)
    }

    pub fn grant(&self, g: GrantInput) -> Result<Grant, String> {
        let server = g.server.trim().to_string();
        let target = g.target.trim().to_string();
        if server.is_empty() || target.is_empty() {
            return bad("a grant needs a server and a target");
        }
        if !GRANT_SCOPES.contains(&g.scope.as_str()) {
            return bad(format!("a grant's scope is role, agent or ticket; got '{}'", g.scope));
        }
        if g.purpose.trim().is_empty() {
            return bad("a grant needs a purpose: why it needs the server");
        }
        if g.purpose.chars().count() > PURPOSE_CHARS {
            return bad(format!("a grant's purpose is at most {PURPOSE_CHARS} characters (setting 'limits.mcp.purposeChars')"));
        }
        if g.approved_by.trim().is_empty() {
            return bad("a grant needs approvedBy");
        }
        let project = trimmed(&g.project_id);
        let _l = self.lock.lock().unwrap();
        let mut all = self.grants();
        all.retain(|o| !(o.server == server && o.scope == g.scope && o.target == target && o.project_id == project));
        if all.len() >= GRANTS_MAX {
            return bad(format!("there are already {} MCP grants (setting 'limits.mcp.grantsMax')", all.len()));
        }
        let id = format!("{GRANT_ID_PREFIX}{}", &uuid::Uuid::new_v4().simple().to_string()[..8]);
        let grant = Grant { id, server, scope: g.scope, target, project_id: project, purpose: g.purpose.trim().into(), approved_by: g.approved_by.trim().into(), approved_at: (self.clock)() };
        all.push(grant.clone());
        self.write(GRANTS_FILE, &all)?;
        Ok(grant)
    }

    pub fn revoke(&self, id: &str) -> Result<(), String> {
        let _l = self.lock.lock().unwrap();
        let mut all = self.grants();
        let before = all.len();
        all.retain(|g| g.id != id);
        if all.len() == before {
            return bad(format!("no MCP grant '{id}'"));
        }
        self.write(GRANTS_FILE, &all)
    }

    /// A role / ticket / agent `mcp` block: REPLACES the grants of its scope + target (+ project).
    pub fn declare(&self, d: DeclareInput, by: &str) -> Result<Vec<Grant>, String> {
        let target = d.target.trim().to_string();
        if target.is_empty() {
            return bad(format!("an mcp block needs the {} it is for", d.scope));
        }
        if !GRANT_SCOPES.contains(&d.scope.as_str()) {
            return bad(format!("an mcp block's scope is role, agent or ticket; got '{}'", d.scope));
        }
        let mut servers: Vec<String> = d.servers.iter().map(|s| s.trim().to_string()).filter(|s| !s.is_empty()).collect();
        servers.dedup();
        if !servers.is_empty() && d.purpose.trim().is_empty() {
            return bad(format!("an mcp block needs a purpose: why this {} needs {}", d.scope, servers.join(", ")));
        }
        let project = trimmed(&d.project_id);
        let stale: Vec<String> = self.grants().into_iter().filter(|g| g.scope == d.scope && g.target == target && g.project_id == project && !servers.contains(&g.server)).map(|g| g.id).collect();
        for id in stale {
            self.revoke(&id)?;
        }
        servers
            .into_iter()
            .map(|s| self.grant(GrantInput { server: s, scope: d.scope.clone(), target: target.clone(), project_id: project.clone(), purpose: d.purpose.clone(), approved_by: by.into() }))
            .collect()
    }

    // ---- selection ------------------------------------------------------------------------------------------

    fn matches(g: &Grant, ctx: &SessionCtx) -> bool {
        if g.project_id.is_some() && g.project_id != ctx.project_id {
            return false;
        }
        let want = match g.scope.as_str() {
            "role" => &ctx.role,
            "agent" => &ctx.agent_id,
            "ticket" => &ctx.ticket_id,
            _ => return false,
        };
        want.as_deref() == Some(g.target.as_str())
    }

    /// The servers beyond `infantry` this session is handed, and one reason per server name.
    pub fn select(&self, ctx: &SessionCtx) -> (Vec<McpServerSpec>, BTreeMap<String, String>) {
        let grants = self.grants();
        let mut add = Vec::new();
        let mut reasons = BTreeMap::new();
        reasons.insert(INFANTRY_MCP_SERVER.to_string(), "the daemon's own server, always delivered".to_string());
        for e in self.entries(ctx.project_id.as_deref()).into_iter().filter(|e| e.source != "infantry") {
            let grant = grants.iter().find(|g| g.server == e.input.name && Self::matches(g, ctx));
            let who = [("role", &ctx.role), ("agent", &ctx.agent_id), ("ticket", &ctx.ticket_id)]
                .iter()
                .filter_map(|(k, v)| v.as_ref().map(|v| format!("{k} {v}")))
                .collect::<Vec<_>>()
                .join(" / ");
            let why = if let Some(by) = e.shadowed_by {
                Some(format!("shadowed by the {by} server of the same name"))
            } else if grant.is_none() {
                Some(format!("walled: no grant for this {}", if who.is_empty() { "session".into() } else { who }))
            } else if !e.input.enabled {
                Some("disabled".into())
            } else if ctx.remote && e.input.local_only {
                Some("local only: never sent to a remote runner".into())
            } else {
                e.problem.as_ref().map(|p| format!("cannot be built: {p}"))
            };
            if let Some(w) = why {
                reasons.entry(e.input.name.clone()).or_insert(w);
                continue;
            }
            let g = grant.expect("granted");
            if let Ok(spec) = self.spec(&e.input, e.project_id.as_deref(), e.source) {
                add.push(spec);
                reasons.insert(e.input.name.clone(), format!("granted to {} {} ({}, by {}): {}", g.scope, g.target, g.id, g.approved_by, g.purpose));
            }
        }
        (add, reasons)
    }

    // ---- frames ---------------------------------------------------------------------------------------------

    fn list_view(&self, project: Option<&str>) -> Value {
        let servers: Vec<Value> = self.entries(project).iter().map(Entry::view).collect();
        json!({
            "servers": servers,
            "targets": [{ "id": TARGET_INFANTRY, "canAdd": true, "canRemove": true, "canEdit": true, "scopes": [SCOPE_APP, SCOPE_PROJECT], "listing": true }],
            "grants": self.grants(),
        })
    }

    fn infantry_target(r: &Request) -> Result<(), String> {
        match trimmed(&r.target).as_deref() {
            None | Some(TARGET_INFANTRY) => Ok(()),
            Some(t) => bad(format!("'{t}' has no MCP management on this daemon (its CLI's servers are not listed yet); a CLI's own server is kept from Infantry sessions by having no grant (walled by default)")),
        }
    }

    /// One frame: (reply type, reply data, changed).
    pub fn handle(&self, ty: &str, r: Request, owner: bool) -> Result<(&'static str, Value, bool), String> {
        let need = |v: &Option<String>, what: &str| trimmed(v).ok_or_else(|| format!("expects a {what}"));
        let write = matches!(ty, frames::ADD | frames::UPDATE | frames::REMOVE | frames::SET_ENABLED | frames::ASSIGN | frames::DECLARE | frames::REVOKE);
        if write && !owner {
            return bad("owner only");
        }
        let project = trimmed(&r.project_id);
        match ty {
            frames::LIST => Ok((frames::SERVERS, self.list_view(project.as_deref()), false)),
            frames::GET => {
                let name = need(&r.name, "name")?;
                let e = self.entries(project.as_deref()).into_iter().find(|e| e.input.name == name && e.shadowed_by.is_none()).ok_or_else(|| format!("no MCP server named '{name}'"))?;
                Ok((frames::SERVER, e.view(), false))
            }
            frames::ADD | frames::UPDATE => {
                Self::infantry_target(&r)?;
                let server = r.server.clone().ok_or("expects a server")?;
                self.put(server, Self::owned_project(&r)?, ty == frames::UPDATE)?;
                Ok((frames::SERVERS, self.list_view(project.as_deref()), true))
            }
            frames::REMOVE => {
                Self::infantry_target(&r)?;
                self.remove(&need(&r.name, "name")?, Self::owned_project(&r)?)?;
                Ok((frames::SERVERS, self.list_view(project.as_deref()), true))
            }
            frames::SET_ENABLED => {
                Self::infantry_target(&r)?;
                let enabled = r.enabled.ok_or("expects enabled")?;
                self.set_enabled(&need(&r.name, "name")?, Self::owned_project(&r)?, enabled)?;
                Ok((frames::SERVERS, self.list_view(project.as_deref()), true))
            }
            frames::TEST => {
                Self::infantry_target(&r)?;
                let name = need(&r.name, "name")?;
                let e = self.entries(project.as_deref()).into_iter().find(|e| e.input.name == name).ok_or_else(|| format!("no Infantry MCP server named '{name}'"))?;
                let mut v = json!({ "name": name, "state": e.state() });
                if let Some(p) = &e.problem {
                    v["detail"] = json!(p);
                }
                Ok((frames::STATUS, v, false))
            }
            frames::ASSIGN => {
                self.grant(r.grant.clone().ok_or("expects a grant")?)?;
                Ok((frames::GRANTS, json!(self.grants()), true))
            }
            frames::DECLARE => {
                let d = r.declare.clone().ok_or("expects a declare block")?;
                let by = d.declared_by.clone().unwrap_or_else(|| "owner".into());
                self.declare(d, &by)?;
                Ok((frames::GRANTS, json!(self.grants()), true))
            }
            frames::REVOKE => {
                self.revoke(&need(&r.grant_id, "grantId")?)?;
                Ok((frames::GRANTS, json!(self.grants()), true))
            }
            frames::GRANTS => Ok((frames::GRANTS, json!(self.grants()), false)),
            frames::PLAN => {
                let ctx = match trimmed(&r.session_id) {
                    Some(sid) => (self.session)(&sid).ok_or_else(|| format!("unknown session '{sid}'"))?,
                    None => match trimmed(&r.target).filter(|t| t != TARGET_INFANTRY) {
                        Some(h) => SessionCtx { harness: h, project_id: project, role: trimmed(&r.role), agent_id: trimmed(&r.agent_id), ticket_id: trimmed(&r.ticket_id), remote: r.remote },
                        None => return bad("expects a sessionId, or a harness id as target"),
                    },
                };
                let (add, reasons) = self.select(&ctx);
                let mut names = vec![INFANTRY_MCP_SERVER.to_string()];
                names.extend(add.iter().map(|s| s.name.clone()));
                Ok((frames::PLAN, json!({ "add": names, "deny": Vec::<String>::new(), "reasons": reasons }), false))
            }
            other => bad(format!("not an mcp frame: {other}")),
        }
    }
}

impl ServerInput {
    fn named(name: &str) -> ServerInput {
        ServerInput {
            name: name.into(),
            command: Vec::new(),
            working_dir: None,
            url: None,
            env: BTreeMap::new(),
            env_secrets: BTreeMap::new(),
            headers: BTreeMap::new(),
            header_secrets: BTreeMap::new(),
            provides: Vec::new(),
            local_only: false,
            enabled: true,
        }
    }
}

/// Adds a session's granted servers to its launch plan (the session manager calls it at open).
impl crate::sessions::McpSelector for Registry {
    fn select(&self, rec: &SessionRecord, plan: &mut McpPlan) {
        let (add, _) = Registry::select(self, &SessionCtx::of(rec));
        let have: HashSet<String> = plan.add.iter().map(|s| s.name.clone()).collect();
        plan.add.extend(add.into_iter().filter(|s| !have.contains(&s.name)));
    }
}

/// The `daemon.mcpServers` the installed official modules declare (the tiers the plugin host loads). A declaration
/// that breaks the schema (no capability tag, disabled, an invalid server) is left out.
pub fn plugin_servers(plugins_dir: &Path) -> Vec<(String, Vec<ServerInput>)> {
    let mut out = Vec::new();
    for tier in crate::compose::plugins::TIERS {
        let Ok(rd) = std::fs::read_dir(plugins_dir.join(tier)) else { continue };
        for e in rd.filter_map(|e| e.ok()) {
            let Some(v) = std::fs::read_to_string(e.path().join("module.json")).ok().and_then(|t| serde_json::from_str::<Value>(&t).ok()) else { continue };
            let Some(list) = v.pointer("/daemon/mcpServers").and_then(Value::as_array) else { continue };
            let id = v.get("name").and_then(Value::as_str).unwrap_or_default().to_string();
            let servers: Vec<ServerInput> = list
                .iter()
                .filter_map(|s| serde_json::from_value::<ServerInput>(s.clone()).ok())
                .filter(|s| validate(s).is_ok() && s.enabled && !s.provides.is_empty())
                .collect();
            if !servers.is_empty() {
                out.push((id, servers));
            }
        }
    }
    out
}

pub struct McpUnit {
    pub registry: Arc<Registry>,
}

impl FrameUnit for McpUnit {
    fn name(&self) -> &'static str {
        "mcp"
    }
    fn frames(&self) -> &'static [&'static str] {
        frames::ALL
    }
    fn handle(&self, env: &Envelope, ctx: &FrameCtx) -> Result<Vec<Envelope>, Refusal> {
        let data = env.data.trim();
        let r: Request = if data.is_empty() { Request::default() } else { serde_json::from_str(data).map_err(|e| Refusal::new(&env.r#type, format!("malformed request: {e}")))? };
        let (ty, v, changed) = self.registry.handle(&env.r#type, r, ctx.owner).map_err(|e| Refusal::new(&env.r#type, e))?;
        if changed {
            ctx.bus.publish(Envelope::new(frames::CHANGED, "{}"));
        }
        let mut out = Envelope::new(ty, v.to_string());
        out.request_id = env.request_id.clone();
        Ok(vec![out])
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn reg(dir: &Path) -> Registry {
        let secret: SecretFn = Arc::new(|name, _| (name == "gh").then(|| "s3cr3t".to_string()));
        let session: SessionFn = Arc::new(|id| (id == "s1").then(|| SessionCtx { harness: "claude".into(), role: Some("dev".into()), ..Default::default() }));
        Registry::new(dir, secret, Arc::new(Vec::new), session)
    }

    fn server(v: Value) -> ServerInput {
        serde_json::from_value(v).unwrap()
    }

    #[test]
    fn a_server_is_walled_until_granted_and_views_carry_no_secret() {
        let dir = std::env::temp_dir().join(format!("infantry-mcp-{}", uuid::Uuid::new_v4()));
        let r = reg(&dir);
        r.put(server(json!({"name": "github", "url": "https://x/mcp", "headerSecrets": {"Authorization": "gh"}})), None, false).unwrap();
        let ctx = SessionCtx { role: Some("dev".into()), ..Default::default() };
        let (add, why) = r.select(&ctx);
        assert!(add.is_empty() && why["github"].starts_with("walled"));
        r.grant(GrantInput { server: "github".into(), scope: "role".into(), target: "dev".into(), project_id: None, purpose: "PRs".into(), approved_by: "owner".into() }).unwrap();
        let (add, _) = r.select(&ctx);
        assert_eq!(add.len(), 1);
        let list = r.list_view(None).to_string();
        assert!(list.contains("Authorization") && !list.contains("s3cr3t"));
        let (_, plan, _) = r.handle(frames::PLAN, Request { session_id: Some("s1".into()), ..Default::default() }, false).unwrap();
        assert_eq!(plan["add"], json!(["infantry", "github"]));
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn changes_are_owner_only_and_validated() {
        let dir = std::env::temp_dir().join(format!("infantry-mcp-{}", uuid::Uuid::new_v4()));
        let r = reg(&dir);
        let add = |s: Value| Request { server: Some(server(s)), ..Default::default() };
        assert_eq!(r.handle(frames::ADD, add(json!({"name": "a", "command": ["x"]})), false).unwrap_err(), "owner only");
        assert!(r.handle(frames::ADD, add(json!({"name": "a", "command": ["x"], "env": {"API_KEY": "v"}})), true).unwrap_err().contains("looks like a credential"));
        assert!(r.handle(frames::ADD, add(json!({"name": "infantry", "command": ["x"]})), true).unwrap_err().contains("daemon's own"));
        let (_, _, changed) = r.handle(frames::ADD, add(json!({"name": "a", "command": ["x"]})), true).unwrap();
        assert!(changed);
        let set = Request { name: Some("a".into()), enabled: Some(false), ..Default::default() };
        let (_, v, _) = r.handle(frames::SET_ENABLED, set, true).unwrap();
        assert_eq!(v["servers"][1]["state"], "disabled");
        let _ = std::fs::remove_dir_all(dir);
    }
}
