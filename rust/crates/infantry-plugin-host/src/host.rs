// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! [`PluginHost`]: loads module folders, holds the loaded plugins and their tool table, guards every call, hot swaps.
use crate::guard::{CallError, Fault, Guard, GuardLimits};
use crate::imports::PluginImports;
use crate::manifest::{inside, tool_name_ok, DaemonDecl, ModuleManifest, Tier, ToolDecl};
use crate::services::HostServices;
use crate::sidecar::SidecarPlugin;
use crate::wasm::{WasmEngine, WasmPlugin};
use crate::{Budget, CallContext, Caller, Frame, HostLimits, HttpResponse, NamedEvent, Plugin, RouteRequest};
use infantry_plugin_wit::jsonl::LogLevel;
use std::collections::{BTreeMap, BTreeSet};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, RwLock};

/// The hook kinds a daemon half may declare (DaemonPluginHost HOOK_KINDS).
pub const HOOK_KINDS: &[&str] = &[
    "frameGate", "connected", "httpGate", "admission", "mailGate", "incomingMail", "spawnGate", "ready", "turns", "sessions", "policyProvider",
    "orchestratorBinding", "runnerBinding", "usageReading", "runnerLinkTransport", "seats", "approvalRules", "status",
];

/// The hook kind the half-open breaker probes with; any answer (even "unknown hook") proves the plugin responds.
pub const PROBE_HOOK: &str = "probe";

/// Frames core answers itself; no plugin may declare them.
pub const CORE_FRAMES: &[&str] = &["plugin_tools", "plugin_tool_call", "plugin_tool_result", "replay_request", "ready", "error"];

/// One tool of the tool table.
#[derive(Debug, Clone)]
pub struct ToolEntry {
    pub module_id: String,
    pub decl: ToolDecl,
}

/// A loaded daemon half: its declarations, its runtime and its guard.
pub struct LoadedPlugin {
    pub id: String,
    pub version: String,
    pub tier: Tier,
    pub dir: PathBuf,
    pub decl: DaemonDecl,
    /// The whole manifest (capabilities, for [`PluginHost::call_capability`]).
    pub manifest: ModuleManifest,
    runtime: Box<dyn Plugin>,
    pub guard: Guard,
    imports: Arc<PluginImports>,
    limits: HostLimits,
}

