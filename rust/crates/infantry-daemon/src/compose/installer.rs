// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! INSTALLER-1: the install seam. Core's generic way to put a plugin on disk and into the running daemon, and to name
//! the one plugin that does the installing when nothing else is installed.
//!
//! A *source* is a folder laid out like the plugins folder (`<source>/{official,core}/<Module>/module.json`): the
//! bundled folder first (`plugins.bundled`, `INFANTRY_BUNDLED_PLUGINS_DIR`, else `<exe dir>/plugins-bundled`), then the
//! folders listed in the `plugins.sources` setting. A module installs at the tier of the folder it sits in, never by
//! what its manifest says. The *bootstrap plugin* is named by data: `INFANTRY_BOOTSTRAP_PLUGIN`, else the
//! `plugins.bootstrap` setting, else the first module whose manifest says `"bootstrap": true`. Core names no plugin id.
//! A module lists the modules it needs in `requires`; an install brings them first, in dependency order.
//!
//! The registry is built once at start, so a plugin installed later answers its frames through [`LateUnits`], which the
//! composition consults after the registry.

use super::plugins::TIERS;
use infantry_core::frames::FrameUnit;
use serde_json::Value;
use std::collections::{BTreeMap, BTreeSet};
use std::path::{Path, PathBuf};
use std::sync::{Arc, RwLock};

pub const BOOTSTRAP_ENV: &str = "INFANTRY_BOOTSTRAP_PLUGIN";
pub const BOOTSTRAP_KEY: &str = "plugins.bootstrap";
pub const UI_ENV: &str = "INFANTRY_UI_PLUGIN";
pub const UI_KEY: &str = "plugins.ui";
pub const BUNDLED_ENV: &str = "INFANTRY_BUNDLED_PLUGINS_DIR";
pub const BUNDLED_KEY: &str = "plugins.bundled";
pub const SOURCES_KEY: &str = "plugins.sources";
/// The bundled folder when nothing names one: next to the executable, beside the plugins folder.
pub const BUNDLED_DIR: &str = "plugins-bundled";

/// Where the plugins come from and which one bootstraps.
#[derive(Debug, Clone, Default)]
pub struct InstallConfig {
    pub bootstrap: Option<String>,
    /// BOOT-CHAIN-1: the UI plugin (the shell and its layout), named by data like the bootstrap plugin: `INFANTRY_UI_PLUGIN`,
    /// else the `plugins.ui` setting, else the first module whose manifest says `"uiPlugin": true`. None: this daemon has none.
    pub ui_plugin: Option<String>,
    pub sources: Vec<PathBuf>,
}

/// APPS-ROOT-1: a module that is an *app* lists the modules that belong to it, in its manifest:
/// `"app": { "core": ["A", "B"], "optional": ["C"] }`. `core` members come with the app and cannot be removed while it
/// is installed; `optional` members are offered and installed or removed on their own. Moving an id between the two lists
/// is a one-line manifest edit. Core names no plugin id: the lists are data an app's developer sets.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct AppDecl {
    pub core: Vec<String>,
    pub optional: Vec<String>,
}

impl AppDecl {
    pub fn members(&self) -> impl Iterator<Item = &String> {
        self.core.iter().chain(self.optional.iter())
    }
}

/// The `app` declaration of a manifest, when it has one.
pub fn app_decl(manifest: &Value) -> Option<AppDecl> {
    let a = manifest.get("app")?.as_object()?;
    let ids = |k: &str| -> Vec<String> { a.get(k).and_then(Value::as_array).map(|v| v.iter().filter_map(|x| x.as_str().map(String::from)).filter(|x| safe_name(x)).collect()).unwrap_or_default() };
    Some(AppDecl { core: ids("core"), optional: ids("optional") })
}

/// One module a source offers.
#[derive(Debug, Clone)]
pub struct Offer {
    pub id: String,
    /// `official` or `core`: the folder of the source it sits in, and the folder it installs to.
    pub tier: String,
    pub dir: PathBuf,
    pub source: PathBuf,
    pub version: String,
    pub display_name: String,
    pub description: String,
    pub requires: Vec<String>,
    /// Set when this module is an app (see [`AppDecl`]).
    pub app: Option<AppDecl>,
    pub bootstrap: bool,
    pub ui_plugin: bool,
    pub daemon: bool,
    pub ui: bool,
}

