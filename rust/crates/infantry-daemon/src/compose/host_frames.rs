// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The frames plugin guests ask the host for (RUST-PLUGINS-A "HOST NEEDS"), served from what the daemon already holds:
//!
//! - `session_get` `{"sessionId"|"id", "lines"?}` -> `session`: the record plus `transcript` (the replay buffer tail)
//!   and `transcriptTruncated`.
//! - `session_jobs` `{"sessionId"}` -> `jobs` `[{id,label,startedAt,running,exitCode?}]`; `session_job_log`
//!   `{"sessionId","jobId","from","maxBytes"}` -> `job_log` `{text,nextOffset,size,reset,job}`.
//! - `module_cards` `{}` -> `cards` `[{type,module,summary,spec,hosts}]` from every module.json in the plugins folder
//!   (spec resolved to text: a file inside the module, else the text; the first declaration of a type wins).
//! - `agent_registry` `{"op": list|get|enable|disable, "id"?}` -> `agent_registry`: the role catalog, then the modules'
//!   `agents` blocks, under the user's enable/disable overrides (`agent-overrides.json` in the state dir).
//! - `plugins_op` `{"op": list|status|search|show|import|request, ...}` -> `plugins`: the plugin catalog of this host
//!   (the plugins folder, the loaded daemon halves and the imported list files). `request` asks the owner and installs
//!   nothing. A `list` row's `ui.web` is the module's web UI half, served by [super::ui_bundles] (WEBUI-1).
use crate::sessions::Daemon;
use infantry_core::frames::{Envelope, FrameCtx, FrameUnit, Refusal};
use infantry_mail::notify::questions::{Asker, OwnerQuestions};
use infantry_protocol::prompts::{PromptOption, QuestionAsk};
use serde_json::{json, Value};
use std::io::{Read, Seek, SeekFrom};
use std::path::{Path, PathBuf};
use std::sync::Arc;

pub const SESSION_GET: &str = "session_get";
pub const SESSION_JOBS: &str = "session_jobs";
pub const SESSION_JOB_LOG: &str = "session_job_log";
pub const MODULE_CARDS: &str = "module_cards";
pub const AGENT_REGISTRY: &str = "agent_registry";
pub const PLUGINS_OP: &str = "plugins_op";
const FRAMES: &[&str] = &[SESSION_GET, SESSION_JOBS, SESSION_JOB_LOG, MODULE_CARDS, AGENT_REGISTRY, PLUGINS_OP, super::project_files::PROJECT_FILES, infantry_protocol::frames::SESSION_PROJECT];

/// Transcript frames `session_get` returns when the caller names no `lines`.
pub const TRANSCRIPT_LINES: usize = 40;
/// The most bytes one `session_job_log` read returns when the caller names no `maxBytes`, and its ceiling.
pub const JOB_LOG_BYTES: u64 = 16_384;
pub const JOB_LOG_BYTES_MAX: u64 = 262_144;
/// The module tiers the catalog scans, in precedence order.
const TIERS: &[&str] = &["official", "core", "community"];
/// State-dir files: the user's agent overrides and the imported plugin list locations.
pub const AGENT_OVERRIDES_FILE: &str = "agent-overrides.json";
pub const PLUGIN_LISTS_FILE: &str = "plugin-lists.json";
/// The owner's answers to a plugin request.
pub const INSTALL: &str = "Install";
pub const DECLINE: &str = "Decline";

pub struct HostFrames {
    pub daemon: Arc<Daemon>,
    pub plugins_dir: Option<PathBuf>,
    /// The agent registry (`agent_registry` and the `infantry_agent_*` tools share it).
    pub registry: Arc<super::agent_registry::AgentRegistry>,
    pub questions: Arc<OwnerQuestions>,
    pub state_dir: PathBuf,
    /// For `project_files` (a plugin's files under `<project>/.infantry/`).
    pub projects: Arc<infantry_projects::store::ProjectStore>,
    /// The enable rule the list reports, shared with the web UI route (WEBUI-1).
    pub bundles: Arc<super::ui_bundles::UiBundles>,
    /// The bundled folder ([`super::installer::InstallConfig::bundled`]): a module it offers is `bundled` (ships with the
    /// app); one installed from a user source is not, and its card offers Uninstall.
    pub bundled_dir: Option<PathBuf>,
}

