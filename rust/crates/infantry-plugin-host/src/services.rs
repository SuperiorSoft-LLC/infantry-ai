// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! What the daemon gives the plugin host: the back ends of the WIT imports. The host does the per-plugin part
//! (grants, the `<Module>.` settings prefix, the endpoint filter, the storage table rule); a [`HostServices`]
//! only carries it out. [`MemoryServices`] is a complete in-process implementation for tests and tools.
use infantry_plugin_wit::jsonl::{CallContext, Frame, HttpRequest, HttpResponse, LogLevel, NamedEvent};
use std::collections::BTreeMap;
use std::sync::{Arc, Mutex};
use std::time::{SystemTime, UNIX_EPOCH};

pub trait HostServices: Send + Sync + 'static {
    /// One plugin log line (`module` is the module id).
    fn log(&self, module: &str, level: LogLevel, line: &str);
    /// The running build environment: prod | staging | dev.
    fn environment(&self) -> String {
        "prod".into()
    }
    /// A setting by its FULL key (`<Module>.x`).
    fn setting_get(&self, key: &str) -> Option<String>;
    fn setting_set(&self, key: &str, value: &str) -> Result<(), String>;
    /// The owner's app-scope value of a BARE key the plugin declared in its manifest config (what Settings writes with
    /// `config_set` / `settings_set`), as text; None = no value stored.
    fn app_setting(&self, _key: &str) -> Option<String> {
        None
    }
    fn setting_remove(&self, key: &str) -> Result<(), String>;
    /// A frame into the daemon (DaemonApi ToolCall.request); the reply frame. `caller` is who the plugin is working
    /// for: the call context of the tool call, frame or capability running when it asked (None = the plugin's own
    /// work, a hook, an event or a route). The daemon scopes the frame to that caller (spawn depth, kill scope,
    /// project scope).
    ///
    /// The frame is the PLUGIN's, never the owner's: the daemon dispatches it as a non-owner, so a frame only the
    /// owner may send is refused, and `module` is the calling plugin (the daemon pins what a frame names to it, e.g.
    /// whose secret `plugin_secret_get` reads). `as_owner` is true only when the plugin's manifest lists this frame
    /// type in `daemon.ownerFrames` (an official or core module); the daemon then dispatches that one frame as the owner.
    fn frame_request(&self, module: &str, frame: Frame, caller: Option<&CallContext>, as_owner: bool, timeout_ms: u32) -> Result<Frame, String>;
    /// Publishes a named event as `module` (source); the stored event.
    fn publish(&self, module: &str, name: &str, payload: &str, cause_id: Option<&str>) -> Result<NamedEvent, String>;
    /// Pushes a frame to every connected client.
    fn broadcast(&self, module: &str, r#type: &str, data: &str);
    /// Sends an outbound request the host already checked against the module's endpoints.
    fn http_send(&self, module: &str, request: HttpRequest) -> Result<HttpResponse, String>;
    /// The same, but a 3xx comes back as the answer instead of being followed (the host checks each hop itself).
    fn http_send_no_redirect(&self, module: &str, request: HttpRequest) -> Result<HttpResponse, String> {
        self.http_send(module, request)
    }
    /// A no-redirect send that connects only to `addrs` (the addresses the host checked), keeping the URL's host
    /// for TLS SNI and the Host header (HTTP-REBIND-1). A real network implementation must override this.
    fn http_send_pinned(&self, module: &str, request: HttpRequest, addrs: &[std::net::SocketAddr]) -> Result<HttpResponse, String> {
        let _ = addrs;
        self.http_send_no_redirect(module, request)
    }
    /// The daemon's SQLite database (plugin tables and the plugin key/value table live here).
    fn db(&self) -> Arc<Mutex<rusqlite::Connection>>;
}

