// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The plugin host: loads every installed daemon half (`<plugins>/official/<Module>`, `<plugins>/core/<Module>`),
//! answers their imports from the daemon ([`DaemonServices`]), registers their frames and hands them bus events.
//! `plugin_admin` enables and disables them, and the modules with no daemon half (a web UI half, declarations), for good.
//! INSTALLER-1: it also installs and uninstalls them from the sources (see `installer`), with no restart.

use super::installer::{self, InstallConfig, LateUnits};
use super::Composed;
use crate::boot::{BootState, Bootstrap};
use infantry_core::frames::{Envelope, FrameCtx, FrameUnit, Refusal};
use infantry_core::bus::EventBus;
use infantry_plugin_host::unit::PluginFrameUnit;
use infantry_plugin_host::{CallContext, Frame, HostServices, NamedEvent, PluginHost};
use infantry_plugin_wit::jsonl::{HttpRequest, HttpResponse, LogLevel};
use std::collections::{BTreeMap, BTreeSet};
use std::io::Read;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, OnceLock, RwLock, Weak};
use std::time::Duration;

/// The tiers loaded from the plugins folder. A community module loads only with the grants the owner lists for it in
/// [ALLOW_FILE] (the plugin host refuses it otherwise, and always refuses what a community module may never do).
pub const TIERS: &[&str] = &["official", "core", "community"];
/// The tiers whose manifests the daemon trusts for its own declarations (account providers): never community.
pub const TRUSTED_TIERS: &[&str] = &["official", "core"];
/// PLUGIN-GAPS-1: the owner's grants for community modules, in the state dir:
/// `{"<Module>": {"allow": ["daemon.code", "daemon.tool", "daemon.import.http", ...]}}`. Read at start.
pub const ALLOW_FILE: &str = "modules.json";
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

/// What the install ops need besides the plugin host: where plugins come from and which one bootstraps, the boot state a
/// change of bootstrap mode is reported on, and the late frame table a plugin installed after start answers through.
pub struct InstallSeam {
    pub config: InstallConfig,
    pub boot: Arc<BootState>,
    pub late: Arc<LateUnits>,
    /// the sources the user added, kept on the daemon.
    pub sources: Arc<super::sources::SourceStore>,
    pub signers: Arc<super::sources::Signers>,
}

/// What `plugin_admin` manages: the installed daemon halves, the modules with none, and the owner's disabled set.
pub struct Admin {
    host: Arc<PluginHost>,
    plugins_dir: PathBuf,
    installed: RwLock<BTreeMap<String, Installed>>,
    disabled: Arc<Disabled>,
    install: InstallSeam,
}

impl Admin {
    /// The built-in sources (bundled folder, `plugins.sources`) then the ones the user added.
    fn all_sources(&self) -> Vec<PathBuf> {
        let mut v = self.install.config.sources.clone();
        for d in self.install.sources.dirs() {
            if !v.contains(&d) {
                v.push(d);
            }
        }
        v
    }

    /// After a source is added, removed or refreshed: every client re-reads `available`.
    fn catalog_changed(&self, bus: &EventBus) {
        bus.publish(Envelope::new(infantry_protocol::frames::PLUGINS_CHANGED, serde_json::json!({ "sources": true }).to_string()));
    }

    fn has_daemon_half(&self, id: &str) -> bool {
        self.installed.read().unwrap_or_else(|e| e.into_inner()).contains_key(id)
    }

    /// The official and core modules with no daemon half (a web UI half, or declarations only).
    fn without_daemon(&self) -> Vec<String> {
        super::host_frames::scan_modules(&Some(self.plugins_dir.clone())).into_iter().filter(|m| TIERS.contains(&m.tier.as_str()) && !self.has_daemon_half(&m.id)).map(|m| m.id).collect()
    }

    /// The bootstrap plugin's state (None: this daemon has none, or it is not installed): bootstrap mode is on while it
    /// is the only module installed, and `error` is why its daemon half is not running, while that is the case.
    pub fn bootstrap_report(&self) -> Option<Bootstrap> {
        let id = self.install.config.bootstrap.clone()?;
        let installed = installer::installed_ids(&self.plugins_dir);
        if !installed.contains(&id) {
            return None;
        }
        let active = installer::bootstrap_active(&self.plugins_dir, &id, self.install.config.ui_plugin.as_deref());
        let error = if active && self.has_daemon_half(&id) { self.host.statuses().get(&id).filter(|s| s.as_str() != "loaded").cloned() } else { None };
        Some(Bootstrap { plugin: id, active, error })
    }