/// One module.json found in the plugins folder.
pub(super) struct Module {
    pub(super) id: String,
    pub(super) tier: String,
    pub(super) dir: PathBuf,
    pub(super) manifest: Value,
}

/// Every module.json in the plugins folder, tiers in precedence order (the first id wins).
pub(super) fn scan_modules(plugins_dir: &Option<PathBuf>) -> Vec<Module> {
    let Some(root) = plugins_dir else { return vec![] };
    let mut out: Vec<Module> = vec![];
    for tier in TIERS {
        let Ok(rd) = std::fs::read_dir(root.join(tier)) else { continue };
        let mut dirs: Vec<PathBuf> = rd.filter_map(|e| e.ok()).map(|e| e.path()).filter(|p| p.join("module.json").is_file()).collect();
        dirs.sort();
        for dir in dirs {
            let Some(manifest) = std::fs::read_to_string(dir.join("module.json")).ok().and_then(|t| serde_json::from_str::<Value>(&t).ok()) else { continue };
            let id = Some(s(&manifest, "name")).filter(|n| !n.is_empty()).map(String::from).or_else(|| dir.file_name().map(|n| n.to_string_lossy().into_owned())).unwrap_or_default();
            if out.iter().any(|m| m.id == id) {
                continue;
            }
            out.push(Module { id, tier: tier.to_string(), dir, manifest });
        }
    }
    out
}


/// FRAME-REFUSAL-WORDING-1: the display name (else the id) of the module whose `daemon.frames` declares `ty`.
pub(super) fn frame_provider(plugins_dir: &Option<PathBuf>, ty: &str) -> Option<String> {
    scan_modules(plugins_dir).into_iter().find_map(|m| {
        let declares = m.manifest.get("daemon").and_then(|d| d.get("frames")).and_then(Value::as_array).is_some_and(|a| a.iter().any(|f| s(f, "type") == ty));
        declares.then(|| Some(s(&m.manifest, "displayName").to_string()).filter(|n| !n.is_empty()).unwrap_or(m.id))
    })
}

#[cfg(test)]
mod frame_provider_tests {
    #[test]
    fn finds_the_module_declaring_the_frame() {
        let root = std::env::temp_dir().join(format!("fp-{}", std::process::id()));
        let dir = root.join(super::TIERS[0]).join("Chat");
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("module.json"), r#"{"name":"Infantry_Chat","displayName":"Chat","daemon":{"frames":[{"type":"image_upload"}]}}"#).unwrap();
        let d = Some(root.clone());
        assert_eq!(super::frame_provider(&d, "image_upload").as_deref(), Some("Chat"));
        assert_eq!(super::frame_provider(&d, "nope"), None);
        let _ = std::fs::remove_dir_all(root);
    }
}

pub(super) fn s<'a>(v: &'a Value, k: &str) -> &'a str {
    v.get(k).and_then(Value::as_str).map(str::trim).unwrap_or("")
}

fn strings(v: Option<&Value>) -> Vec<String> {
    v.and_then(Value::as_array).map(|a| a.iter().filter_map(|x| x.as_str().map(String::from)).collect()).unwrap_or_default()
}

