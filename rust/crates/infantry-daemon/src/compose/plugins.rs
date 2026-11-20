// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The plugin host: loads every installed daemon half (`<plugins>/official/<Module>`, `<plugins>/core/<Module>`),
//! answers their imports from the daemon ([`DaemonServices`]), registers their frames and hands them bus events.
//! `plugin_admin` enables and disables them, and the modules with no daemon half (a web UI half, declarations), for good.

use super::Composed;
use infantry_core::frames::{Envelope, FrameCtx, FrameUnit, Refusal};
use infantry_core::bus::EventBus;
use infantry_plugin_host::unit::PluginFrameUnit;
use infantry_plugin_host::{CallContext, Frame, HostServices, NamedEvent, PluginHost};
use infantry_plugin_wit::jsonl::{HttpRequest, HttpResponse, LogLevel};
use std::collections::{BTreeMap, BTreeSet};
use std::io::Read;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, OnceLock, Weak};
use std::time::Duration;

/// The tiers loaded from the plugins folder (community modules need the owner's modules.json allow list: not yet).
pub const TIERS: &[&str] = &["official", "core"];
/// Plugin settings (`<Module>.key` -> value), until the settings unit moves.
pub const SETTINGS_FILE: &str = "plugin-settings.json";
/// The longest a plugin's outbound HTTP call may take when it names no timeout.
pub const HTTP_TIMEOUT_MS: u64 = 30_000;
/// The modules the owner disabled (a JSON array of ids): not loaded at start, until `plugin_admin enable`.
pub const DISABLED_FILE: &str = "plugins-disabled.json";
/// The owner's plugin manager frame: `{"args": [op, id?]}` -> `plugin_admin` `{code, out, err}`.
pub const PLUGIN_ADMIN: &str = "plugin_admin";
/// The named event after a module is enabled or disabled: `{"modules": [loaded ids]}`.
pub const MODULES_CHANGED: &str = "modules.changed";

/// The modules the owner disabled ([DISABLED_FILE] in the state dir): a daemon half is not loaded, any other module is
/// reported disabled (its tabs leave the app, its web UI half is no longer served).
pub struct Disabled {
    path: PathBuf,
    set: Mutex<BTreeSet<String>>,
}

impl Disabled {
    pub fn load(state_dir: &Path) -> Disabled {
        let path = state_dir.join(DISABLED_FILE);
        let set = std::fs::read_to_string(&path).ok().and_then(|t| serde_json::from_str(&t).ok()).unwrap_or_default();
        Disabled { path, set: Mutex::new(set) }
    }

    pub fn contains(&self, id: &str) -> bool {
        self.set.lock().unwrap_or_else(|e| e.into_inner()).contains(id)
    }

    pub fn snapshot(&self) -> BTreeSet<String> {
        self.set.lock().unwrap_or_else(|e| e.into_inner()).clone()
    }

    /// Marks [id] disabled (or not) and writes the file; on a write failure nothing changes.
    pub fn set(&self, id: &str, disabled: bool) -> Result<(), String> {
        let mut guard = self.set.lock().unwrap_or_else(|e| e.into_inner());
        let mut next = guard.clone();
        if disabled {
            next.insert(id.to_string());
        } else {
            next.remove(id);
        }
        std::fs::write(&self.path, serde_json::to_string_pretty(&next).unwrap_or_default()).map_err(|e| format!("{}: {e}", self.path.display()))?;
        *guard = next;
        Ok(())
    }
}

/// An installed daemon half: its folder and the frames its manifest declares.
#[derive(Debug, Clone)]
pub struct Installed {
    pub dir: PathBuf,
    pub frames: Vec<String>,
}

pub struct Plugins {
    pub host: Arc<PluginHost>,
    services: Arc<DaemonServices>,
    pub admin: Arc<Admin>,
}

/// What `plugin_admin` manages: the installed daemon halves, the modules with none, and the owner's disabled set.
pub struct Admin {
    host: Arc<PluginHost>,
    plugins_dir: PathBuf,
    installed: BTreeMap<String, Installed>,
    disabled: Arc<Disabled>,
}