    /// Tells the boot state (and so every client) whether bootstrap mode is on, after an install or an uninstall.
    fn sync_bootstrap(&self) {
        self.install.boot.set_bootstrap(self.bootstrap_report());
    }

    /// Records a freshly placed module: its daemon half is loaded (unless the owner disabled that id) and its frames
    /// answer from now on. On a refusal nothing stays registered; the caller removes the folder.
    fn register(&self, id: &str, dir: &Path) -> Result<(), String> {
        let Some((_, frames)) = daemon_half(dir) else { return Ok(()) };
        self.installed.write().unwrap_or_else(|e| e.into_inner()).insert(id.to_string(), Installed { dir: dir.to_path_buf(), frames: frames.clone() });
        if !frames.is_empty() {
            self.install.late.add(id, &frames, Arc::new(PluginFrameUnit::declared(self.host.clone(), id, &frames)));
        }
        if self.disabled.contains(id) {
            return Ok(());
        }
        self.host.load(dir).map(|_| ()).map_err(|e| {
            self.forget(id);
            e
        })
    }

    /// Drops the daemon half of `id` from the running daemon (unloaded, no frames, not listed).
    fn forget(&self, id: &str) {
        self.host.unload(id);
        self.install.late.remove(id);
        self.installed.write().unwrap_or_else(|e| e.into_inner()).remove(id);
    }

    /// What the approval dialog shows about an offered copy, read at listing time from `module.sig.json` and module.json:
    /// `check` (unsigned, digest, signed, untrusted or bad), `signer`, `license`, `grants` (the daemon `needs`) and, when
    /// the check fails, `error`. The same fields `show <id>` prints.
    fn offer_check(&self, dir: &Path) -> serde_json::Value {
        let manifest: serde_json::Value = std::fs::read_to_string(dir.join("module.json")).ok().and_then(|t| serde_json::from_str(&t).ok()).unwrap_or_default();
        let grants = manifest.pointer("/daemon/needs").cloned().unwrap_or_else(|| serde_json::json!([]));
        match super::sources::vet(dir, &self.install.signers.snapshot()) {
            Ok(v) => serde_json::json!({ "check": v.status, "signer": v.signer, "license": v.license, "grants": grants }),
            Err(e) => serde_json::json!({ "check": "bad", "error": e, "license": "", "grants": grants }),
        }
    }

    /// `available`: every module a source offers and every module installed, one row each.
    fn available(&self) -> Vec<serde_json::Value> {
        let offers = installer::catalog(&self.all_sources());
        let installed = super::host_frames::scan_modules(&Some(self.plugins_dir.clone()));
        let disabled = self.disabled.snapshot();
        let statuses = self.host.statuses();
        let bootstrap = self.install.config.bootstrap.as_deref();
        let mut rows: BTreeMap<String, serde_json::Value> = BTreeMap::new();
        for o in &offers {
            rows.insert(
                o.id.clone(),
                serde_json::json!({
                    "id": o.id, "displayName": o.display_name, "description": o.description, "version": o.version, "tier": o.tier,
                    "requires": o.requires, "bootstrap": bootstrap == Some(o.id.as_str()), "daemon": o.daemon, "ui": o.ui,
                    "source": installer::plain(&o.source).display().to_string(), "installed": false, "enabled": false, "status": "available",
                }),
            );
            if let (Some(row), Some(extra)) = (rows.get_mut(&o.id), self.offer_check(&o.dir).as_object()) {
                for (k, v) in extra {
                    row[k] = v.clone();
                }
                row["signed"] = row["check"].clone();
            }
        }
        for m in installed.iter().filter(|m| TIERS.contains(&m.tier.as_str())) {
            let off = disabled.contains(&m.id);
            let status = if off { "disabled".to_string() } else { statuses.get(&m.id).cloned().unwrap_or_else(|| "ui only".into()) };
            let t = |k: &str| super::host_frames::s(&m.manifest, k).to_string();
            let row = rows.entry(m.id.clone()).or_insert_with(|| {
                serde_json::json!({
                    "id": m.id, "displayName": t("displayName"), "description": t("description"), "version": t("version"), "tier": m.tier,
                    "requires": m.manifest.get("requires").cloned().unwrap_or_else(|| serde_json::json!([])),
                    "bootstrap": bootstrap == Some(m.id.as_str()), "daemon": m.manifest.get("daemon").is_some_and(|d| d.is_object()),
                    "ui": m.manifest.get("ui").is_some_and(|d| d.is_object()), "source": null,
                })
            });
            row["installed"] = true.into();
            row["enabled"] = (!off).into();
            row["status"] = status.into();
        }
        // APPS-ROOT-1: an app row carries its member lists (`app`), a member row the apps it belongs to (`memberOf`).
        let decls: Vec<(String, installer::AppDecl)> = rows
            .values()
            .filter_map(|r| {
                let from_manifest = installed.iter().find(|m| m.id == r["id"].as_str().unwrap_or("")).and_then(|m| installer::app_decl(&m.manifest));
                let id = r["id"].as_str()?.to_string();
                from_manifest.or_else(|| offers.iter().find(|o| o.id == id).and_then(|o| o.app.clone())).map(|d| (id, d))
            })
            .collect();
        for (id, d) in &decls {
            if let Some(r) = rows.get_mut(id) {
                r["app"] = serde_json::json!({ "core": d.core, "optional": d.optional });
            }
        }
        for (id, r) in rows.iter_mut() {
            let of: Vec<serde_json::Value> = decls
                .iter()
                .filter(|(a, _)| a != id)
                .filter_map(|(a, d)| {
                    let kind = if d.core.contains(id) { "core" } else if d.optional.contains(id) { "optional" } else { return None };
                    Some(serde_json::json!({ "app": a, "kind": kind }))
                })
                .collect();
            r["memberOf"] = of.into();
        }
        rows.into_values().collect()
    }