fn text(v: &Value, k: &str) -> String {
    v.get(k).and_then(Value::as_str).map(str::trim).unwrap_or("").to_string()
}

/// A setting that holds a list of folders: a JSON array of strings, or one string split on `;` and new lines.
fn list(v: Option<Value>) -> Vec<String> {
    match v {
        Some(Value::Array(a)) => a.iter().filter_map(|x| x.as_str().map(|s| s.trim().to_string())).filter(|s| !s.is_empty()).collect(),
        Some(Value::String(s)) => s.split([';', '\n']).map(|p| p.trim().to_string()).filter(|p| !p.is_empty()).collect(),
        _ => Vec::new(),
    }
}

fn one(v: Option<Value>) -> Option<String> {
    v.and_then(|v| v.as_str().map(|s| s.trim().to_string())).filter(|s| !s.is_empty())
}

/// A folder name that is safe to create under the plugins folder: one path segment, no dots first.
pub fn safe_name(id: &str) -> bool {
    !id.is_empty() && id.len() <= 64 && !id.starts_with('.') && id.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '_' | '-' | '.'))
}

/// The module in `dir` as an offer, when its manifest reads and its name is a safe folder name.
fn offer_of(dir: &Path, tier: &str, source: &Path) -> Option<Offer> {
    let v: Value = serde_json::from_str(&std::fs::read_to_string(dir.join("module.json")).ok()?).ok()?;
    let id = text(&v, "name");
    if !safe_name(&id) {
        return None;
    }
    let requires = v.get("requires").and_then(Value::as_array).map(|a| a.iter().filter_map(|x| x.as_str().map(String::from)).collect()).unwrap_or_default();
    Some(Offer {
        id,
        tier: tier.to_string(),
        dir: dir.to_path_buf(),
        source: source.to_path_buf(),
        version: text(&v, "version"),
        display_name: text(&v, "displayName"),
        description: text(&v, "description"),
        requires,
        app: app_decl(&v),
        bootstrap: v.get("bootstrap").and_then(Value::as_bool).unwrap_or(false),
        ui_plugin: v.get("uiPlugin").and_then(Value::as_bool).unwrap_or(false),
        daemon: v.get("daemon").is_some_and(Value::is_object),
        ui: v.get("ui").is_some_and(Value::is_object),
    })
}

/// Every module the folders offer, sources in order (the first to offer an id wins), tiers in precedence order.
pub fn catalog(sources: &[PathBuf]) -> Vec<Offer> {
    let mut out: Vec<Offer> = Vec::new();
    for source in sources {
        for tier in TIERS {
            let Ok(rd) = std::fs::read_dir(source.join(tier)) else { continue };
            let mut dirs: Vec<PathBuf> = rd.filter_map(|e| e.ok()).map(|e| e.path()).filter(|p| p.join("module.json").is_file()).collect();
            dirs.sort();
            for dir in dirs {
                if let Some(o) = offer_of(&dir, tier, source) {
                    if !out.iter().any(|x| x.id == o.id) {
                        out.push(o);
                    }
                }
            }
        }
    }
    out
}

/// The ids of the modules installed in the plugins folder (a daemon half or not).
pub fn installed_ids(plugins_dir: &Path) -> BTreeSet<String> {
    super::host_frames::scan_modules(&Some(plugins_dir.to_path_buf())).into_iter().map(|m| m.id).collect()
}

/// True while the bootstrap plugin is installed and the app has nothing to start from: nothing else is installed, or the
/// daemon names a UI plugin (BOOT-CHAIN-1) and it is not installed. The daemon then offers the installer, not the app.
pub fn bootstrap_active(plugins_dir: &Path, bootstrap: &str, ui_plugin: Option<&str>) -> bool {
    let ids = installed_ids(plugins_dir);
    ids.contains(bootstrap) && (ids.iter().all(|i| i == bootstrap) || ui_plugin.is_some_and(|u| u != bootstrap && !ids.contains(u)))
}