/// What a module declares for the client shell (RUST-UI-5): its tabs, panels, fills and slots exactly as module.json
/// holds them (with its web UI half's tab and panel contributions folded into `tabs`), plus its display name,
/// description and whether it declares settings. The shell renders from this.
pub fn ui_of(manifest: &Value) -> Value {
    let arr = |k: &str| manifest.get(k).filter(|v| v.is_array()).cloned().unwrap_or_else(|| Value::Array(vec![]));
    let mut tabs = manifest.get("tabs").and_then(Value::as_array).cloned().unwrap_or_default();
    super::ui_bundles::fold_tabs(manifest, &mut tabs);
    json!({
        "displayName": s(manifest, "displayName"), "description": s(manifest, "description"),
        "tabs": tabs, "panels": arr("panels"), "fills": arr("fills"), "slots": arr("slots"),
        "config": manifest.get("config").is_some_and(|c| !c.is_null()),
        // The settings nodes this module declares, so a client can show one plugin's cards.
        "configNodes": manifest.get("config").and_then(Value::as_array).map(|a| a.iter().filter_map(|g| g.get("node").and_then(Value::as_str)).map(Value::from).collect::<Vec<_>>()).unwrap_or_default(),
    })
}

fn reply(ty: &str, env: &Envelope, data: String) -> Vec<Envelope> {
    let mut e = Envelope::new(ty, data);
    e.session_id = env.session_id.clone();
    e.request_id = env.request_id.clone();
    vec![e]
}

impl HostFrames {
    fn modules(&self) -> Vec<Module> {
        scan_modules(&self.plugins_dir)
    }

    // ---- sessions --------------------------------------------------------------------------------------------

    fn session_of(env: &Envelope, d: &Value) -> Option<String> {
        [s(d, "sessionId"), s(d, "id")].into_iter().find(|v| !v.is_empty()).map(String::from).or_else(|| env.session_id.clone().filter(|v| !v.trim().is_empty()))
    }

    fn session_get(&self, env: &Envelope, d: &Value) -> Result<Vec<Envelope>, Refusal> {
        let sid = Self::session_of(env, d).ok_or_else(|| Refusal::new(SESSION_GET, "sessionId is required"))?;
        let rec = self.daemon.record(&sid).ok_or_else(|| Refusal::new(SESSION_GET, format!("unknown session {sid}")))?;
        let mut v = serde_json::to_value(&rec).unwrap_or(Value::Null);
        let brief = d.get("brief").and_then(Value::as_bool) == Some(true);
        let lines = d.get("lines").and_then(Value::as_u64).map(|n| n as usize).unwrap_or(TRANSCRIPT_LINES);
        if let Some(o) = v.as_object_mut() {
            let (tail, truncated) = if brief { (vec![], false) } else { self.daemon.transcript_tail(&sid, lines).unwrap_or_default() };
            o.insert("transcript".into(), Value::Array(tail));
            o.insert("transcriptTruncated".into(), truncated.into());
        }
        Ok(reply(crate::wire::SESSION, env, v.to_string()))
    }

    fn job_json(j: &infantry_protocol::session::BgJob) -> Value {
        json!({ "id": j.id, "label": j.label, "startedAt": j.started_at, "running": crate::health::pid_alive(j.pid), "logPath": j.log_path })
    }

    fn session_jobs(&self, env: &Envelope, d: &Value) -> Result<Vec<Envelope>, Refusal> {
        let sid = Self::session_of(env, d).ok_or_else(|| Refusal::new(SESSION_JOBS, "sessionId is required"))?;
        let jobs: Vec<Value> = self.daemon.record(&sid).map(|r| r.bg_jobs.iter().map(Self::job_json).collect()).unwrap_or_default();
        Ok(reply("jobs", env, Value::Array(jobs).to_string()))
    }