impl Admin {
    /// The official and core modules with no daemon half (a web UI half, or declarations only).
    fn without_daemon(&self) -> Vec<String> {
        super::host_frames::scan_modules(&Some(self.plugins_dir.clone())).into_iter().filter(|m| TIERS.contains(&m.tier.as_str()) && !self.installed.contains_key(&m.id)).map(|m| m.id).collect()
    }

    /// After an enable or disable: the plugins hear `modules.changed`, every client gets `plugins_changed`.
    fn changed(&self, bus: &EventBus, id: &str, enabled: bool) {
        let loaded: Vec<String> = self.host.loaded().iter().map(|p| p.id.clone()).collect();
        bus.emit(MODULES_CHANGED, serde_json::json!({ "modules": loaded }));
        bus.publish(Envelope::new(infantry_protocol::frames::PLUGINS_CHANGED, serde_json::json!({ "id": id, "enabled": enabled }).to_string()));
    }

    /// One `plugin_admin` command: (exit code, out, err).
    pub fn admin(&self, args: &[String], bus: &EventBus) -> (i32, String, String) {
        let op = args.first().map(String::as_str).unwrap_or("");
        let id = args.get(1).map(|s| s.trim()).unwrap_or("");
        let fail = |e: String| (1, String::new(), e);
        match op {
            "status" | "list" => {
                let statuses = self.host.statuses();
                let disabled = self.disabled.snapshot();
                let mut rows: Vec<serde_json::Value> = self
                    .installed
                    .keys()
                    .filter(|m| id.is_empty() || m.as_str() == id)
                    .map(|m| {
                        let loaded = self.host.get(m);
                        let status = statuses.get(m).cloned().unwrap_or_else(|| if disabled.contains(m) { "disabled".into() } else { "not loaded".into() });
                        serde_json::json!({
                            "id": m,
                            "status": status,
                            "enabled": !disabled.contains(m),
                            "fault": loaded.as_ref().and_then(|p| p.guard.disabled_reason()),
                            "cooling": loaded.as_ref().is_some_and(|p| p.guard.is_cooling()),
                        })
                    })
                    .collect();
                rows.extend(self.without_daemon().into_iter().filter(|m| id.is_empty() || m == id).map(|m| {
                    let off = disabled.contains(&m);
                    serde_json::json!({ "id": m, "status": if off { "disabled" } else { "ui only" }, "enabled": !off, "fault": null, "cooling": false })
                }));
                if !id.is_empty() && rows.is_empty() {
                    return fail(format!("no plugin '{id}' on this host"));
                }
                (0, serde_json::to_string_pretty(&rows).unwrap_or_default(), String::new())
            }
            "enable" | "disable" => {
                let on = op == "enable";
                match self.installed.get(id) {
                    Some(m) if on => {
                        if self.host.get(id).is_none() {
                            if let Err(e) = self.host.load(&m.dir) {
                                return fail(format!("{id} not loaded: {e}"));
                            }
                        }
                    }
                    Some(_) => {
                        self.host.unload(id);
                    }
                    // A module with no daemon half: enabling or disabling it is the mark alone.
                    None if self.without_daemon().iter().any(|m| m == id) => {}
                    None => return fail(format!("no plugin '{id}' on this host")),
                }
                if let Err(e) = self.disabled.set(id, !on) {
                    return fail(e);
                }
                self.changed(bus, id, on);
                (0, format!("{id} {op}d"), String::new())
            }
            "" => fail("usage: plugin <status|enable|disable> [id]".into()),
            other => fail(format!("plugin {other}: not supported by this daemon yet (it serves status, enable, disable)")),
        }
    }
}

/// `plugin_admin` (the Kotlin PluginCli over a frame, owner only): `enable` / `disable` a module on this host (its daemon
/// half is loaded or unloaded; one with none is only marked), kept across restarts; `status` (each installed half: load status, the guard's fault, cooling). Other operations
/// (install, update, remove, rollback, grants) answer exit code 1: this daemon serves only bundled modules.
pub struct PluginAdmin {
    admin: Arc<Admin>,
}