/// The sources and the bootstrap id from the environment, the settings and the manifests (`setting` reads an app-scope
/// setting). The bootstrap manifest search covers the installed modules first, then the sources.
pub fn config(plugins_dir: &Option<PathBuf>, setting: &dyn Fn(&str) -> Option<Value>) -> InstallConfig {
    let mut sources: Vec<PathBuf> = Vec::new();
    let bundled = crate::auth::env(BUNDLED_ENV)
        .or_else(|| one(setting(BUNDLED_KEY)))
        .map(PathBuf::from)
        .or_else(|| std::env::current_exe().ok()?.parent().map(|p| p.join(BUNDLED_DIR)));
    let canon = |p: &Path| std::fs::canonicalize(p).unwrap_or_else(|_| p.to_path_buf());
    let own = plugins_dir.as_deref().map(canon);
    for s in bundled.into_iter().chain(list(setting(SOURCES_KEY)).into_iter().map(PathBuf::from)) {
        if !s.is_dir() {
            continue;
        }
        let c = canon(&s);
        if own.as_ref() == Some(&c) || sources.iter().any(|x| canon(x) == c) {
            continue;
        }
        sources.push(s);
    }
    let named = crate::auth::env(BOOTSTRAP_ENV).or_else(|| one(setting(BOOTSTRAP_KEY)));
    let bootstrap = named.or_else(|| {
        let installed = super::host_frames::scan_modules(plugins_dir).into_iter().find(|m| m.manifest.get("bootstrap").and_then(Value::as_bool) == Some(true)).map(|m| m.id);
        installed.or_else(|| catalog(&sources).into_iter().find(|o| o.bootstrap).map(|o| o.id))
    });
    let flagged = |key: &str| {
        let installed = super::host_frames::scan_modules(plugins_dir).into_iter().find(|m| m.manifest.get(key).and_then(Value::as_bool) == Some(true)).map(|m| m.id);
        installed.or_else(|| catalog(&sources).into_iter().find(|o| o.ui_plugin).map(|o| o.id))
    };
    let ui_plugin = crate::auth::env(UI_ENV).or_else(|| one(setting(UI_KEY))).or_else(|| flagged("uiPlugin"));
    InstallConfig { bootstrap, ui_plugin, sources }
}

/// The modules to install, in the order to install them: `want` and everything they `requires`, each module after the
/// ones it needs, none that is installed already. An id no source offers, or a cycle, is a refusal.
pub fn plan(want: &[String], offers: &[Offer], installed: &BTreeSet<String>) -> Result<Vec<Offer>, String> {
    fn visit(id: &str, parent: Option<&str>, offers: &[Offer], installed: &BTreeSet<String>, stack: &mut Vec<String>, out: &mut Vec<Offer>) -> Result<(), String> {
        if installed.contains(id) || out.iter().any(|o| o.id == id) {
            return Ok(());
        }
        if stack.iter().any(|s| s == id) {
            return Err(format!("{} loops back to {id}", stack.join(" -> ")));
        }
        let Some(offer) = offers.iter().find(|o| o.id == id) else {
            return Err(match parent {
                Some(p) => format!("{p} requires {id}, which no source offers"),
                None => format!("no source offers {id}"),
            });
        };
        stack.push(id.to_string());
        for r in &offer.requires {
            visit(r, Some(id), offers, installed, stack, out)?;
        }
        stack.pop();
        out.push(offer.clone());
        Ok(())
    }
    let mut out = Vec::new();
    for id in want {
        visit(id, None, offers, installed, &mut Vec::new(), &mut out)?;
    }
    Ok(out)
}

/// What an install of `want` means once apps are read: an app is preceded by its core members (so the app, which switches
/// the window over, lands last and a failed member leaves the installer on screen), every other id stays as asked.
pub fn expand_apps(want: &[String], offers: &[Offer]) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    for id in want {
        if let Some(app) = offers.iter().find(|o| &o.id == id).and_then(|o| o.app.as_ref()) {
            for m in &app.core {
                if !out.contains(m) {
                    out.push(m.clone());
                }
            }
        }
        if !out.contains(id) {
            out.push(id.clone());
        }
    }
    out
}