    fn session_job_log(&self, env: &Envelope, d: &Value) -> Result<Vec<Envelope>, Refusal> {
        let sid = Self::session_of(env, d).ok_or_else(|| Refusal::new(SESSION_JOB_LOG, "sessionId is required"))?;
        let job_id = s(d, "jobId");
        let rec = self.daemon.record(&sid).ok_or_else(|| Refusal::new(SESSION_JOB_LOG, format!("unknown session {sid}")))?;
        let job = rec.bg_jobs.iter().find(|j| j.id == job_id).ok_or_else(|| Refusal::new(SESSION_JOB_LOG, format!("unknown job {job_id}")))?;
        let path = job.log_path.as_deref().filter(|p| !p.trim().is_empty()).ok_or_else(|| Refusal::new(SESSION_JOB_LOG, format!("job {job_id} keeps no log")))?;
        let from = d.get("from").and_then(Value::as_u64).unwrap_or(0);
        let max = d.get("maxBytes").and_then(Value::as_u64).unwrap_or(JOB_LOG_BYTES).clamp(1, JOB_LOG_BYTES_MAX);
        let (text, next, size, reset) = read_chunk(Path::new(path), from, max).map_err(|e| Refusal::new(SESSION_JOB_LOG, e))?;
        let data = json!({ "text": text, "nextOffset": next, "size": size, "reset": reset, "job": Self::job_json(job) });
        Ok(reply("job_log", env, data.to_string()))
    }

    // ---- cards -----------------------------------------------------------------------------------------------

    fn module_cards(&self, env: &Envelope) -> Vec<Envelope> {
        let mut out: Vec<Value> = vec![];
        for m in self.modules() {
            for c in m.manifest.get("cards").and_then(Value::as_array).into_iter().flatten() {
                let ty = s(c, "type");
                if ty.is_empty() || out.iter().any(|o| o["type"] == ty) {
                    continue;
                }
                let spec = s(c, "spec");
                let file = m.dir.join(spec);
                let inside = file.canonicalize().ok().zip(m.dir.canonicalize().ok()).is_some_and(|(f, d)| f.starts_with(d));
                let text = if !spec.is_empty() && inside && file.is_file() { std::fs::read_to_string(&file).unwrap_or_default() } else { spec.to_string() };
                let hosts = strings(c.get("images").and_then(|i| i.get("hosts")));
                out.push(json!({ "type": ty, "module": m.id, "summary": s(c, "summary"), "spec": text, "hosts": hosts }));
            }
        }
        reply("cards", env, Value::Array(out).to_string())
    }

    // ---- agent registry ------------------------------------------------------------------------------------

    fn agent_registry(&self, env: &Envelope, d: &Value, ctx: &FrameCtx) -> Result<Vec<Envelope>, Refusal> {
        let data = self.registry.op(s(d, "op"), s(d, "id"), ctx.owner).map_err(|m| Refusal::new(AGENT_REGISTRY, m))?;
        Ok(reply(AGENT_REGISTRY, env, data.to_string()))
    }

    // ---- plugins ---------------------------------------------------------------------------------------------

    /// The ids the bundled folder offers (empty when there is none).
    fn bundled_ids(&self) -> std::collections::BTreeSet<String> {
        super::installer::bundled_ids(self.bundled_dir.as_deref())
    }

    fn summaries(&self) -> Value {
        let bundled = self.bundled_ids();
        Value::Array(self.modules().iter().map(|m| self.summary(m, &bundled)).collect())
    }

    fn summary(&self, m: &Module, bundled: &std::collections::BTreeSet<String>) -> Value {
        let statuses = self.bundles.statuses();
        let daemon = m.manifest.get("daemon");
        let status = statuses.get(&m.id).cloned();
        let has_daemon = super::ui_bundles::has_daemon(&m.manifest);
        let enabled = self.bundles.enabled(m, &statuses);
        let mut ui = ui_of(&m.manifest);
        ui["web"] = super::ui_bundles::web_row(m);
        let is_bundled = m.tier != "community" && bundled.contains(&m.id);
        json!({
            "id": m.id, "version": s(&m.manifest, "version"), "class": Some(s(&m.manifest, "class")).filter(|c| !c.is_empty()).unwrap_or("plugin"),
            "tier": m.tier, "bundled": is_bundled, "enabled": enabled,
            "grants": strings(daemon.and_then(|d| d.get("needs"))), "requested": strings(daemon.and_then(|d| d.get("wants"))),
            "license": s(&m.manifest, "license"), "source": if is_bundled { "bundled" } else { "installed" }, "yanked": false, "updateTo": Value::Null,
            "status": status.unwrap_or_else(|| if has_daemon { "not loaded".into() } else if enabled { "ui only".into() } else { "disabled".into() }),
            "ui": ui,
        })
    }