    /// `install <id>...`: each module and what it requires, in dependency order, copied from the first source that
    /// offers it and loaded at once. Stops at the first failure and says what was installed before it.
    fn install_ops(&self, want: &[String], approve: bool, bus: &EventBus) -> (i32, String, String) {
        let fail = |e: String| (1, String::new(), e);
        if want.is_empty() {
            return fail("usage: plugin install <id>...".into());
        }
        let offers = installer::catalog(&self.all_sources());
        let want = installer::expand_apps(want, &offers);
        let order = match installer::plan(&want, &offers, &installer::installed_ids(&self.plugins_dir)) {
            Ok(o) => o,
            Err(e) => return fail(e),
        };
        if order.is_empty() {
            return (0, "nothing to install: already installed".into(), String::new());
        }
        let mut done: Vec<String> = Vec::new();
        for offer in order {
            let step = self.vet_offer(&offer, approve).and_then(|_| installer::place(&self.plugins_dir, &offer)).and_then(|dir| {
                self.register(&offer.id, &dir).map_err(|e| {
                    let _ = installer::remove(&self.plugins_dir, &dir);
                    format!("{} not loaded: {e}", offer.id)
                })
            });
            if let Err(e) = step {
                self.sync_bootstrap();
                let before = if done.is_empty() { String::new() } else { format!(" (installed before it: {})", done.join(", ")) };
                return fail(format!("{e}{before}"));
            }
            done.push(offer.id.clone());
            self.changed(bus, &offer.id, true);
        }
        self.sync_bootstrap();
        (0, format!("installed {}", done.join(", ")), String::new())
    }

    /// The install gate: a bad signature or a digest mismatch is refused; a valid signature by a key that is not a trusted
    /// signer needs the owner's `--approve`; a trusted signer, a digest match and an unsigned module pass.
    fn vet_offer(&self, offer: &installer::Offer, approve: bool) -> Result<(), String> {
        let v = super::sources::vet(&offer.dir, &self.install.signers.snapshot()).map_err(|e| format!("{} refused: {e}", offer.id))?;
        if v.status == "untrusted" && !approve {
            return Err(format!("{} is signed by a key that is not a trusted signer; check `plugin show {}` and install with --approve, or add the signer", offer.id, offer.id));
        }
        Ok(())
    }

    /// Every installed module with its version.
    fn installed_versions(&self) -> Vec<(String, String)> {
        super::host_frames::scan_modules(&Some(self.plugins_dir.clone())).into_iter().filter(|m| TIERS.contains(&m.tier.as_str())).map(|m| (m.id.clone(), super::host_frames::s(&m.manifest, "version").to_string())).collect()
    }