fn requires_of(v: &Value) -> Vec<String> {
    v.get("requires").and_then(Value::as_array).map(|a| a.iter().filter_map(|x| x.as_str().map(String::from)).collect()).unwrap_or_default()
}

/// What removing the app `id` takes away, in the order to remove it. `modules` is every installed module with its
/// manifest. A member stays when another installed app also lists it, when an installed module outside the set requires it
/// (the second list says which were kept and why), or when it is `keep` (the installer). None when `id` is no app.
pub fn app_removal(id: &str, modules: &[(String, Value)], keep: Option<&str>) -> Option<(Vec<String>, Vec<(String, String)>)> {
    let decl = app_decl(&modules.iter().find(|(m, _)| m == id)?.1)?;
    let installed = |m: &str| modules.iter().any(|(x, _)| x == m);
    let mut set: Vec<String> = decl.members().filter(|m| installed(m) && Some(m.as_str()) != keep).cloned().collect();
    set.push(id.to_string());
    let mut kept: Vec<(String, String)> = Vec::new();
    let others: Vec<(&String, AppDecl)> = modules.iter().filter(|(m, _)| m != id).filter_map(|(m, v)| app_decl(v).map(|d| (m, d))).collect();
    set.retain(|m| {
        if m == id {
            return true;
        }
        match others.iter().find(|(_, d)| d.members().any(|x| x == m)) {
            Some((app, _)) => {
                kept.push((m.clone(), format!("also part of {app}")));
                false
            }
            None => true,
        }
    });
    // A module outside the set that requires a member keeps that member.
    loop {
        let hit = set.iter().filter(|m| m.as_str() != id).find_map(|m| modules.iter().find(|(o, v)| !set.contains(o) && requires_of(v).contains(m)).map(|(o, _)| (m.clone(), o.clone())));
        let Some((member, by)) = hit else { break };
        kept.push((member.clone(), format!("required by {by}")));
        set.retain(|m| *m != member);
    }
    // Dependents first: a module goes only once nothing left in the set requires it.
    let mut order: Vec<String> = Vec::new();
    let mut left = set;
    while !left.is_empty() {
        let pick = left.iter().position(|m| !left.iter().any(|o| o != m && modules.iter().find(|(x, _)| x == o).is_some_and(|(_, v)| requires_of(v).contains(m)))).unwrap_or(0);
        order.push(left.remove(pick));
    }
    Some((order, kept))
}

/// Copies a module folder (files and folders; a link is refused, so a source cannot reach outside itself).
fn copy_dir(from: &Path, to: &Path) -> Result<(), String> {
    std::fs::create_dir_all(to).map_err(|e| format!("{}: {e}", to.display()))?;
    for entry in std::fs::read_dir(from).map_err(|e| format!("{}: {e}", from.display()))? {
        let entry = entry.map_err(|e| e.to_string())?;
        let (src, dst) = (entry.path(), to.join(entry.file_name()));
        let kind = std::fs::symlink_metadata(&src).map_err(|e| format!("{}: {e}", src.display()))?.file_type();
        if kind.is_symlink() {
            return Err(format!("{} is a link; a module is copied from plain files only", src.display()));
        } else if kind.is_dir() {
            copy_dir(&src, &dst)?;
        } else {
            std::fs::copy(&src, &dst).map_err(|e| format!("{} -> {}: {e}", src.display(), dst.display()))?;
        }
    }
    Ok(())
}

