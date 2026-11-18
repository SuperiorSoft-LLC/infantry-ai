// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The plugin host: loads every installed daemon half (`<plugins>/official/<Module>`, `<plugins>/core/<Module>`),
//! answers their imports from the daemon ([`DaemonServices`]), registers their frames and hands them bus events.

use super::Composed;
use infantry_core::frames::{Envelope, FrameUnit};
use infantry_core::bus::EventBus;
use infantry_plugin_host::unit::PluginFrameUnit;
use infantry_plugin_host::{Frame, HostServices, NamedEvent, PluginHost};
use infantry_plugin_wit::jsonl::{HttpRequest, HttpResponse, LogLevel};
use std::collections::BTreeMap;
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

pub struct Plugins {
    pub host: Arc<PluginHost>,
    services: Arc<DaemonServices>,
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
    fn frame_request(&self, _module: &str, frame: Frame, _timeout_ms: u32) -> Result<Frame, String> {
        let c = self.composed.get().and_then(Weak::upgrade).ok_or("the daemon is still starting")?;
        let _rt = self.handle.enter();
        let mut env = Envelope::new(frame.r#type.clone(), frame.data);
        env.session_id = frame.session_id;
        env.request_id = frame.request_id.clone();
        let replies: Vec<Envelope> = if crate::sessions::owns(&env.r#type) {
            let mut conn = crate::sessions::Conn::default();
            c.daemon.handle(env, &mut conn).iter().filter_map(|t| serde_json::from_str(t).ok()).collect()
        } else {
            c.request(&env, true)?
        };
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

fn has_daemon_half(dir: &Path) -> bool {
    std::fs::read_to_string(dir.join("module.json"))
        .ok()
        .and_then(|t| serde_json::from_str::<serde_json::Value>(&t).ok())
        .is_some_and(|v| v.get("daemon").is_some_and(|d| d.is_object()))
}

/// Starts the host and loads every installed daemon half; None when there is no plugins folder or the host failed.
pub fn start(state_dir: &Path, dir: Option<PathBuf>, bus: EventBus, handle: tokio::runtime::Handle) -> Option<Plugins> {
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
    for tier in TIERS {
        let Ok(rd) = std::fs::read_dir(dir.join(tier)) else { continue };
        let mut mods: Vec<PathBuf> = rd.filter_map(|e| e.ok()).map(|e| e.path()).filter(|p| has_daemon_half(p)).collect();
        mods.sort();
        for m in mods {
            if let Err(e) = host.load(&m) {
                eprintln!("[infantry] plugin {} not loaded: {e}", m.display());
            }
        }
    }
    Some(Plugins { host, services })
}

/// One frame unit per loaded plugin that declares frames.
pub fn frame_units(p: &Plugins) -> Vec<Arc<dyn FrameUnit>> {
    p.host
        .loaded()
        .iter()
        .filter(|l| !l.decl.frames.is_empty())
        .filter_map(|l| PluginFrameUnit::new(p.host.clone(), &l.id))
        .map(|u| Arc::new(u) as Arc<dyn FrameUnit>)
        .collect()
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