    /// `updates`: what the sources offer newer than what is installed.
    fn updates_list(&self) -> Vec<serde_json::Value> {
        installer::updates(&self.installed_versions(), &installer::catalog(&self.all_sources()))
            .into_iter()
            .map(|u| {
                let mut row = serde_json::json!({ "id": u.id, "installed": u.installed, "available": u.available, "source": u.offer.source.display().to_string() });
                if let Some(extra) = self.offer_check(&u.offer.dir).as_object() {
                    for (k, v) in extra {
                        row[k] = v.clone();
                    }
                }
                row
            })
            .collect()
    }

    /// `update <id>...` (none: every update): verify the new copy, stage it, swap it for the installed folder and load it.
    /// A copy that fails its check or does not load leaves the old one exactly as it was (and loaded).
    fn update_ops(&self, want: &[String], approve: bool, bus: &EventBus) -> (i32, String, String) {
        let fail = |e: String| (1, String::new(), e);
        let found = installer::updates(&self.installed_versions(), &installer::catalog(&self.all_sources()));
        let picked: Vec<_> = found.into_iter().filter(|u| want.is_empty() || want.contains(&u.id)).collect();
        if picked.is_empty() {
            return (0, "nothing to update".into(), String::new());
        }
        let mut done: Vec<String> = Vec::new();
        for u in picked {
            let step = (|| -> Result<(), String> {
                if self.install.config.bootstrap.as_deref() == Some(u.id.as_str()) {
                    return Err(format!("{} is the installer: it is updated by a new app build", u.id));
                }
                self.vet_offer(&u.offer, approve)?;
                let current = self.installed.read().unwrap_or_else(|e| e.into_inner()).get(&u.id).map(|i| i.dir.clone()).or_else(|| super::host_frames::scan_modules(&Some(self.plugins_dir.clone())).into_iter().find(|m| m.id == u.id).map(|m| m.dir)).ok_or_else(|| format!("{} is not installed", u.id))?;
                let staged = installer::stage(&self.plugins_dir, &u.offer)?;
                self.forget(&u.id);
                let aside = match installer::swap_in(&staged, &current) {
                    Ok(a) => a,
                    Err(e) => {
                        let _ = std::fs::remove_dir_all(&staged);
                        let _ = self.register(&u.id, &current);
                        return Err(e);
                    }
                };
                if let Err(e) = self.register(&u.id, &current) {
                    let back = installer::swap_back(&aside, &current).and_then(|_| self.register(&u.id, &current));
                    return Err(format!("{} {} not loaded: {e}{}", u.id, u.available, back.err().map(|b| format!(" (and the old copy did not come back: {b})")).unwrap_or_default()));
                }
                let _ = std::fs::remove_dir_all(&aside);
                Ok(())
            })();
            if let Err(e) = step {
                let before = if done.is_empty() { String::new() } else { format!(" (updated before it: {})", done.join(", ")) };
                return fail(format!("{e}{before}"));
            }
            done.push(format!("{} {} -> {}", u.id, u.installed, u.available));
            self.changed(bus, &u.id, true);
        }
        (0, format!("updated {}", done.join(", ")), String::new())
    }

    /// `uninstall <id>`: unloads the module and deletes its folder. The bootstrap plugin stays (it is the way back in),
    /// and so does a module another installed module requires.
    fn uninstall_op(&self, id: &str, bus: &EventBus) -> (i32, String, String) {
        let fail = |e: String| (1, String::new(), e);
        if id.is_empty() {
            return fail("usage: plugin uninstall <id>".into());
        }
        // APPS-ROOT-1: removing an app removes its members (kept: a member another app lists, or another module needs);
        // a core member alone is refused while its app is installed.
        let mods: Vec<(String, serde_json::Value)> = super::host_frames::scan_modules(&Some(self.plugins_dir.clone())).into_iter().filter(|m| TIERS.contains(&m.tier.as_str())).map(|m| (m.id, m.manifest)).collect();
        if let Some((order, kept)) = installer::app_removal(id, &mods, self.install.config.bootstrap.as_deref()) {
            let mut gone: Vec<String> = Vec::new();
            for m in &order {
                let (code, _, err) = self.remove_one(m, bus);
                if code != 0 {
                    let before = if gone.is_empty() { String::new() } else { format!(" (removed before it: {})", gone.join(", ")) };
                    return fail(format!("{err}{before}"));
                }
                gone.push(m.clone());
            }
            let kept = if kept.is_empty() { String::new() } else { format!("; kept {}", kept.iter().map(|(m, why)| format!("{m} ({why})")).collect::<Vec<_>>().join(", ")) };
            return (0, format!("uninstalled {}{kept}", gone.join(", ")), String::new());
        }
        let owners: Vec<&str> = mods.iter().filter(|(a, v)| a != id && installer::app_decl(v).is_some_and(|d| d.core.iter().any(|c| c == id))).map(|(a, _)| a.as_str()).collect();
        if !owners.is_empty() {
            return fail(format!("{id} is a core part of {}; remove the app to remove it", owners.join(", ")));
        }
        self.remove_one(id, bus)
    }