    fn lists(&self) -> Vec<String> {
        std::fs::read_to_string(self.state_dir.join(PLUGIN_LISTS_FILE)).ok().and_then(|t| serde_json::from_str(&t).ok()).unwrap_or_default()
    }

    /// Every catalog item: the local modules, then each imported list's `plugins` entries (an id seen first wins).
    fn catalog(&self) -> Vec<Value> {
        let mut out: Vec<Value> = self
            .modules()
            .iter()
            .map(|m| {
                let d = m.manifest.get("daemon");
                json!({
                    "id": m.id, "name": s(&m.manifest, "displayName"), "description": s(&m.manifest, "description"), "tags": strings(m.manifest.get("tags")),
                    "class": Some(s(&m.manifest, "class")).filter(|c| !c.is_empty()).unwrap_or("plugin"), "source": "bundled",
                    "license": s(&m.manifest, "license"), "latest": s(&m.manifest, "version"), "grants": strings(d.and_then(|d| d.get("needs"))),
                    "signer": Value::Null, "repo": Value::Null, "list": "local",
                })
            })
            .collect();
        for loc in self.lists() {
            let Some(v) = std::fs::read_to_string(&loc).ok().and_then(|t| serde_json::from_str::<Value>(&t).ok()) else { continue };
            for p in v.get("plugins").and_then(Value::as_array).into_iter().flatten() {
                if s(p, "id").is_empty() || out.iter().any(|o| o["id"] == p["id"]) {
                    continue;
                }
                let mut item = p.clone();
                if let Some(o) = item.as_object_mut() {
                    o.insert("list".into(), loc.clone().into());
                }
                out.push(item);
            }
        }
        out
    }