/// Puts `offer` into `<plugins>/<tier>/<id>` (copied to a staging folder first, then renamed, so a failed copy leaves
/// nothing half-installed). Returns the installed folder.
pub fn place(plugins_dir: &Path, offer: &Offer) -> Result<PathBuf, String> {
    if !safe_name(&offer.id) || !TIERS.contains(&offer.tier.as_str()) {
        return Err(format!("{}: not a name or tier a module installs under", offer.id));
    }
    let tier_dir = plugins_dir.join(&offer.tier);
    let target = tier_dir.join(&offer.id);
    if target.exists() {
        return Err(format!("{} is already installed", offer.id));
    }
    // Staged under `<plugins>/.installing`, which is no tier, so a crash mid-copy leaves nothing a scan would load.
    let staging = plugins_dir.join(".installing").join(&offer.id);
    let _ = std::fs::remove_dir_all(&staging);
    std::fs::create_dir_all(&tier_dir).map_err(|e| format!("{}: {e}", tier_dir.display()))?;
    let result = copy_dir(&offer.dir, &staging).and_then(|_| std::fs::rename(&staging, &target).map_err(|e| format!("{}: {e}", target.display())));
    if result.is_err() {
        let _ = std::fs::remove_dir_all(&staging);
    }
    result.map(|_| target)
}

/// Removes an installed module folder, only when it sits where `place` puts one (`<plugins>/<tier>/<id>`).
pub fn remove(plugins_dir: &Path, dir: &Path) -> Result<(), String> {
    let canon = |p: &Path| std::fs::canonicalize(p).map_err(|e| format!("{}: {e}", p.display()));
    let (root, dir) = (canon(plugins_dir)?, canon(dir)?);
    let tier = dir.parent().and_then(|t| t.file_name()).and_then(|n| n.to_str()).unwrap_or("");
    if dir.parent().and_then(|t| t.parent()) != Some(root.as_path()) || !TIERS.contains(&tier) {
        return Err(format!("{} is not an installed module folder", dir.display()));
    }
    std::fs::remove_dir_all(&dir).map_err(|e| format!("{}: {e}", dir.display()))
}

/// A daemon that has nothing installed gets its bootstrap plugin from the sources: copied into the plugins folder
/// (created when it is missing). Does nothing when anything is installed, when no bootstrap plugin is named, or when no
/// source offers it. Returns the id it installed.
pub fn ensure_bootstrap(plugins_dir: &Option<PathBuf>, cfg: &InstallConfig) -> Option<String> {
    let (dir, id) = (plugins_dir.as_ref()?, cfg.bootstrap.as_ref()?);
    if !installed_ids(dir).is_empty() {
        return None;
    }
    let offer = catalog(&cfg.sources).into_iter().find(|o| &o.id == id)?;
    if std::fs::create_dir_all(dir.join(&offer.tier)).is_err() {
        return None;
    }
    match place(dir, &offer) {
        Ok(_) => Some(offer.id),
        Err(e) => {
            eprintln!("[infantry] bootstrap plugin {id} not installed: {e}");
            None
        }
    }
}

/// The frames of plugins installed after start. [`Composed`](super::Composed) asks it after the registry, so a frame a
/// start-time unit owns is never taken over.
#[derive(Default)]
pub struct LateUnits {
    by_frame: RwLock<BTreeMap<String, Arc<dyn FrameUnit>>>,
    by_module: RwLock<BTreeMap<String, Vec<String>>>,
}

impl LateUnits {
    /// Registers `unit` for `frames`, kept under `module` so an uninstall can take them away again.
    pub fn add(&self, module: &str, frames: &[String], unit: Arc<dyn FrameUnit>) {
        let mut by_frame = self.by_frame.write().unwrap_or_else(|e| e.into_inner());
        for f in frames {
            by_frame.insert(f.clone(), unit.clone());
        }
        self.by_module.write().unwrap_or_else(|e| e.into_inner()).insert(module.to_string(), frames.to_vec());
    }

    pub fn remove(&self, module: &str) {
        let frames = self.by_module.write().unwrap_or_else(|e| e.into_inner()).remove(module).unwrap_or_default();
        let mut by_frame = self.by_frame.write().unwrap_or_else(|e| e.into_inner());
        for f in frames {
            by_frame.remove(&f);
        }
    }