    /// Removes one installed module (no app logic): the installer stays, and so does a module another one requires.
    fn remove_one(&self, id: &str, bus: &EventBus) -> (i32, String, String) {
        let fail = |e: String| (1, String::new(), e);
        if self.install.config.bootstrap.as_deref() == Some(id) {
            return fail(format!("{id} is the installer: it stays, so there is always a way to install"));
        }
        let mods = super::host_frames::scan_modules(&Some(self.plugins_dir.clone()));
        let Some(module) = mods.iter().find(|m| m.id == id && TIERS.contains(&m.tier.as_str())) else {
            return fail(format!("plugin '{id}' is not installed"));
        };
        let needs: Vec<&str> = mods.iter().filter(|m| m.id != id && m.manifest.get("requires").and_then(|r| r.as_array()).is_some_and(|r| r.iter().any(|x| x.as_str() == Some(id)))).map(|m| m.id.as_str()).collect();
        if !needs.is_empty() {
            return fail(format!("{id} is required by {}; uninstall those first", needs.join(", ")));
        }
        self.forget(id);
        if let Err(e) = installer::remove(&self.plugins_dir, &module.dir) {
            return fail(e);
        }
        let _ = self.disabled.set(id, false);
        self.changed(bus, id, false);
        self.sync_bootstrap();
        (0, format!("uninstalled {id}"), String::new())
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
        // `--approve` is the owner's yes to the signer, licence and grants `show` printed for a module signed by a key
        // that is not in the trusted signers.
        let approve = args.iter().any(|a| a == "--approve");
        let args: Vec<String> = args.iter().filter(|a| a.as_str() != "--approve").cloned().collect();
        let args = &args[..];
        let rest: Vec<String> = args.iter().skip(1).map(|a| a.trim().to_string()).filter(|a| !a.is_empty()).collect();
        let id = args.get(1).map(|s| s.trim()).unwrap_or("");
        let fail = |e: String| (1, String::new(), e);
        match op {
            "show" => match installer::catalog(&self.all_sources()).into_iter().find(|o| o.id == id) {
                None => fail(format!("no source offers {id}")),
                Some(o) => {
                    let mut row = serde_json::json!({ "id": o.id, "version": o.version, "requires": o.requires });
                    if let Some(extra) = self.offer_check(&o.dir).as_object() {
                        for (k, v) in extra {
                            row[k] = v.clone();
                        }
                    }
                    (0, serde_json::to_string_pretty(&row).unwrap_or_default(), String::new())
                }
            },
            "signers" => (0, serde_json::to_string_pretty(&self.install.signers.snapshot()).unwrap_or_default(), String::new()),
            "signer-add" => match self.install.signers.add(id, args.get(2).map(String::as_str).unwrap_or("")) {
                Ok(()) => (0, format!("trusted signer {id}"), String::new()),
                Err(e) => fail(e),
            },
            "signer-remove" => match self.install.signers.remove(id) {
                Ok(()) => (0, format!("removed signer {id}"), String::new()),
                Err(e) => fail(e),
            },
            "status" | "list" => {
                let statuses = self.host.statuses();
                let disabled = self.disabled.snapshot();
                let with_daemon: Vec<String> = self.installed.read().unwrap_or_else(|e| e.into_inner()).keys().cloned().collect();
                let mut rows: Vec<serde_json::Value> = with_daemon
                    .iter()
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
                let found = self.installed.read().unwrap_or_else(|e| e.into_inner()).get(id).cloned();
                match found {
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
            "available" => (0, serde_json::to_string_pretty(&self.available()).unwrap_or_default(), String::new()),
            "updates" => (0, serde_json::to_string_pretty(&self.updates_list()).unwrap_or_default(), String::new()),
            "update" => self.update_ops(&rest, approve, bus),
            "sources" => (0, serde_json::to_string_pretty(&self.install.sources.list()).unwrap_or_default(), String::new()),
            "source-add" | "source-remove" | "source-refresh" => {
                let done = match op {
                    "source-add" => self.install.sources.add(id).map(|s| format!("added {} source {} ({})", s.kind, s.id, s.location)),
                    "source-remove" => self.install.sources.remove(id).map(|s| format!("removed source {} ({})", s.id, s.location)),
                    _ => self.install.sources.refresh(id).map(|l| l.join("\n")),
                };
                match done {
                    Ok(out) => {
                        self.catalog_changed(bus);
                        (0, out, String::new())
                    }
                    Err(e) => fail(e),
                }
            }
            "search" => {
                let q = args[1..].join(" ").to_lowercase();
                let rows: Vec<serde_json::Value> = self
                    .available()
                    .into_iter()
                    .filter(|r| q.is_empty() || ["id", "displayName", "description"].iter().any(|k| r[*k].as_str().unwrap_or("").to_lowercase().contains(&q)))
                    .collect();
                (0, serde_json::to_string_pretty(&rows).unwrap_or_default(), String::new())
            }
            "install" => self.install_ops(&rest, approve, bus),
            "uninstall" => self.uninstall_op(id, bus),
            "" => fail("usage: plugin <status|available|search|updates|update|sources|source-add|source-remove|source-refresh|install|uninstall|enable|disable> [id]".into()),
            other => fail(format!("plugin {other}: not supported by this daemon yet (it serves status, available, install, uninstall, enable, disable)")),
        }
    }
}

/// `plugin_admin` (the Kotlin PluginCli over a frame, owner only): `enable` / `disable` a module on this host (its daemon
/// half is loaded or unloaded; one with none is only marked), kept across restarts; `status` (each installed half: load status, the guard's fault, cooling).
/// INSTALLER-1: `available` (what the sources offer and what is installed), `install <id>...` and `uninstall <id>`, live.
/// Other operations (update, rollback, grants) answer exit code 1.
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
    http_manual: OnceLock<Result<ureq::Agent, String>>,
}

impl DaemonServices {
    /// One agent for every plugin call, over the system TLS (Windows SChannel).
    fn agent(&self) -> Result<&ureq::Agent, String> {
        Self::agent_in(&self.http, true)
    }
    /// The same over an agent that never follows a redirect (the plugin host checks each hop itself).
    fn agent_manual(&self) -> Result<&ureq::Agent, String> {
        Self::agent_in(&self.http_manual, false)
    }
    fn agent_in(cell: &OnceLock<Result<ureq::Agent, String>>, follow: bool) -> Result<&ureq::Agent, String> {
        cell.get_or_init(|| {
            let tls = native_tls::TlsConnector::new().map_err(|e| format!("no TLS for plugin HTTP calls: {e}"))?;
            let b = ureq::AgentBuilder::new().tls_connector(Arc::new(tls));
            Ok(if follow { b } else { b.redirects(0) }.build())
        })
        .as_ref()
        .map_err(|e| e.clone())
    }