    fn plugins_op(&self, env: &Envelope, d: &Value, ctx: &FrameCtx) -> Result<Vec<Envelope>, Refusal> {
        let r = |why: String| Refusal::new(PLUGINS_OP, why);
        let id = Some(s(d, "id")).filter(|v| !v.is_empty()).or_else(|| Some(s(d, "ref")).filter(|v| !v.is_empty()));
        let data = match s(d, "op") {
            "" | "list" => self.summaries(),
            "status" => match id {
                None => self.summaries(),
                Some(id) => self.modules().iter().find(|m| m.id == id).map(|m| self.summary(m, &self.bundled_ids())).ok_or_else(|| r(format!("no plugin '{id}' on this host")))?,
            },
            "search" => {
                let q = s(d, "query").to_lowercase();
                let hit = |v: &Value| q.is_empty() || ["id", "name", "description"].iter().any(|k| s(v, k).to_lowercase().contains(&q)) || strings(v.get("tags")).iter().any(|t| t.to_lowercase().contains(&q));
                Value::Array(self.catalog().into_iter().filter(hit).collect())
            }
            "show" => {
                let id = id.ok_or_else(|| r("id is required".into()))?;
                let item = self.catalog().into_iter().find(|v| v["id"] == id).ok_or_else(|| r(format!("no plugin '{id}' in the catalog")))?;
                let local = self.modules().into_iter().find(|m| m.id == id);
                json!({
                    "id": id, "version": local.as_ref().map(|m| s(&m.manifest, "version").to_string()).unwrap_or_else(|| s(&item, "latest").into()),
                    "class": item["class"], "tier": local.as_ref().map(|m| m.tier.clone()).unwrap_or_else(|| "community".into()),
                    "license": item["license"], "signer": item["signer"], "source": item["source"], "grants": item["grants"], "warnings": [],
                })
            }
            "import" => {
                if !ctx.owner {
                    return Err(r("owner only".into()));
                }
                let loc = s(d, "location");
                let loc = loc.strip_prefix("file://").unwrap_or(loc);
                if loc.is_empty() {
                    return Err(r("location is required".into()));
                }
                if loc.contains("://") {
                    return Err(r(format!("{loc}: this daemon imports a list from a local file only")));
                }
                let n = std::fs::read_to_string(loc).ok().and_then(|t| serde_json::from_str::<Value>(&t).ok()).and_then(|v| v.get("plugins").and_then(Value::as_array).map(Vec::len)).ok_or_else(|| r(format!("{loc} is not a plugin list (a JSON object with a `plugins` array)")))?;
                let mut lists = self.lists();
                if !lists.iter().any(|l| l == loc) {
                    lists.push(loc.to_string());
                }
                write_json(&self.state_dir.join(PLUGIN_LISTS_FILE), &json!(lists)).map_err(r)?;
                json!({ "message": format!("imported {loc}: {n} plugin(s)") })
            }
            "request" => {
                let id = id.ok_or_else(|| r("id is required".into()))?;
                let item = self.catalog().into_iter().find(|v| v["id"] == id).ok_or_else(|| r(format!("no plugin '{id}' in the catalog")))?;
                let sid = Self::session_of(env, d).unwrap_or_default();
                let rec = self.daemon.record(&sid);
                let asker = Asker { session_id: sid.clone(), agent_id: rec.as_ref().and_then(|r| r.agent_id.clone()), role: rec.as_ref().and_then(|r| r.role.clone()), project_id: rec.and_then(|r| r.project_id) };
                let grants = strings(item.get("grants"));
                let why = Some(s(d, "reason")).filter(|v| !v.is_empty()).unwrap_or("an agent asked for it");
                let ask = QuestionAsk {
                    question: format!("Install plugin {id}? It asks for: {}.", if grants.is_empty() { "no grants".to_string() } else { grants.join(", ") }),
                    options: vec![
                        PromptOption { label: INSTALL.into(), description: "install it with exactly these grants".into() },
                        PromptOption { label: DECLINE.into(), description: "do not install it".into() },
                    ],
                    recommended: DECLINE.into(),
                    reason: why.chars().take(QuestionAsk::REASON_MAX).collect(),
                    allow_other: false,
                    default_after_min: None,
                };
                let q = self.questions.ask(&asker, &ask).map_err(r)?;
                json!({ "questionId": q.id, "message": format!("asked the owner to install {id}; nothing is installed until they answer") })
            }
            other => return Err(r(format!("unknown op '{other}' (list, status, search, show, import, request)"))),
        };
        Ok(reply("plugins", env, data.to_string()))
    }
}

/// Reads `[from, from + max)` of `path`: (text, next offset, size, reset). A `from` past the end (the log was cut or
/// rotated) starts again at 0 with `reset`.
pub fn read_chunk(path: &Path, from: u64, max: u64) -> Result<(String, u64, u64, bool), String> {
    let mut f = std::fs::File::open(path).map_err(|e| format!("{}: {e}", path.display()))?;
    let size = f.metadata().map(|m| m.len()).unwrap_or(0);
    let (start, reset) = if from > size { (0, true) } else { (from, false) };
    f.seek(SeekFrom::Start(start)).map_err(|e| e.to_string())?;
    let mut buf = Vec::new();
    f.take(max).read_to_end(&mut buf).map_err(|e| e.to_string())?;
    // Never end inside a UTF-8 sequence: hold the partial tail back for the next read.
    let cut = match std::str::from_utf8(&buf) {
        Ok(_) => buf.len(),
        Err(e) if e.error_len().is_none() => e.valid_up_to(),
        Err(_) => buf.len(),
    };
    Ok((String::from_utf8_lossy(&buf[..cut]).into_owned(), start + cut as u64, size, reset))
}

pub(super) fn write_json(path: &Path, v: &Value) -> Result<(), String> {
    let tmp = path.with_extension("tmp");
    std::fs::write(&tmp, serde_json::to_vec_pretty(v).unwrap_or_default()).map_err(|e| e.to_string())?;
    std::fs::rename(&tmp, path).map_err(|e| e.to_string())
}