impl FrameUnit for PluginAdmin {
    fn name(&self) -> &'static str {
        PLUGIN_ADMIN
    }
    fn frames(&self) -> &'static [&'static str] {
        &[PLUGIN_ADMIN]
    }
    fn handle(&self, env: &Envelope, ctx: &FrameCtx) -> Result<Vec<Envelope>, Refusal> {
        if !ctx.owner {
            return Err(Refusal::new(PLUGIN_ADMIN, "owner only"));
        }
        let args: Vec<String> = serde_json::from_str::<serde_json::Value>(&env.data)
            .ok()
            .and_then(|v| v.get("args").and_then(|a| a.as_array()).map(|a| a.iter().filter_map(|x| x.as_str().map(String::from)).collect()))
            .ok_or_else(|| Refusal(format!(r#"{PLUGIN_ADMIN}: the data must be {{"args": [...]}}"#)))?;
        let (code, out, err) = self.admin.admin(&args, &ctx.bus);
        let mut reply = Envelope::new(PLUGIN_ADMIN, serde_json::json!({ "code": code, "out": out, "err": err }).to_string());
        reply.request_id = env.request_id.clone();
        Ok(vec![reply])
    }
}

pub struct DaemonServices {
    state_dir: PathBuf,
    bus: EventBus,
    handle: tokio::runtime::Handle,
    settings: Mutex<BTreeMap<String, String>>,
    db: Arc<Mutex<rusqlite::Connection>>,
    composed: OnceLock<Weak<Composed>>,
    http: OnceLock<Result<ureq::Agent, String>>,
}

impl DaemonServices {
    /// One agent for every plugin call, over the system TLS (Windows SChannel).
    fn agent(&self) -> Result<&ureq::Agent, String> {
        self.http
            .get_or_init(|| {
                let tls = native_tls::TlsConnector::new().map_err(|e| format!("no TLS for plugin HTTP calls: {e}"))?;
                Ok(ureq::AgentBuilder::new().tls_connector(Arc::new(tls)).build())
            })
            .as_ref()
            .map_err(|e| e.clone())
    }

    fn save_settings(&self, s: &BTreeMap<String, String>) -> Result<(), String> {
        let text = serde_json::to_string_pretty(s).map_err(|e| e.to_string())?;
        std::fs::write(self.state_dir.join(SETTINGS_FILE), text).map_err(|e| e.to_string())
    }
}

impl HostServices for DaemonServices {
    fn log(&self, module: &str, level: LogLevel, line: &str) {
        eprintln!("[plugin:{module}] {level:?} {line}");
    }
    fn setting_get(&self, key: &str) -> Option<String> {
        self.settings.lock().unwrap().get(key).cloned()
    }
    fn setting_set(&self, key: &str, value: &str) -> Result<(), String> {
        let mut s = self.settings.lock().unwrap();
        s.insert(key.into(), value.into());
        self.save_settings(&s)
    }
    fn setting_remove(&self, key: &str) -> Result<(), String> {
        let mut s = self.settings.lock().unwrap();
        s.remove(key);
        self.save_settings(&s)
    }
    fn frame_request(&self, _module: &str, frame: Frame, caller: Option<&CallContext>, _timeout_ms: u32) -> Result<Frame, String> {
        let c = self.composed.get().and_then(Weak::upgrade).ok_or("the daemon is still starting")?;
        let max_depth = self.setting_get(super::scope::MAX_DEPTH_KEY).and_then(|v| v.trim().parse().ok()).unwrap_or(super::scope::MAX_DEPTH);
        if let Some(why) = super::scope::refusal(&frame.r#type, &frame.data, frame.session_id.as_deref(), caller, &c.daemon.records(), max_depth) {
            return Err(why);
        }
        let _rt = self.handle.enter();
        let mut env = Envelope::new(frame.r#type.clone(), frame.data);
        env.session_id = frame.session_id;
        // An in-process request always wants its answer: units that answer only a request id (the board) do.
        env.request_id = Some(frame.request_id.clone().unwrap_or_else(|| format!("plugin-{}", uuid::Uuid::new_v4())));
        let mut bound = None;
        let replies: Vec<Envelope> = if crate::sessions::owns(&env.r#type) {
            let mut conn = crate::sessions::Conn::default();
            let out: Vec<Envelope> = c.daemon.handle(env, &mut conn).iter().filter_map(|t| serde_json::from_str(t).ok()).collect();
            bound = conn.active;
            out
        } else {
            c.request(&env, true)?
        };
        // A `new` answers its requester nothing (clients see the pushes): the session it bound is the plugin's answer.
        if let (true, Some(id)) = (replies.iter().all(|e| e.r#type != crate::wire::ERROR), bound.filter(|_| frame.r#type == crate::wire::NEW)) {
            return Ok(Frame { r#type: crate::wire::SESSION.into(), data: serde_json::json!({ "id": id }).to_string(), session_id: Some(id), request_id: frame.request_id });
        }
        match replies.into_iter().next() {
            Some(e) if e.r#type == crate::wire::ERROR => Err(e.data),
            Some(e) => Ok(Frame { r#type: e.r#type, data: e.data, session_id: e.session_id, request_id: e.request_id.or(frame.request_id) }),
            None => Ok(Frame { r#type: format!("{}_ok", frame.r#type), data: String::new(), session_id: None, request_id: frame.request_id }),
        }
    }
    fn publish(&self, module: &str, name: &str, payload: &str, cause_id: Option<&str>) -> Result<NamedEvent, String> {
        let id = uuid::Uuid::new_v4().to_string();
        let mut v: serde_json::Value = serde_json::from_str(payload).unwrap_or(serde_json::Value::Null);
        if let Some(o) = v.as_object_mut() {
            o.insert("_eventId".into(), id.clone().into());
            o.insert("_source".into(), module.into());
            if let Some(c) = cause_id {
                o.insert("_causeId".into(), c.into());
            }
        }
        self.bus.emit(name, v);
        Ok(NamedEvent { name: name.into(), payload: payload.into(), source: module.into(), id, cause_id: cause_id.unwrap_or_default().into(), ..Default::default() })
    }
    fn broadcast(&self, _module: &str, r#type: &str, data: &str) {
        self.bus.publish(Envelope::new(r#type, data));
    }
    fn http_send(&self, _module: &str, req: HttpRequest) -> Result<HttpResponse, String> {
        let timeout = req.timeout_ms.map(u64::from).unwrap_or(HTTP_TIMEOUT_MS);
        let mut r = self.agent()?.request(&req.method, &req.url).timeout(Duration::from_millis(timeout));
        for (k, v) in &req.headers {
            r = r.set(k, v);
        }
        let resp = match &req.body {
            Some(b) => r.send_bytes(b),
            None => r.call(),
        };
        let resp = match resp {
            Ok(r) | Err(ureq::Error::Status(_, r)) => r,
            Err(e) => return Err(e.to_string()),
        };
        let status = resp.status();
        let headers = resp.headers_names().into_iter().filter_map(|n| resp.header(&n).map(|v| (n.clone(), v.to_string()))).collect();
        let mut body = Vec::new();
        resp.into_reader().take(64 * 1024 * 1024).read_to_end(&mut body).map_err(|e| e.to_string())?;
        Ok(HttpResponse { status, headers, body })
    }
    fn db(&self) -> Arc<Mutex<rusqlite::Connection>> {
        self.db.clone()
    }
}

/// A folder's module id and declared frames, when its manifest has a daemon half.
fn daemon_half(dir: &Path) -> Option<(String, Vec<String>)> {
    let v: serde_json::Value = serde_json::from_str(&std::fs::read_to_string(dir.join("module.json")).ok()?).ok()?;
    let d = v.get("daemon").filter(|d| d.is_object())?;
    let id = v.get("name").and_then(|n| n.as_str())?.to_string();
    let frames = d.get("frames").and_then(|f| f.as_array()).into_iter().flatten().filter_map(|f| f.get("type").and_then(|t| t.as_str()).map(String::from)).collect();
    Some((id, frames))
}

/// Starts the host and loads every installed daemon half; None when there is no plugins folder or the host failed.
pub fn start(state_dir: &Path, dir: Option<PathBuf>, bus: EventBus, handle: tokio::runtime::Handle, disabled: Arc<Disabled>) -> Option<Plugins> {
    let dir = dir.filter(|d| d.is_dir())?;
    let db = match rusqlite::Connection::open(state_dir.join("infantry.db")) {
        Ok(c) => {
            let _ = c.busy_timeout(Duration::from_secs(5));
            c
        }
        Err(e) => {
            eprintln!("[infantry] plugins off: infantry.db ({e})");
            return None;
        }
    };
    let settings = std::fs::read_to_string(state_dir.join(SETTINGS_FILE)).ok().and_then(|t| serde_json::from_str(&t).ok()).unwrap_or_default();
    let services = Arc::new(DaemonServices {
        state_dir: state_dir.to_path_buf(),
        bus,
        handle,
        settings: Mutex::new(settings),
        db: Arc::new(Mutex::new(db)),
        composed: OnceLock::new(),
        http: OnceLock::new(),
    });
    let host = match PluginHost::new(services.clone(), Default::default()) {
        Ok(h) => Arc::new(h),
        Err(e) => {
            eprintln!("[infantry] plugins off: {e}");
            return None;
        }
    };
    let mut installed = BTreeMap::new();
    for tier in TIERS {
        let Ok(rd) = std::fs::read_dir(dir.join(tier)) else { continue };
        let mut mods: Vec<(PathBuf, String, Vec<String>)> = rd.filter_map(|e| e.ok()).map(|e| e.path()).filter_map(|p| daemon_half(&p).map(|(id, f)| (p, id, f))).collect();
        mods.sort();
        for (m, id, frames) in mods {
            if installed.contains_key(&id) {
                continue;
            }
            installed.insert(id.clone(), Installed { dir: m.clone(), frames });
            if disabled.contains(&id) {
                continue;
            }
            if let Err(e) = host.load(&m) {
                eprintln!("[infantry] plugin {} not loaded: {e}", m.display());
            }
        }
    }
    let admin = Arc::new(Admin { host: host.clone(), plugins_dir: dir, installed, disabled });
    Some(Plugins { host, services, admin })
}

/// One frame unit per installed daemon half that declares frames (loaded or not: one enabled later answers at once),
/// plus the owner's `plugin_admin`.
pub fn frame_units(p: &Plugins) -> Vec<Arc<dyn FrameUnit>> {
    let mut units: Vec<Arc<dyn FrameUnit>> = p
        .admin
        .installed
        .iter()
        .filter(|(_, m)| !m.frames.is_empty())
        .map(|(id, m)| Arc::new(PluginFrameUnit::declared(p.host.clone(), id, &m.frames)) as Arc<dyn FrameUnit>)
        .collect();
    units.push(Arc::new(PluginAdmin { admin: p.admin.clone() }));
    units
}

/// Gives the services the composition (for `frame_request`) and forwards every bus event to the plugins.
pub fn attach(p: &Plugins, c: &Arc<Composed>) {
    let _ = p.services.composed.set(Arc::downgrade(c));
    let mut rx = c.bus.subscribe_events();
    let host = p.host.clone();
    tokio::spawn(async move {
        loop {
            match rx.recv().await {
                Ok(e) => {
                    let mut payload = e.payload.clone();
                    let (mut id, mut source, mut cause) = (String::new(), "daemon".to_string(), String::new());
                    if let Some(o) = payload.as_object_mut() {
                        id = o.remove("_eventId").and_then(|v| v.as_str().map(String::from)).unwrap_or_default();
                        source = o.remove("_source").and_then(|v| v.as_str().map(String::from)).unwrap_or(source);
                        cause = o.remove("_causeId").and_then(|v| v.as_str().map(String::from)).unwrap_or_default();
                    }
                    let ev = NamedEvent { name: e.name, payload: payload.to_string(), source, id, cause_id: cause, ..Default::default() };
                    let h = host.clone();
                    let _ = tokio::task::spawn_blocking(move || h.deliver_event(&ev)).await;
                }
                Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => continue,
                Err(_) => break,
            }
        }
    });
}