    pub fn owner(&self, frame: &str) -> Option<Arc<dyn FrameUnit>> {
        self.by_frame.read().unwrap_or_else(|e| e.into_inner()).get(frame).cloned()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn module(root: &Path, tier: &str, id: &str, extra: Value) {
        let dir = root.join(tier).join(id);
        std::fs::create_dir_all(dir.join("ui/dist")).unwrap();
        let mut m = json!({ "name": id, "version": "1.0.0", "ui": { "apiVersion": 1, "entry": "ui/dist/index.js" } });
        if let (Some(o), Some(e)) = (m.as_object_mut(), extra.as_object()) {
            o.extend(e.clone());
        }
        std::fs::write(dir.join("module.json"), m.to_string()).unwrap();
        std::fs::write(dir.join("ui/dist/index.js"), "export default {};").unwrap();
    }

    fn tmp(name: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("infantry-installer-{name}-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&d).unwrap();
        d
    }

    #[test]
    fn an_install_brings_what_a_module_requires_first_and_skips_what_is_there() {
        let src = tmp("plan");
        module(&src, "official", "App_Ui", json!({}));
        module(&src, "official", "App_Board", json!({ "requires": ["App_Ui"] }));
        module(&src, "official", "App_Mail", json!({ "requires": ["App_Board", "App_Ui"] }));
        let offers = catalog(&[src.clone()]);
        let ids = |p: Result<Vec<Offer>, String>| p.unwrap().into_iter().map(|o| o.id).collect::<Vec<_>>();
        assert_eq!(ids(plan(&["App_Mail".into()], &offers, &BTreeSet::new())), ["App_Ui", "App_Board", "App_Mail"]);
        let have: BTreeSet<String> = ["App_Ui".to_string()].into();
        assert_eq!(ids(plan(&["App_Mail".into(), "App_Board".into()], &offers, &have)), ["App_Board", "App_Mail"]);
        assert!(plan(&["App_Gone".into()], &offers, &BTreeSet::new()).unwrap_err().contains("no source offers App_Gone"));
        let _ = std::fs::remove_dir_all(&src);
    }

    #[test]
    fn a_missing_dependency_and_a_cycle_are_refused() {
        let src = tmp("bad");
        module(&src, "official", "Needs_Gone", json!({ "requires": ["Gone"] }));
        module(&src, "official", "Loop_A", json!({ "requires": ["Loop_B"] }));
        module(&src, "official", "Loop_B", json!({ "requires": ["Loop_A"] }));
        let offers = catalog(&[src.clone()]);
        assert_eq!(plan(&["Needs_Gone".into()], &offers, &BTreeSet::new()).unwrap_err(), "Needs_Gone requires Gone, which no source offers");
        assert!(plan(&["Loop_A".into()], &offers, &BTreeSet::new()).unwrap_err().contains("loops back to Loop_A"));
        let _ = std::fs::remove_dir_all(&src);
    }

    #[test]
    fn a_module_installs_at_the_tier_of_its_source_folder_and_leaves_nothing_half_done() {
        let (src, dest) = (tmp("src"), tmp("dest"));
        module(&src, "core", "Core_Thing", json!({ "tier": "official" }));
        let offer = catalog(&[src.clone()]).remove(0);
        assert_eq!(offer.tier, "core", "the folder decides, not the manifest");
        std::fs::create_dir_all(dest.join("core")).unwrap();
        let dir = place(&dest, &offer).unwrap();
        assert_eq!(dir, dest.join("core").join("Core_Thing"));
        assert!(dir.join("ui/dist/index.js").is_file());
        assert!(place(&dest, &offer).unwrap_err().contains("already installed"));
        assert_eq!(std::fs::read_dir(dest.join("core")).unwrap().count(), 1);
        assert!(!dest.join(".installing").join("Core_Thing").exists(), "no staging folder is left");
        assert!(remove(&dest, &src.join("core").join("Core_Thing")).is_err(), "only a folder under the plugins folder is removed");
        remove(&dest, &dir).unwrap();
        assert!(!dir.exists());
        for d in [src, dest] {
            let _ = std::fs::remove_dir_all(d);
        }
    }

    #[test]
    fn bootstrap_is_named_by_data_and_active_only_while_nothing_else_is_installed() {
        let (plugins, src) = (tmp("plugins"), tmp("bundle"));
        module(&src, "official", "Any_Installer", json!({ "bootstrap": true }));
        module(&src, "official", "Any_Other", json!({}));
        let cfg = config(&Some(plugins.clone()), &|k| (k == SOURCES_KEY).then(|| json!([src.display().to_string()])));
        assert_eq!(cfg.bootstrap.as_deref(), Some("Any_Installer"), "the manifest flag names it");
        assert_eq!(cfg.sources, [src.clone()]);
        let named = config(&Some(plugins.clone()), &|k| match k {
            BOOTSTRAP_KEY => Some(json!("Any_Other")),
            SOURCES_KEY => Some(json!(src.display().to_string())),
            _ => None,
        });
        assert_eq!(named.bootstrap.as_deref(), Some("Any_Other"), "the setting beats the manifest flag");
        // An empty plugins folder gets the bootstrap plugin from the source, and is then in bootstrap mode.
        assert_eq!(ensure_bootstrap(&Some(plugins.clone()), &cfg).as_deref(), Some("Any_Installer"));
        assert!(bootstrap_active(&plugins, "Any_Installer", None));
        // Anything installed ends it, and a folder that already holds something is left alone.
        let other = catalog(&[src.clone()]).into_iter().find(|o| o.id == "Any_Other").unwrap();
        place(&plugins, &other).unwrap();
        assert!(!bootstrap_active(&plugins, "Any_Installer", None));
        assert_eq!(ensure_bootstrap(&Some(plugins.clone()), &cfg), None);
        for d in [plugins, src] {
            let _ = std::fs::remove_dir_all(d);
        }
    }

    #[test]
    fn a_named_ui_plugin_that_is_not_installed_keeps_bootstrap_mode_on() {
        let (plugins, src) = (tmp("ui-plugins"), tmp("ui-bundle"));
        module(&src, "official", "Any_Installer", json!({ "bootstrap": true }));
        module(&src, "official", "Any_Ui", json!({ "uiPlugin": true }));
        module(&src, "official", "Any_Other", json!({}));
        let cfg = config(&Some(plugins.clone()), &|k| (k == SOURCES_KEY).then(|| json!([src.display().to_string()])));
        assert_eq!(cfg.ui_plugin.as_deref(), Some("Any_Ui"), "the manifest flag names it");
        let by_setting = config(&Some(plugins.clone()), &|k| (k == UI_KEY).then(|| json!("Any_Other")));
        assert_eq!(by_setting.ui_plugin.as_deref(), Some("Any_Other"), "the setting beats the flag");
        let find = |id: &str| catalog(&[src.clone()]).into_iter().find(|o| o.id == id).unwrap();
        place(&plugins, &find("Any_Installer")).unwrap();
        place(&plugins, &find("Any_Other")).unwrap();
        // Something else is installed, but the UI plugin is not: the installer is still the window.
        assert!(bootstrap_active(&plugins, "Any_Installer", Some("Any_Ui")));
        place(&plugins, &find("Any_Ui")).unwrap();
        assert!(!bootstrap_active(&plugins, "Any_Installer", Some("Any_Ui")));
        remove(&plugins, &plugins.join("official").join("Any_Ui")).unwrap();
        assert!(bootstrap_active(&plugins, "Any_Installer", Some("Any_Ui")), "removing the UI plugin falls back to the installer");
        for d in [plugins, src] {
            let _ = std::fs::remove_dir_all(d);
        }
    }

    #[test]
    fn a_module_name_that_is_not_a_plain_folder_name_is_never_offered() {
        assert!(safe_name("Infantry_Thing-2.x"));
        for bad in ["", ".hidden", "a/b", "..", "a\\b", "a b"] {
            assert!(!safe_name(bad), "{bad}");
        }
        let src = tmp("names");
        module(&src, "official", "Fine_One", json!({}));
        let dir = src.join("official").join("Odd");
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("module.json"), r#"{"name": "../escape"}"#).unwrap();
        let ids: Vec<String> = catalog(&[src.clone()]).into_iter().map(|o| o.id).collect();
        assert_eq!(ids, ["Fine_One"]);
        let _ = std::fs::remove_dir_all(&src);
    }
}