    fn send_with(agent: &ureq::Agent, req: HttpRequest) -> Result<HttpResponse, String> {
        let timeout = req.timeout_ms.map(u64::from).unwrap_or(HTTP_TIMEOUT_MS);
        let mut r = agent.request(&req.method, &req.url).timeout(Duration::from_millis(timeout));
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

    fn save_settings(&self, s: &BTreeMap<String, String>) -> Result<(), String> {
        let text = serde_json::to_string_pretty(s).map_err(|e| e.to_string())?;
        std::fs::write(self.state_dir.join(SETTINGS_FILE), text).map_err(|e| e.to_string())
    }
}

impl HostServices for DaemonServices {
    fn environment(&self) -> String {
        crate::build_env::current()
    }
    fn log(&self, module: &str, level: LogLevel, line: &str) {
        eprintln!("[plugin:{module}] {level:?} {line}");
    }
    fn setting_get(&self, key: &str) -> Option<String> {
        self.settings.lock().unwrap().get(key).cloned()
    }
    fn app_setting(&self, key: &str) -> Option<String> {
        let raw: String = self.db.lock().ok()?.query_row("SELECT value FROM settings WHERE key = ?1", [key], |r| r.get(0)).ok()?;
        match serde_json::from_str::<serde_json::Value>(&raw).ok()? {
            serde_json::Value::Null => None,
            serde_json::Value::String(s) => Some(s),
            v => Some(v.to_string()),
        }
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
    fn frame_request(&self, module: &str, frame: Frame, caller: Option<&CallContext>, as_owner: bool, _timeout_ms: u32) -> Result<Frame, String> {
        let c = self.composed.get().and_then(Weak::upgrade).ok_or("the daemon is still starting")?;
        let max_depth = self.setting_get(super::scope::MAX_DEPTH_KEY).and_then(|v| v.trim().parse().ok()).unwrap_or(super::scope::MAX_DEPTH);
        if let Some(why) = super::scope::refusal(&frame.r#type, &frame.data, frame.session_id.as_deref(), caller, &c.daemon.records(), max_depth) {
            return Err(why);
        }
        // `account_token`: answered here, never through the frame registry, so the module is the host's (the manifest id)
        // and no client can name another. The provider decides by its `consumers`.
        if frame.r#type == crate::account::frames::TOKEN {
            let provider = serde_json::from_str::<serde_json::Value>(&frame.data).ok().and_then(|v| v.get("provider").and_then(|p| p.as_str()).map(String::from));
            let v = c.account.token_for(provider.as_deref(), module).map_err(|e| format!("account_token refused: {e}"))?;
            return Ok(Frame { r#type: crate::account::frames::TOKEN.into(), data: v.to_string(), session_id: None, request_id: frame.request_id });
        }
        // SEC-PLUGIN-1: the frame is the plugin's, not the owner's. It goes in as a non-owner unless the manifest lists
        // this frame type in daemon.ownerFrames (`as_owner`, official and core only). The one frame a plugin may always
        // send with the vault's owner authority are the read, write and clear of ITS OWN secret, pinned to the calling module.
        let own_secret = [infantry_protocol::frames::PLUGIN_SECRET_GET, infantry_protocol::frames::PLUGIN_SECRET_SET, infantry_protocol::frames::PLUGIN_SECRET_CLEAR].contains(&frame.r#type.as_str());
        let data = if own_secret { super::scope::own_secret_request(&frame.r#type, module, &frame.data)? } else { frame.data };
        let owner = as_owner || own_secret;
        let _rt = self.handle.enter();
        let mut env = Envelope::new(frame.r#type.clone(), data);
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
            c.request(&env, owner)?
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
    fn broadcast(&self, module: &str, r#type: &str, data: &str) {
        // PLUGIN-BROADCAST-1: the host already scoped the type; the envelope names the module that pushed it.
        let mut env = Envelope::new(r#type, data);
        env.origin = Some(infantry_protocol::MessageOrigin::System { kind: format!("plugin:{module}") });
        self.bus.publish(env);
    }
    fn http_send(&self, _module: &str, req: HttpRequest) -> Result<HttpResponse, String> {
        Self::send_with(self.agent()?, req)
    }
    fn http_send_no_redirect(&self, _module: &str, req: HttpRequest) -> Result<HttpResponse, String> {
        Self::send_with(self.agent_manual()?, req)
    }
    fn db(&self) -> Arc<Mutex<rusqlite::Connection>> {
        self.db.clone()
    }
}

/// The owner's community grants ([ALLOW_FILE]): module id -> the grants it lists. A missing or unreadable file grants nothing.
pub fn read_allow(state_dir: &Path) -> BTreeMap<String, Vec<String>> {
    let Some(v) = std::fs::read_to_string(state_dir.join(ALLOW_FILE)).ok().and_then(|t| serde_json::from_str::<serde_json::Value>(&t).ok()) else { return BTreeMap::new() };
    let Some(o) = v.as_object() else { return BTreeMap::new() };
    o.iter()
        .map(|(id, m)| (id.clone(), m.get("allow").and_then(|a| a.as_array()).into_iter().flatten().filter_map(|g| g.as_str().map(|g| g.trim().to_string())).filter(|g| !g.is_empty()).collect()))
        .collect()
}

/// SVC-FIX-7: `limits.plugins.maxDeclaredTimeoutMs` as `settings_set` stores it (the settings table, like the other
/// `limits.*` keys); the old plugin-settings.json value is only a fallback. 0 / unparsable = the built-in default.
fn declared_timeout_cap(services: &dyn HostServices) -> Option<u64> {
    const KEY: &str = "limits.plugins.maxDeclaredTimeoutMs";
    let parse = |v: String| v.trim().trim_matches('"').parse::<u64>().ok().filter(|v| *v > 0);
    services.app_setting(KEY).and_then(parse).or_else(|| services.setting_get(KEY).and_then(parse))
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
pub fn start(state_dir: &Path, dir: Option<PathBuf>, bus: EventBus, handle: tokio::runtime::Handle, disabled: Arc<Disabled>, install: InstallSeam) -> Option<Plugins> {
    let dir = dir.filter(|d| d.is_dir())?;
    // The embedded daemon (desktop app) never runs the distribution main: set the loader error mode here too, before any helper spawns.
    crate::loader_errors::fail_fast();
    // The bootstrap plugin is the way in while nothing else is installed: the owner's disabled mark does not apply then.
    if let Some(id) = install.config.bootstrap.as_deref().filter(|id| disabled.contains(id) && installer::bootstrap_active(&dir, id, install.config.ui_plugin.as_deref())) {
        if let Err(e) = disabled.set(id, false) {
            eprintln!("[infantry] bootstrap plugin {id} stays disabled: {e}");
        }
    }
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
        http_manual: OnceLock::new(),
    });
    let mut limits = infantry_plugin_host::HostLimits::default();
    if let Some(ms) = declared_timeout_cap(services.as_ref()) {
        limits.max_declared_timeout_ms = ms;
    }
    let host = match PluginHost::new(services.clone(), limits) {
        Ok(h) => Arc::new(h),
        Err(e) => {
            eprintln!("[infantry] plugins off: {e}");
            return None;
        }
    };
    for (id, allow) in read_allow(state_dir) {
        host.set_allow(&id, allow);
    }
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
    let admin = Arc::new(Admin { host: host.clone(), plugins_dir: dir, installed: RwLock::new(installed), disabled, install });
    admin.sync_bootstrap();
    Some(Plugins { host, services, admin })
}

/// One frame unit per installed daemon half that declares frames (loaded or not: one enabled later answers at once),
/// plus the owner's `plugin_admin`.
pub fn frame_units(p: &Plugins) -> Vec<Arc<dyn FrameUnit>> {
    let installed = p.admin.installed.read().unwrap_or_else(|e| e.into_inner());
    let mut units: Vec<Arc<dyn FrameUnit>> = installed
        .iter()
        .filter(|(_, m)| !m.frames.is_empty())
        .map(|(id, m)| Arc::new(PluginFrameUnit::declared(p.host.clone(), id, &m.frames)) as Arc<dyn FrameUnit>)
        .collect();
    drop(installed);
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

#[cfg(test)]
mod allow_tests {
    use super::*;

    #[test]
    fn the_community_tier_is_loaded_with_the_owners_grants_and_never_trusted_for_providers() {
        assert!(TIERS.contains(&"community"));
        assert!(!TRUSTED_TIERS.contains(&"community"));
        let dir = std::env::temp_dir().join(format!("infantry-allow-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&dir).unwrap();
        assert!(read_allow(&dir).is_empty(), "no file grants nothing");
        std::fs::write(dir.join(ALLOW_FILE), r#"{"Acme_Hello": {"allow": ["daemon.code", " daemon.tool ", ""]}, "Bad": 3}"#).unwrap();
        let a = read_allow(&dir);
        assert_eq!(a["Acme_Hello"], vec!["daemon.code".to_string(), "daemon.tool".to_string()]);
        assert!(a["Bad"].is_empty());
        std::fs::write(dir.join(ALLOW_FILE), "not json").unwrap();
        assert!(read_allow(&dir).is_empty());
        let _ = std::fs::remove_dir_all(dir);
    }
}

#[cfg(test)]
mod declared_cap_tests {
    use super::*;
    use infantry_plugin_host::MemoryServices;

    #[test]
    fn the_cap_comes_from_the_settings_table_first() {
        const KEY: &str = "limits.plugins.maxDeclaredTimeoutMs";
        let s = MemoryServices::new();
        assert_eq!(declared_timeout_cap(&s), None, "nothing set: the built-in default");
        s.settings.lock().unwrap().insert(KEY.into(), "20000".into());
        assert_eq!(declared_timeout_cap(&s), Some(20_000), "plugin-settings.json is the fallback");
        s.app_settings.lock().unwrap().insert(KEY.into(), "90000".into());
        assert_eq!(declared_timeout_cap(&s), Some(90_000), "what settings_set stores wins");
        s.app_settings.lock().unwrap().insert(KEY.into(), "0".into());
        assert_eq!(declared_timeout_cap(&s), Some(20_000), "0 = unset");
    }
}