impl LoadedPlugin {
    pub fn runtime_name(&self) -> &'static str {
        self.runtime.runtime()
    }

    fn cpu_ms(&self) -> u64 {
        self.decl.limits.cpu_ms_per_call.unwrap_or(self.limits.cpu_ms_per_call)
    }
    /// A tool's wall clock: its own declared `timeoutMs` (capped), else the plugin's, else the host default.
    pub fn tool_timeout_ms(&self, name: &str) -> u64 {
        let declared = self.decl.tools.iter().find(|t| t.name == name).and_then(|t| t.timeout_ms);
        self.declared_wall(declared, self.decl.limits.tool_timeout_ms.unwrap_or(self.limits.tool_timeout_ms))
    }
    /// A frame's wall clock: its own declared `timeoutMs` (capped), else the plugin's, else the host default.
    pub fn frame_timeout_ms(&self, frame_type: &str) -> u64 {
        let declared = self.decl.frames.iter().find(|f| f.r#type == frame_type).and_then(|f| f.timeout_ms);
        self.declared_wall(declared, self.decl.limits.call_timeout_ms.unwrap_or(self.limits.call_timeout_ms))
    }
    fn declared_wall(&self, declared: Option<u64>, fallback: u64) -> u64 {
        declared.map_or(fallback, |d| d.min(self.limits.max_declared_timeout_ms.max(fallback)))
    }
    fn tool_budget(&self, name: &str) -> Budget {
        Budget { cpu_ms: self.cpu_ms(), wall_ms: self.tool_timeout_ms(name).max(self.cpu_ms()) }
    }
    fn frame_budget(&self, frame_type: &str) -> Budget {
        Budget { cpu_ms: self.cpu_ms(), wall_ms: self.frame_timeout_ms(frame_type).max(self.cpu_ms()) }
    }
    fn call_budget(&self) -> Budget {
        let wall = self.decl.limits.call_timeout_ms.unwrap_or(self.limits.call_timeout_ms);
        Budget { cpu_ms: self.cpu_ms(), wall_ms: wall.max(self.cpu_ms()) }
    }
    fn start_budget(&self) -> Budget {
        Budget { cpu_ms: self.cpu_ms().max(self.limits.cpu_ms_per_call), wall_ms: self.limits.start_timeout_ms }
    }

    fn probe(&self) -> Result<(), Fault> {
        self.runtime.on_hook(PROBE_HOOK, "{}", self.call_budget()).map(|_| ())
    }

    /// A declared tool; an undeclared name is refused without calling the plugin.
    pub fn call_tool(&self, name: &str, args: &str, ctx: &CallContext) -> Result<String, CallError> {
        if !self.decl.tools.iter().any(|t| t.name == name) {
            return Err(CallError::Refused(format!("{name} refused: plugin {} does not declare it in daemon.tools", self.id)));
        }
        let what = format!("call-tool({name})");
        let _acting = self.imports.acting_for(ctx.clone());
        self.guard.run(&what, &|| self.probe(), || self.runtime.call_tool(name, args, ctx, self.tool_budget(name)))
    }

    /// A declared frame; the reply frame or the exact `<type> refused: ...` text.
    pub fn handle_frame(&self, frame: &Frame, caller: &Caller) -> Result<Option<Frame>, CallError> {
        let Some(d) = self.decl.frames.iter().find(|f| f.r#type == frame.r#type) else {
            return Err(CallError::Refused(format!("{} refused: plugin {} does not declare it in daemon.frames", frame.r#type, self.id)));
        };
        if d.owner_only && !caller.owner {
            return Err(CallError::Refused(format!("{} refused: owner only", frame.r#type)));
        }
        let what = format!("handle-frame({})", frame.r#type);
        let _acting = self.imports.acting_for(CallContext { caller: caller.clone(), client_session_id: caller.active_session.clone(), client_role: None, client_project_id: caller.project.clone() });
        self.guard.run(&what, &|| self.probe(), || self.runtime.handle_frame(frame, caller, self.frame_budget(&frame.r#type)))
    }

    pub fn handle_route(&self, request: &RouteRequest) -> Result<HttpResponse, CallError> {
        let declared = self.decl.routes.iter().chain(self.decl.public_routes.iter()).any(|r| route_matches(r, &request.method, &request.path));
        if !declared {
            return Err(CallError::Refused(format!("{} {} refused: plugin {} does not declare the route", request.method, request.path, self.id)));
        }
        let what = format!("handle-route({} {})", request.method, request.path);
        self.guard.run(&what, &|| self.probe(), || self.runtime.handle_route(request, self.call_budget()))
    }

    pub fn on_hook(&self, kind: &str, payload: &str) -> Result<String, CallError> {
        if !self.decl.hooks.iter().any(|h| h == kind) {
            return Err(CallError::Refused(format!("hook {kind} refused: plugin {} does not declare it in daemon.hooks", self.id)));
        }
        let what = format!("on-hook({kind})");
        self.guard.run(&what, &|| self.probe(), || self.runtime.on_hook(kind, payload, self.call_budget()))
    }

    /// Hands `event` to every matching subscription of this plugin.
    pub fn deliver_event(&self, event: &NamedEvent) {
        for sub in self.imports.matching_subscriptions(&event.name) {
            let what = format!("on-event({})", event.name);
            let _ = self.guard.run(&what, &|| self.probe(), || self.runtime.on_event(sub, event, self.call_budget()).map(Ok));
        }
    }

    pub fn memory_bytes(&self) -> Option<u64> {
        self.runtime.memory_bytes()
    }

    /// Unload: refuse new calls, drain the running ones, cut off a straggler, call `stop`.
    fn retire(&self) {
        let drained = self.guard.retire(self.limits.drain_ms);
        if !drained {
            self.runtime.cancel();
            return;
        }
        if let Err(f) = self.runtime.stop(self.call_budget()) {
            self.imports.log(LogLevel::Warn, &format!("stop failed while unloading: {f}"));
        }
        self.imports.log(LogLevel::Info, &format!("version {} stopped", self.version));
    }
}

/// `GET /path` (or a bare `/path` = any method) against a request. A `{name}` segment matches any one non-empty segment.
pub fn route_matches(decl: &str, method: &str, path: &str) -> bool {
    let (m, p) = match decl.split_once(' ') {
        Some((m, p)) => (Some(m), p),
        None => (None, decl),
    };
    if m.is_some_and(|m| !m.eq_ignore_ascii_case(method)) {
        return false;
    }
    let (want, got): (Vec<&str>, Vec<&str>) = (p.trim_end_matches('/').split('/').collect(), path.trim_end_matches('/').split('/').collect());
    want.len() == got.len() && want.iter().zip(&got).all(|(w, g)| if w.starts_with('{') && w.ends_with('}') { !g.is_empty() } else { w == g })
}

pub struct PluginHost {
    services: Arc<dyn HostServices>,
    limits: HostLimits,
    engine: Arc<WasmEngine>,
    plugins: RwLock<BTreeMap<String, Arc<LoadedPlugin>>>,
    statuses: Mutex<BTreeMap<String, String>>,
    /// modules.json `allow` per module id (grants a community module needs).
    allow: Mutex<BTreeMap<String, BTreeSet<String>>>,
    retiring: Mutex<Vec<std::thread::JoinHandle<()>>>,
}

impl PluginHost {
    pub fn new(services: Arc<dyn HostServices>, limits: HostLimits) -> anyhow::Result<PluginHost> {
        let engine = WasmEngine::new(limits.epoch_tick_ms)?;
        Ok(PluginHost { services, limits, engine, plugins: RwLock::default(), statuses: Mutex::default(), allow: Mutex::default(), retiring: Mutex::default() })
    }

    pub fn limits(&self) -> &HostLimits {
        &self.limits
    }

    /// The daemon-side back ends the plugins' imports end in (what a tool or a test hands to a [`PluginImports`]).
    pub fn services(&self) -> &Arc<dyn HostServices> {
        &self.services
    }

    /// Records the owner's modules.json `allow` list for `module_id`.
    pub fn set_allow(&self, module_id: &str, allow: impl IntoIterator<Item = String>) {
        self.allow.lock().unwrap().insert(module_id.into(), allow.into_iter().collect());
    }

    /// The tier of a module folder by where it is installed: `.../official/<M>` official, `.../core/<M>` core,
    /// anything else community. Never by the manifest.
    pub fn tier_of(dir: &Path) -> Tier {
        match dir.parent().and_then(|p| p.file_name()).and_then(|n| n.to_str()) {
            Some("official") => Tier::Official,
            Some("core") => Tier::Core,
            _ => Tier::Community,
        }
    }

    /// Loads the daemon half of the module in `dir` (tier from its folder). Loading a module that is already loaded
    /// is a hot swap: the new version starts, takes over, and the old one drains its calls and stops.
    pub fn load(&self, dir: &Path) -> Result<Arc<LoadedPlugin>, String> {
        self.load_as(dir, Self::tier_of(dir))
    }

    pub fn load_as(&self, dir: &Path, tier: Tier) -> Result<Arc<LoadedPlugin>, String> {
        let manifest_path = dir.join("module.json");
        let text = std::fs::read_to_string(&manifest_path).map_err(|e| format!("{}: {e}", manifest_path.display()))?;
        let manifest: ModuleManifest = serde_json::from_str(&text).map_err(|e| format!("{}: {e}", manifest_path.display()))?;
        let id = manifest.name.clone();
        let result = self.load_manifest(dir, tier, manifest);
        let status = match &result {
            Ok(_) => "loaded".to_string(),
            Err(e) if e.starts_with("failed:") => e.clone(),
            Err(e) => format!("refused: {e}"),
        };
        self.statuses.lock().unwrap().insert(id.clone(), status);
        if let Err(e) = &result {
            self.services.log(&id, LogLevel::Warn, &format!("[plugins] daemon half of {id} {}", if e.starts_with("failed:") { e.clone() } else { format!("refused: {e}") }));
        }
        result
    }

    fn load_manifest(&self, dir: &Path, tier: Tier, manifest: ModuleManifest) -> Result<Arc<LoadedPlugin>, String> {
        let id = manifest.name.clone();
        let decl = manifest.daemon.clone().ok_or("the module has no daemon half")?;
        let allow = self.allow.lock().unwrap().get(&id).cloned().unwrap_or_default();
        refusal(tier, &decl, &allow)?;
        let (granted, denied) = grants(tier, &decl, &allow)?;
        let imports = Arc::new(PluginImports::new(&id, tier, decl.clone(), self.services.clone(), granted, denied));
        let runtime: Box<dyn Plugin> = match (&decl.component, &decl.sidecar) {
            (Some(_), Some(_)) => return Err("daemon.component and daemon.sidecar are both set; pick one".into()),
            (Some(c), None) => {
                let path = inside(dir, c, "daemon.component")?;
                let bytes = std::fs::read(&path).map_err(|e| format!("component missing: daemon.component {c} ({e})"))?;
                let component = self.engine.compile(&bytes)?;
                Box::new(WasmPlugin::new(self.engine.clone(), &component, imports.clone(), self.limits.fuel_per_ms, self.limits.memory_mb)?)
            }
            (None, Some(s)) => {
                if tier == Tier::Community && !allow.contains("daemon.native") {
                    return Err("a community module may not run a native sidecar (daemon.native)".into());
                }
                let mut path = inside(dir, &s.exe, "daemon.sidecar.exe")?;
                if cfg!(windows) && path.extension().is_none() {
                    path.set_extension("exe");
                }
                Box::new(SidecarPlugin::spawn(&path, &s.args, dir, &decl.env, imports.clone(), self.limits.sidecar_hello_ms)?)
            }
            (None, None) => return Err("daemon.component (or daemon.sidecar) is not set".into()),
        };
        let services = self.services.clone();
        let log_id = id.clone();
        let guard = Guard::new(
            &id,
            GuardLimits { strikes: self.limits.strikes, cooldown_ms: self.limits.cooldown_ms, queue_limit: self.limits.queue_limit },
            move |line| services.log(&log_id, LogLevel::Warn, line),
            |_| {},
        );
        let plugin = Arc::new(LoadedPlugin { id: id.clone(), version: manifest.version.clone(), tier, dir: dir.to_path_buf(), decl, manifest: manifest.clone(), runtime, guard, imports, limits: self.limits.clone() });
        let started = plugin.guard.run("start", &|| Ok(()), || plugin.runtime.start(plugin.start_budget()));
        if let Err(e) = started {
            plugin.runtime.cancel();
            return Err(format!("failed: {e}"));
        }
        let old = self.plugins.write().unwrap().insert(id.clone(), plugin.clone());
        if let Some(old) = old {
            self.services.log(&id, LogLevel::Info, &format!("[plugins] daemon half of {id} hot swap {} -> {}: the old version drains", old.version, plugin.version));
            let h = std::thread::Builder::new().name(format!("plugin-retire-{id}")).spawn(move || old.retire()).map_err(|e| e.to_string())?;
            self.retiring.lock().unwrap().push(h);
        }
        self.services.log(&id, LogLevel::Info, &format!("[plugins] daemon half of {id} ({}, {}) loaded: {} tool(s)", tier.as_str(), plugin.runtime_name(), plugin.decl.tools.len()));
        Ok(plugin)
    }

    /// Unloads `id` (drain, then stop). False when it was not loaded.
    pub fn unload(&self, id: &str) -> bool {
        let old = self.plugins.write().unwrap().remove(id);
        match old {
            Some(p) => {
                p.retire();
                self.statuses.lock().unwrap().insert(id.into(), "unloaded".into());
                true
            }
            None => false,
        }
    }

    /// Waits for every swapped-out version to finish retiring.
    pub fn settle(&self) {
        let hs: Vec<_> = self.retiring.lock().unwrap().drain(..).collect();
        for h in hs {
            let _ = h.join();
        }
    }

    pub fn get(&self, id: &str) -> Option<Arc<LoadedPlugin>> {
        self.plugins.read().unwrap().get(id).cloned()
    }

    pub fn loaded(&self) -> Vec<Arc<LoadedPlugin>> {
        self.plugins.read().unwrap().values().cloned().collect()
    }

    /// `module id -> loaded | refused: why | failed: why | unloaded`.
    pub fn statuses(&self) -> BTreeMap<String, String> {
        self.statuses.lock().unwrap().clone()
    }

    /// The loaded plugin that declares route `method path`, and whether that route is public (no daemon bearer).
    pub fn route_owner(&self, method: &str, path: &str) -> Option<(Arc<LoadedPlugin>, bool)> {
        self.loaded().into_iter().find_map(|p| {
            let public = p.decl.public_routes.iter().any(|r| route_matches(r, method, path));
            let private = p.decl.routes.iter().any(|r| route_matches(r, method, path));
            (public || private).then(|| (p.clone(), public))
        })
    }

    /// Every tool of every healthy plugin.
    pub fn tools(&self) -> Vec<ToolEntry> {
        self.loaded().iter().filter(|p| !p.guard.is_disabled()).flat_map(|p| p.decl.tools.iter().map(|t| ToolEntry { module_id: p.id.clone(), decl: t.clone() })).collect()
    }

    /// The plugin that declares tool `name`.
    pub fn tool_owner(&self, name: &str) -> Option<Arc<LoadedPlugin>> {
        self.loaded().into_iter().find(|p| p.decl.tools.iter().any(|t| t.name == name))
    }

    /// Calls tool `name` on whichever plugin declares it.
    pub fn call_tool(&self, name: &str, args: &str, ctx: &CallContext) -> Result<String, CallError> {
        match self.tool_owner(name) {
            Some(p) => p.call_tool(name, args, ctx),
            None => Err(CallError::Refused(format!("{name} refused: no loaded plugin declares this tool"))),
        }
    }

    /// Calls capability `id` (`runs: "daemon"`) on the plugin that declares it, through the daemon tool it maps to
    /// ([`ModuleManifest::capability_tool`]: same `action`, else the id with `.` -> `_`).
    pub fn call_capability(&self, id: &str, args: &str, ctx: &CallContext) -> Result<String, CallError> {
        for p in self.loaded() {
            if let Some(t) = p.manifest.capability_tool(id) {
                let name = t.name.clone();
                return p.call_tool(&name, args, ctx);
            }
        }
        Err(CallError::Refused(format!("{id} refused: no loaded plugin answers this capability")))
    }

    /// Hands a bus event to every plugin subscribed to it.
    pub fn deliver_event(&self, event: &NamedEvent) {
        for p in self.loaded() {
            p.deliver_event(event);
        }
    }
}

impl Drop for PluginHost {
    fn drop(&mut self) {
        self.settle();
    }
}

/// Why `decl` may not load for `tier` with the owner's `allow` list (DaemonPluginHost.refusal).
fn refusal(tier: Tier, d: &DaemonDecl, allow: &BTreeSet<String>) -> Result<(), String> {
    if let Some(need) = &d.api_at_least {
        let v = semver(need).ok_or_else(|| format!("daemon.apiAtLeast '{need}' is not a version"))?;
        // The Kotlin DaemonApi line (1.x) is what the old manifests name; the WIT ABI is a new line (0.x). A manifest
        // asking for more than the WIT ABI version on the 0.x line is refused; a 1.x requirement means "the
        // Kotlin API surface", which this host replaces, and is accepted.
        let have = semver(infantry_plugin_wit::API_VERSION).expect("API_VERSION is semver");
        if v.0 == have.0 && v > have {
            return Err(format!("needs daemon plugin ABI {need}, this host has {}", infantry_plugin_wit::API_VERSION));
        }
    }
    if let Some(h) = d.hooks.iter().find(|h| !HOOK_KINDS.contains(&h.as_str())) {
        return Err(format!("unknown hook kind '{h}' (known: {})", HOOK_KINDS.join(", ")));
    }
    if let Some(t) = d.tools.iter().find(|t| !tool_name_ok(&t.name)) {
        return Err(format!("tool name '{}' must match ^[a-z][a-z0-9_]{{2,63}}$", t.name));
    }
    if let Some(f) = d.frames.iter().find(|f| CORE_FRAMES.contains(&f.r#type.as_str())) {
        return Err(format!("frame '{}' is core's", f.r#type));
    }
    for (name, envs) in &d.endpoints {
        for (env, url) in envs {
            let lower = url.to_lowercase();
            if !(lower.starts_with("http://") || lower.starts_with("https://")) {
                return Err(format!("daemon.endpoints.{name}.{env}: '{url}' is not an http(s) address"));
            }
            if env == "prod" && !lower.starts_with("https://") {
                return Err(format!("daemon.endpoints.{name}.prod: '{url}' must be https"));
            }
        }
    }
    if tier != Tier::Community {
        return Ok(());
    }
    if !d.storage.adopt.is_empty() {
        return Err("a community module may not adopt core tables (daemon.storage.adopt)".into());
    }
    if !allow.contains("daemon.code") {
        return Err("community daemon code needs the 'daemon.code' grant in modules.json".into());
    }
    if !d.routes.is_empty() || !d.public_routes.is_empty() {
        return Err("a community module may not declare top-level or public routes".into());
    }
    if d.hooks.iter().any(|h| h == "admission") {
        return Err("a community module may not admit tokens".into());
    }
    if let Some(f) = d.owner_frames.first() {
        return Err(format!("a community module may not send a frame as the owner (daemon.ownerFrames: {f})"));
    }
    if let Some(f) = d.frames.iter().find(|f| f.owner_only) {
        return Err(format!("a community module may not declare an owner-only frame ({})", f.r#type));
    }
    let mut needed = Vec::new();
    if !d.tools.is_empty() {
        needed.push("daemon.tool".to_string());
    }
    if !d.frames.is_empty() {
        needed.push("daemon.frame".to_string());
    }
    needed.extend(d.hooks.iter().map(|h| format!("daemon.hook.{h}")));
    if let Some(g) = needed.iter().find(|g| !allow.contains(*g)) {
        return Err(format!("missing grant '{g}' in modules.json"));
    }
    Ok(())
}

/// (granted, linked-but-denied) interfaces. A community module needs `daemon.import.<x>` per import; a refused
/// `needs` entry refuses the module, a refused `wants` entry is linked denied.
fn grants(tier: Tier, d: &DaemonDecl, allow: &BTreeSet<String>) -> Result<(BTreeSet<String>, BTreeSet<String>), String> {
    let (mut granted, mut denied) = (BTreeSet::new(), BTreeSet::new());
    for (list, needed) in [(&d.needs, true), (&d.wants, false)] {
        for name in list.iter().filter(|n| infantry_plugin_wit::GRANTABLE_IMPORTS.contains(&n.as_str())) {
            let ok = tier != Tier::Community || allow.contains(&format!("daemon.import.{name}"));
            if ok {
                granted.insert(name.clone());
            } else if needed {
                return Err(format!("missing grant 'daemon.import.{name}' in modules.json (daemon.needs)"));
            } else {
                denied.insert(name.clone());
            }
        }
    }
    denied.retain(|n| !granted.contains(n));
    Ok((granted, denied))
}

fn semver(s: &str) -> Option<(u64, u64, u64)> {
    let mut it = s.trim().split('.').map(|p| p.parse::<u64>().ok());
    Some((it.next()??, it.next().unwrap_or(Some(0))?, it.next().unwrap_or(Some(0))?))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn community_rules_and_grants() {
        let d: DaemonDecl = serde_json::from_str(r#"{"needs":["frames"],"wants":["storage"],"tools":[{"name":"acme_t","action":"a"}]}"#).unwrap();
        let none = BTreeSet::new();
        assert!(refusal(Tier::Official, &d, &none).is_ok());
        assert!(refusal(Tier::Community, &d, &none).unwrap_err().contains("daemon.code"));
        let allow: BTreeSet<String> = ["daemon.code", "daemon.tool"].iter().map(|s| s.to_string()).collect();
        assert!(refusal(Tier::Community, &d, &allow).is_ok());
        assert!(grants(Tier::Community, &d, &allow).unwrap_err().contains("daemon.import.frames"));
        // SEC-PLUGIN-1: the owner's authority for a frame is never a community module's, whatever it is granted.
        let owner: DaemonDecl = serde_json::from_str(r#"{"needs":["frames"],"ownerFrames":["plugin_admin"]}"#).unwrap();
        assert!(refusal(Tier::Official, &owner, &none).is_ok() && refusal(Tier::Core, &owner, &none).is_ok());
        let all: BTreeSet<String> = ["daemon.code", "daemon.tool", "daemon.frame"].iter().map(|s| s.to_string()).collect();
        assert!(refusal(Tier::Community, &owner, &all).unwrap_err().contains("daemon.ownerFrames: plugin_admin"));
        let mut allow2 = allow.clone();
        allow2.insert("daemon.import.frames".into());
        let (g, x) = grants(Tier::Community, &d, &allow2).unwrap();
        assert_eq!((g.into_iter().collect::<Vec<_>>(), x.into_iter().collect::<Vec<_>>()), (vec!["frames".to_string()], vec!["storage".to_string()]));
        let bad: DaemonDecl = serde_json::from_str(r#"{"endpoints":{"api":{"prod":"http://x"}}}"#).unwrap();
        assert!(refusal(Tier::Official, &bad, &none).unwrap_err().contains("https"));
        let newer: DaemonDecl = serde_json::from_str(r#"{"apiAtLeast":"0.9.0"}"#).unwrap();
        assert!(refusal(Tier::Official, &newer, &none).is_err());
        let kotlin: DaemonDecl = serde_json::from_str(r#"{"apiAtLeast":"1.48.0"}"#).unwrap();
        assert!(refusal(Tier::Official, &kotlin, &none).is_ok());
    }
}

#[cfg(test)]
mod budget_tests {
    use super::*;
    use crate::services::MemoryServices;
    use std::time::{Duration, Instant};

    /// A runtime that behaves like a sidecar: a call sleeps `how_long(name)`, and one that outlasts its wall budget is a hang.
    struct Slow;
    impl Slow {
        fn run(what: &str, sleep_ms: u64, b: Budget) -> Result<(), Fault> {
            if sleep_ms > b.wall_ms {
                std::thread::sleep(Duration::from_millis(b.wall_ms));
                return Err(Fault::Hang(format!("{what} made no answer in {} ms", b.wall_ms)));
            }
            std::thread::sleep(Duration::from_millis(sleep_ms));
            Ok(())
        }
    }
    impl Plugin for Slow {
        fn runtime(&self) -> &'static str {
            "fake"
        }
        fn start(&self, _: Budget) -> Result<Result<(), String>, Fault> {
            Ok(Ok(()))
        }
        fn stop(&self, _: Budget) -> Result<(), Fault> {
            Ok(())
        }
        fn call_tool(&self, name: &str, args: &str, _: &CallContext, b: Budget) -> Result<Result<String, String>, Fault> {
            Self::run(name, args.parse().unwrap_or(0), b).map(|_| Ok(format!("{name} done")))
        }
        fn handle_frame(&self, f: &Frame, _: &Caller, b: Budget) -> Result<Result<Option<Frame>, String>, Fault> {
            Self::run(&f.r#type, f.data.parse().unwrap_or(0), b).map(|_| Ok(Some(Frame { r#type: format!("{}_reply", f.r#type), ..Default::default() })))
        }
        fn handle_route(&self, _: &RouteRequest, _: Budget) -> Result<Result<HttpResponse, String>, Fault> {
            unreachable!()
        }
        fn on_hook(&self, _: &str, _: &str, _: Budget) -> Result<Result<String, String>, Fault> {
            Ok(Ok(String::new()))
        }
        fn on_event(&self, _: u64, _: &NamedEvent, _: Budget) -> Result<(), Fault> {
            Ok(())
        }
        fn cancel(&self) {}
        fn memory_bytes(&self) -> Option<u64> {
            None
        }
    }

    /// Host default 300 ms stands in for the 10 s default; `fast` is undeclared, `slow` declares 2000 ms, `huge` declares far past the cap.
    fn plugin() -> Arc<LoadedPlugin> {
        let decl: DaemonDecl = serde_json::from_str(
            r#"{"tools":[{"name":"t_fast"},{"name":"t_slow","timeoutMs":2000}],
                "frames":[{"type":"fast"},{"type":"slow","timeoutMs":2000},{"type":"huge","timeoutMs":999999999}]}"#,
        )
        .unwrap();
        let limits = HostLimits { call_timeout_ms: 300, tool_timeout_ms: 300, max_declared_timeout_ms: 5_000, cpu_ms_per_call: 50, strikes: 2, ..HostLimits::default() };
        let imports = Arc::new(PluginImports::new("Acme_Slow", Tier::Official, decl.clone(), Arc::new(MemoryServices::new()), BTreeSet::new(), BTreeSet::new()));
        let guard = Guard::new("Acme_Slow", GuardLimits { strikes: 2, cooldown_ms: 30_000, queue_limit: 8 }, |_| {}, |_| {});
        let manifest: ModuleManifest = serde_json::from_str(r#"{"name":"Acme_Slow","version":"1.0.0"}"#).unwrap();
        Arc::new(LoadedPlugin { id: "Acme_Slow".into(), version: "1.0.0".into(), tier: Tier::Official, dir: PathBuf::new(), decl, manifest, runtime: Box::new(Slow), guard, imports, limits })
    }

    fn frame(t: &str, ms: u64) -> Frame {
        Frame { r#type: t.into(), data: ms.to_string(), ..Default::default() }
    }
    fn owner() -> Caller {
        Caller { owner: true, ..Default::default() }
    }

    #[test]
    fn default_budget_is_unchanged_and_a_declared_one_wins_up_to_the_cap() {
        let p = plugin();
        assert_eq!((p.frame_timeout_ms("fast"), p.tool_timeout_ms("t_fast")), (300, 300));
        assert_eq!((p.frame_timeout_ms("slow"), p.tool_timeout_ms("t_slow")), (2000, 2000));
        assert_eq!(p.frame_timeout_ms("huge"), 5_000, "a declared budget is cut to limits.plugins.maxDeclaredTimeoutMs");
    }

    #[test]
    fn a_declared_frame_runs_past_the_default_and_is_no_strike() {
        let p = plugin();
        assert!(p.handle_frame(&frame("fast", 600), &owner()).is_err(), "undeclared: cut at the default");
        assert!(p.handle_frame(&frame("slow", 600), &owner()).is_ok(), "declared: runs past the default");
        assert!(p.handle_frame(&frame("slow", 600), &owner()).is_ok());
        assert!(p.handle_frame(&frame("slow", 600), &owner()).is_ok());
        assert!(!p.guard.is_cooling(), "slow calls inside their budget are never a strike");
    }

    #[test]
    fn a_declared_tool_runs_past_the_default() {
        let p = plugin();
        let ctx = CallContext::default();
        assert!(p.call_tool("t_fast", "600", &ctx).is_err());
        assert_eq!(p.call_tool("t_slow", "600", &ctx).unwrap(), "t_slow done");
    }

    #[test]
    fn over_budget_ends_readably_and_does_strike() {
        let p = plugin();
        let t = Instant::now();
        let e = p.handle_frame(&frame("slow", 4000), &owner()).unwrap_err();
        assert!(t.elapsed() < Duration::from_millis(3500), "ended at its own budget, not later");
        assert!(e.message().contains("handle-frame(slow) hung") && e.message().contains("2000 ms"), "{}", e.message());
    }

    #[test]
    fn another_frame_is_answered_during_a_slow_call() {
        let p = plugin();
        let p2 = p.clone();
        let slow = std::thread::spawn(move || p2.handle_frame(&frame("slow", 1200), &owner()));
        std::thread::sleep(Duration::from_millis(100));
        let t = Instant::now();
        assert!(p.handle_frame(&frame("fast", 0), &owner()).is_ok());
        assert!(t.elapsed() < Duration::from_millis(400), "sites_list-style frame was not held behind the slow call");
        assert!(slow.join().unwrap().is_ok());
        assert!(!p.guard.is_cooling());
    }
}