impl FrameUnit for HostFrames {
    fn name(&self) -> &'static str {
        "host-frames"
    }
    fn frames(&self) -> &'static [&'static str] {
        FRAMES
    }
    fn handle(&self, env: &Envelope, ctx: &FrameCtx) -> Result<Vec<Envelope>, Refusal> {
        let d: Value = serde_json::from_str(if env.data.trim().is_empty() { "{}" } else { &env.data }).unwrap_or(Value::Null);
        match env.r#type.as_str() {
            SESSION_GET => self.session_get(env, &d),
            SESSION_JOBS => self.session_jobs(env, &d),
            SESSION_JOB_LOG => self.session_job_log(env, &d),
            MODULE_CARDS => Ok(self.module_cards(env)),
            AGENT_REGISTRY => self.agent_registry(env, &d, ctx),
            PLUGINS_OP => self.plugins_op(env, &d, ctx),
            // `session_project` `{sessionId, projectId}`: no answer; clients see the session push.
            infantry_protocol::frames::SESSION_PROJECT => {
                let t = infantry_protocol::frames::SESSION_PROJECT;
                let sid = d["sessionId"].as_str().unwrap_or("").trim().to_string();
                let pid = d["projectId"].as_str().unwrap_or("").trim().to_string();
                if sid.is_empty() {
                    return Err(Refusal::new(t, "expects a sessionId"));
                }
                if self.projects.get(&pid).is_err() {
                    return Err(Refusal::new(t, format!("no project '{}'", pid.chars().take(80).collect::<String>())));
                }
                self.daemon.set_project(&sid, &pid).map_err(|e| Refusal::new(t, e))?;
                Ok(Vec::new())
            }
            // In-process callers only (a plugin guest, conn 0): a client or agent connection never writes these files.
            super::project_files::PROJECT_FILES if ctx.conn_id != 0 => Err(Refusal::new(super::project_files::PROJECT_FILES, "only a plugin may ask")),
            super::project_files::PROJECT_FILES => super::project_files::answer(&self.projects, &env.data)
                .map(|v| reply(super::project_files::PROJECT_FILES, env, v.to_string()))
                .map_err(|e| Refusal::new(super::project_files::PROJECT_FILES, e)),
            other => Err(Refusal::new(other, "not a host frame")),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::read_chunk;

    #[test]
    fn ui_of_carries_the_shell_declarations_and_config_nodes() {
        let m = serde_json::json!({
            "name": "X", "displayName": "Ex", "tabs": [{"id": "x.tab", "container": "center"}], "fills": ["infantry.shell.footer.badge"],
            "config": [{"node": "x.node", "group": "G"}, {"group": "no node"}],
        });
        let u = super::ui_of(&m);
        assert_eq!(u["displayName"], "Ex");
        assert_eq!(u["tabs"][0]["id"], "x.tab");
        assert_eq!(u["fills"][0], "infantry.shell.footer.badge");
        assert_eq!(u["panels"], serde_json::json!([]));
        assert_eq!(u["config"], true);
        assert_eq!(u["configNodes"], serde_json::json!(["x.node"]));
        assert_eq!(super::ui_of(&serde_json::json!({}))["config"], false);
    }

    #[test]
    fn job_log_reads_from_the_offset_and_resets_past_the_end() {
        let dir = std::env::temp_dir().join(format!("infantry-jl-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let p = dir.join("job.log");
        std::fs::write(&p, "hello world").unwrap();
        assert_eq!(read_chunk(&p, 6, 100).unwrap(), ("world".into(), 11, 11, false));
        assert_eq!(read_chunk(&p, 0, 5).unwrap(), ("hello".into(), 5, 11, false));
        assert_eq!(read_chunk(&p, 99, 5).unwrap(), ("hello".into(), 5, 11, true));
        std::fs::write(&p, "a\u{e9}").unwrap(); // 'é' is 2 bytes: a 2-byte read stops before it
        assert_eq!(read_chunk(&p, 0, 2).unwrap(), ("a".into(), 1, 3, false));
        let _ = std::fs::remove_dir_all(&dir);
    }
}