/// An in-memory [`HostServices`]: settings in a map, an in-memory SQLite, frames answered by a handler.
pub struct MemoryServices {
    pub settings: Mutex<BTreeMap<String, String>>,
    /// The owner's app-scope values by bare key (what Settings wrote), see [`HostServices::app_setting`].
    pub app_settings: Mutex<BTreeMap<String, String>>,
    pub logs: Mutex<Vec<String>>,
    pub broadcasts: Mutex<Vec<(String, String)>>,
    pub events: Mutex<Vec<NamedEvent>>,
    #[allow(clippy::type_complexity)]
    frame_handler: Box<dyn Fn(&str, Frame) -> Result<Frame, String> + Send + Sync>,
    #[allow(clippy::type_complexity)]
    http_handler: Box<dyn Fn(HttpRequest) -> Result<HttpResponse, String> + Send + Sync>,
    db: Arc<Mutex<rusqlite::Connection>>,
    environment: String,
}

impl Default for MemoryServices {
    fn default() -> Self {
        MemoryServices::new()
    }
}

impl MemoryServices {
    pub fn new() -> Self {
        MemoryServices {
            settings: Mutex::default(),
            app_settings: Mutex::default(),
            logs: Mutex::default(),
            broadcasts: Mutex::default(),
            events: Mutex::default(),
            frame_handler: Box::new(|_, f| Err(format!("this daemon has no frame `{}`", f.r#type))),
            http_handler: Box::new(|r| Err(format!("no network in this host ({})", r.url))),
            db: Arc::new(Mutex::new(rusqlite::Connection::open_in_memory().expect("in-memory sqlite"))),
            environment: "prod".into(),
        }
    }

    /// Answers `frames.request` with `handler(module, frame)`.
    pub fn with_frames(mut self, handler: impl Fn(&str, Frame) -> Result<Frame, String> + Send + Sync + 'static) -> Self {
        self.frame_handler = Box::new(handler);
        self
    }

    pub fn with_http(mut self, handler: impl Fn(HttpRequest) -> Result<HttpResponse, String> + Send + Sync + 'static) -> Self {
        self.http_handler = Box::new(handler);
        self
    }

    pub fn with_environment(mut self, env: &str) -> Self {
        self.environment = env.into();
        self
    }

    pub fn log_lines(&self) -> Vec<String> {
        self.logs.lock().unwrap().clone()
    }
}

impl HostServices for MemoryServices {
    fn log(&self, module: &str, level: LogLevel, line: &str) {
        self.logs.lock().unwrap().push(format!("[{module}] {level:?}: {line}"));
    }
    fn environment(&self) -> String {
        self.environment.clone()
    }
    fn setting_get(&self, key: &str) -> Option<String> {
        self.settings.lock().unwrap().get(key).cloned()
    }
    fn app_setting(&self, key: &str) -> Option<String> {
        self.app_settings.lock().unwrap().get(key).cloned()
    }
    fn setting_set(&self, key: &str, value: &str) -> Result<(), String> {
        self.settings.lock().unwrap().insert(key.into(), value.into());
        Ok(())
    }
    fn setting_remove(&self, key: &str) -> Result<(), String> {
        self.settings.lock().unwrap().remove(key);
        Ok(())
    }
    fn frame_request(&self, module: &str, frame: Frame, _caller: Option<&CallContext>, _as_owner: bool, _timeout_ms: u32) -> Result<Frame, String> {
        (self.frame_handler)(module, frame)
    }
    fn publish(&self, module: &str, name: &str, payload: &str, cause_id: Option<&str>) -> Result<NamedEvent, String> {
        let mut events = self.events.lock().unwrap();
        let at = SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0);
        let e = NamedEvent {
            name: name.into(),
            payload: if payload.is_empty() { "{}".into() } else { payload.into() },
            source: module.into(),
            id: format!("ev-{}", events.len() + 1),
            cause_id: cause_id.unwrap_or_default().into(),
            depth: 0,
            at,
        };
        events.push(e.clone());
        Ok(e)
    }
    fn broadcast(&self, _module: &str, r#type: &str, data: &str) {
        self.broadcasts.lock().unwrap().push((r#type.into(), data.into()));
    }
    fn http_send(&self, _module: &str, request: HttpRequest) -> Result<HttpResponse, String> {
        (self.http_handler)(request)
    }
    fn db(&self) -> Arc<Mutex<rusqlite::Connection>> {
        self.db.clone()
    }
}
