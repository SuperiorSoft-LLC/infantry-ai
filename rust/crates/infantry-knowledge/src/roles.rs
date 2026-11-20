// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The role catalog: custom role files (`<dir>/<id>.md`) over the role bundle a plugin ships (a `roles/index.json`
//! manifest, PM-ROLE-1) over the canonical id contract, plus [`RoleService`]: save, seed (plugin roles), delete (with
//! the "owner removed" mark). Core holds no role text: with no plugin bundle a role is its bare canonical id.
//!
//! Same files and same JSON as the Kotlin Infantry_Roles daemon half, so an existing `roles/` folder keeps working.
use std::collections::{BTreeSet, HashMap, HashSet};
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::RwLock;

use serde::{Deserialize, Serialize};

use crate::avatars::AvatarRef;
use crate::ids;

mod bundle {
    include!(concat!(env!("OUT_DIR"), "/resources.rs"));
}

/// A bundled resource (`/avatars/index.json`, ...), as the Kotlin classpath held it. Role texts are never bundled.
pub fn resource(path: &str) -> Option<&'static [u8]> {
    bundle::RESOURCES.iter().find(|(p, _)| *p == path).map(|(_, b)| *b)
}

pub const EXTENSION: &str = ".md";
pub const MAX_NAME_LENGTH: usize = 60;
pub const MAX_PERSONA_LENGTH: usize = 20_000;
pub const MAX_DESCRIPTION_LENGTH: usize = 200;
pub const MAX_PREFERENCES: usize = 64;
/// The file (not a role) listing plugin role ids the owner deleted.
pub const REMOVED_FILE: &str = ".plugin-roles-removed";
/// `responseDetail` values.
pub const DETAILS: &[&str] = &["ack", "summary", "full"];

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum RoleSource {
    #[default]
    BuiltIn,
    Custom,
    Generated,
}

/// One role, as the `role` / `roles` frames carry it (every field, like kotlinx `encodeDefaults`), plus `avatar`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Role {
    pub id: String,
    pub name: String,
    pub color: String,
    #[serde(default)]
    pub persona: String,
    #[serde(default)]
    pub source: RoleSource,
    #[serde(default)]
    pub icon: String,
    #[serde(default)]
    pub description: String,
    #[serde(default)]
    pub response_detail: String,
    #[serde(default)]
    pub response_max_chars: Option<i64>,
    #[serde(default)]
    pub response_body_chars: Option<i64>,
    #[serde(default)]
    pub plugin: String,
    #[serde(default)]
    pub disabled: bool,
    #[serde(default)]
    pub edited: bool,
    #[serde(default)]
    pub cards: Vec<String>,
    #[serde(default)]
    pub tools: Vec<String>,
    #[serde(default)]
    pub mcp_servers: Vec<String>,
    #[serde(default)]
    pub mcp_purpose: String,
    /// RUST-KNOW-1: where this role's avatar comes from (override / bundled / generated). Filled by the service.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub avatar: Option<AvatarRef>,
}

impl Role {
    fn bare(id: &str, source: RoleSource) -> Role {
        Role {
            id: id.into(),
            name: ids::prettify(id),
            color: ids::color_for(id),
            persona: String::new(),
            source,
            icon: String::new(),
            description: String::new(),
            response_detail: String::new(),
            response_max_chars: None,
            response_body_chars: None,
            plugin: String::new(),
            disabled: false,
            edited: false,
            cards: vec![],
            tools: vec![],
            mcp_servers: vec![],
            mcp_purpose: String::new(),
            avatar: None,
        }
    }
}

/// The `role_save` payload. Decoded strictly: an unknown key is refused.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct RoleSave {
    pub id: String,
    pub name: String,
    #[serde(default)]
    pub color: Option<String>,
    #[serde(default)]
    pub persona: String,
    #[serde(default)]
    pub icon: Option<String>,
    #[serde(default)]
    pub description: Option<String>,
    #[serde(default)]
    pub cards: Option<Vec<String>>,
    #[serde(default)]
    pub tools: Option<Vec<String>>,
    #[serde(default)]
    pub mcp: Option<Vec<String>>,
    #[serde(default)]
    pub mcp_purpose: Option<String>,
}

/// `StrictJson.decode`: the message the Kotlin daemon gives a malformed payload.
pub fn strict<T: serde::de::DeserializeOwned>(text: &str, what: &str) -> Result<T, String> {
    serde_json::from_str(text).map_err(|e| format!("malformed {what}: {}", e.to_string().lines().next().unwrap_or("not a JSON object")))
}

fn normalize_text(s: &str) -> String {
    s.replace("\r\n", "\n").replace('\r', "\n")
}

/// A `#rgb` / `#rrggbb` / `#rrggbbaa` colour lower-cased, else the palette's.
pub fn normalize_color(raw: &str, id: &str) -> String {
    let v = raw.trim();
    let hex = v.strip_prefix('#').filter(|h| matches!(h.len(), 3 | 6 | 8) && h.chars().all(|c| c.is_ascii_hexdigit()));
    if hex.is_some() {
        v.to_lowercase()
    } else {
        ids::color_for(id)
    }
}

// ---- bundled manifest ----------------------------------------------------------------------------------------------

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Entry {
    id: String,
    #[serde(default)]
    name: String,
    #[serde(default)]
    color: String,
    #[serde(default)]
    persona: Option<String>,
    #[serde(default)]
    icon: String,
    #[serde(default)]
    response_detail: String,
    #[serde(default)]
    response_max_chars: Option<i64>,
    #[serde(default)]
    response_body_chars: Option<i64>,
}

#[derive(Deserialize)]
struct Manifest {
    #[serde(default)]
    roles: Vec<Entry>,
}

/// The bundled roles, in manifest order. A persona file the manifest names but the bundle lacks is an error.
pub fn bundled_with(read: impl Fn(&str) -> Option<String>) -> Result<Vec<Role>, String> {
    let Some(text) = read("/roles/index.json") else { return Ok(vec![]) };
    let text = text.trim_start_matches('\u{feff}');
    if text.trim().is_empty() {
        return Ok(vec![]);
    }
    let m: Manifest = serde_json::from_str(text).map_err(|e| format!("/roles/index.json is not a valid role manifest: {e}"))?;
    let mut seen = HashSet::new();
    let mut out = Vec::new();
    for e in m.roles {
        if !ids::is_valid(&e.id) {
            return Err(format!("/roles/index.json has role id '{}': a role id is a lowercase letter then up to 31 lowercase letters, digits or '-'", e.id));
        }
        if !seen.insert(e.id.clone()) {
            return Err(format!("/roles/index.json names role '{}' twice", e.id));
        }
        let persona = match e.persona.as_deref().filter(|p| !p.trim().is_empty()) {
            Some(file) => normalize_text(
                &read(&format!("/roles/{file}")).ok_or_else(|| format!("role '{}' names persona '{file}' but /roles/{file} is not bundled", e.id))?,
            ),
            None => String::new(),
        };
        let mut r = Role::bare(&e.id, RoleSource::BuiltIn);
        r.name = if e.name.trim().is_empty() { e.id.clone() } else { e.name.trim().to_string() };
        r.color = normalize_color(&e.color, &e.id);
        r.persona = persona;
        r.icon = Some(e.icon.trim().to_lowercase()).filter(|i| ids::is_icon(i)).unwrap_or_default();
        r.response_detail = Some(e.response_detail.trim().to_lowercase()).filter(|d| DETAILS.contains(&d.as_str())).unwrap_or_default();
        r.response_max_chars = e.response_max_chars.filter(|n| *n >= 0);
        r.response_body_chars = e.response_body_chars.filter(|n| *n >= 0);
        out.push(r);
    }
    Ok(out)
}

/// PM-ROLE-1: the roles of one plugin's catalog file (`manifest`, the `/roles/index.json` format); each persona file it
/// names is read from the manifest's own folder (a bare file name: no separator, no `..`).
pub fn bundled_from(manifest: &Path) -> Result<Vec<Role>, String> {
    let folder = manifest.parent().map(Path::to_path_buf).unwrap_or_default();
    let index = fs::read_to_string(manifest).map_err(|e| format!("{}: {e}", manifest.display()))?;
    bundled_with(|p| {
        if p == "/roles/index.json" {
            return Some(index.clone());
        }
        let file = p.strip_prefix("/roles/")?;
        if file.is_empty() || file.contains(['/', '\\']) || file.contains("..") {
            return None;
        }
        fs::read_to_string(folder.join(file)).ok()
    })
    .map_err(|e| format!("{}: {e}", manifest.display()))
}

/// PM-ROLE-1: the merged role bundle of every plugin catalog, in order (the first plugin naming an id wins), plus one
/// line per catalog that could not be read.
pub fn plugin_bundle(manifests: &[PathBuf]) -> (Vec<Role>, Vec<String>) {
    let (mut out, mut problems): (Vec<Role>, Vec<String>) = (vec![], vec![]);
    for m in manifests {
        match bundled_from(m) {
            Ok(roles) => {
                for r in roles {
                    if !out.iter().any(|o| o.id == r.id) {
                        out.push(r);
                    }
                }
            }
            Err(e) => problems.push(e),
        }
    }
    (out, problems)
}

// ---- custom role files --------------------------------------------------------------------------------------------

fn id_list(raw: Option<&String>) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    for s in raw.map(String::as_str).unwrap_or("").split(',').map(str::trim) {
        if !s.is_empty() && ids::is_pref_id(s) && !out.iter().any(|o| o == s) {
            out.push(s.to_string());
        }
    }
    out
}

/// Parses one role file (`roleId` = the file name without `.md`).
pub fn parse(role_id: &str, text: &str) -> Result<Role, String> {
    if !ids::is_valid(role_id) {
        return Err(format!("'{role_id}' is not a role id"));
    }
    let text = normalize_text(text.trim_start_matches('\u{feff}'));
    let (front, body) = if let Some(rest) = text.strip_prefix("---\n") {
        let close = rest.find("\n---").map(|i| i + 4).ok_or("front matter opens with '---' but never closes")?;
        // close is relative to text: "---\n" (4) + i
        let front = &text[4..close];
        let after = close + 4;
        let body = if after >= text.len() { "" } else { text[after..].strip_prefix('\n').unwrap_or(&text[after..]) };
        (Some(front.to_string()), body.to_string())
    } else {
        (None, text.clone())
    };
    let mut fields: HashMap<String, String> = HashMap::new();
    for line in front.as_deref().unwrap_or("").lines() {
        let Some(colon) = line.find(':') else { continue };
        if colon == 0 {
            continue;
        }
        let key = line[..colon].trim().to_lowercase();
        let value = line[colon + 1..].trim().trim_matches(|c| c == '"' || c == '\'').to_string();
        if !key.is_empty() {
            fields.insert(key, value);
        }
    }
    let f = |k: &str| fields.get(k).map(|s| s.trim().to_string());
    let mut r = Role::bare(role_id, RoleSource::Custom);
    r.name = f("name").filter(|s| !s.is_empty()).unwrap_or_else(|| role_id.to_string());
    r.color = normalize_color(&f("color").unwrap_or_default(), role_id);
    r.persona = body.trim().to_string();
    r.icon = f("icon").map(|s| s.to_lowercase()).filter(|s| ids::is_icon(s)).unwrap_or_default();
    r.description = f("description").unwrap_or_default();
    r.response_detail = f("responsedetail").map(|s| s.to_lowercase()).filter(|d| DETAILS.contains(&d.as_str())).unwrap_or_default();
    r.response_max_chars = f("responsemaxchars").and_then(|s| s.parse().ok()).filter(|n: &i64| *n >= 0);
    r.response_body_chars = f("responsebodychars").and_then(|s| s.parse().ok()).filter(|n: &i64| *n >= 0);
    r.plugin = f("plugin").filter(|s| ids::is_module(s)).unwrap_or_default();
    r.edited = f("edited").map(|s| s.to_lowercase() == "true").unwrap_or(false);
    r.cards = id_list(fields.get("cards"));
    r.tools = id_list(fields.get("tools"));
    r.mcp_servers = id_list(fields.get("mcp"));
    r.mcp_purpose = f("mcppurpose").unwrap_or_default();
    Ok(r)
}

/// Every `*.md` role in `dir`, plus a line per file that could not be read.
pub fn load_custom(dir: Option<&Path>) -> (Vec<Role>, Vec<String>) {
    let Some(dir) = dir.filter(|d| d.is_dir()) else { return (vec![], vec![]) };
    let mut files: Vec<PathBuf> = fs::read_dir(dir)
        .map(|d| d.flatten().map(|e| e.path()).filter(|p| p.is_file() && p.file_name().and_then(|n| n.to_str()).is_some_and(|n| n.to_lowercase().ends_with(EXTENSION))).collect())
        .unwrap_or_default();
    files.sort_by_key(|p| p.file_name().map(|n| n.to_string_lossy().to_lowercase()));
    let (mut roles, mut problems, mut seen) = (vec![], vec![], HashSet::new());
    for p in files {
        let name = p.file_name().unwrap().to_string_lossy().to_string();
        let id = &name[..name.len() - EXTENSION.len()];
        if !ids::is_valid(id) {
            problems.push(format!("{name}: '{id}' is not a role id (a lowercase letter then up to 31 lowercase letters, digits or '-')"));
            continue;
        }
        if !seen.insert(id.to_string()) {
            problems.push(format!("{name}: a role '{id}' is already defined by an earlier file"));
            continue;
        }
        match fs::read_to_string(&p).map_err(|e| e.to_string()).and_then(|t| parse(id, &t)) {
            Ok(r) => roles.push(r),
            Err(e) => problems.push(format!("{name}: {e}")),
        }
    }
    (roles, problems)
}

// ---- catalog ------------------------------------------------------------------------------------------------------

/// Custom over bundled over canonical; the canonical ids (and `custom`) first, then the rest sorted.
#[derive(Debug, Clone, Default)]
pub struct Catalog {
    ordered: Vec<Role>,
    bundled_ids: HashSet<String>,
}

impl Catalog {
    pub fn new(builtin: Vec<Role>, custom: Vec<Role>) -> Catalog {
        let bundled_ids = builtin.iter().map(|r| r.id.clone()).collect();
        let custom: HashMap<String, Role> = custom.into_iter().map(|r| (r.id.clone(), r)).collect();
        let manifest: HashMap<String, Role> = builtin.into_iter().map(|r| (r.id.clone(), r)).collect();
        let resolve = |id: &str| custom.get(id).or_else(|| manifest.get(id)).cloned().unwrap_or_else(|| Role::bare(id, RoleSource::BuiltIn));
        let mut ordered: Vec<Role> = ids::built_in().map(resolve).collect();
        let extra: BTreeSet<&String> = manifest.keys().chain(custom.keys()).filter(|k| !ids::is_built_in(k)).collect();
        ordered.extend(extra.into_iter().map(|k| resolve(k)));
        Catalog { ordered, bundled_ids }
    }
    pub fn all(&self) -> &[Role] {
        &self.ordered
    }
    pub fn role(&self, id: &str) -> Option<&Role> {
        self.ordered.iter().find(|r| r.id == id)
    }
    /// Always a role: the definition, else a generated one so a badge always paints.
    pub fn rendered(&self, id: &str) -> Role {
        self.role(id).cloned().unwrap_or_else(|| Role::bare(id, RoleSource::Generated))
    }
    pub fn is_bundled(&self, id: &str) -> bool {
        self.bundled_ids.contains(id)
    }
}

/// What [`RoleService::seed`] did with one declared role.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Seeded {
    Added,
    Refreshed,
    Kept,
}

/// The role catalog over a custom roles directory. Thread safe; every write reloads the catalog.
pub struct RoleService {
    dir: PathBuf,
    builtin: Vec<Role>,
    catalog: RwLock<Catalog>,
    write_lock: std::sync::Mutex<()>,
    /// The loaded module ids; None = unknown (no plugin role reads disabled).
    loaded: RwLock<Option<HashSet<String>>>,
}

impl RoleService {
    /// A catalog over `dir` with no plugin bundle: canonical ids and the custom files only.
    pub fn new(dir: impl Into<PathBuf>) -> RoleService {
        Self::with_bundle(dir, vec![])
    }

    pub fn with_bundle(dir: impl Into<PathBuf>, builtin: Vec<Role>) -> RoleService {
        let dir = dir.into();
        let catalog = Catalog::new(builtin.clone(), load_custom(Some(&dir)).0);
        RoleService { dir, builtin, catalog: RwLock::new(catalog), write_lock: Default::default(), loaded: RwLock::new(None) }
    }

    pub fn dir(&self) -> &Path {
        &self.dir
    }

    /// ROLE-PLUGIN-1: the module ids loaded now (a plugin role whose module is not among them reads disabled).
    pub fn set_loaded_modules(&self, modules: Option<HashSet<String>>) {
        *self.loaded.write().unwrap() = modules;
    }

    fn overlay(&self, mut r: Role) -> Role {
        if let Some(loaded) = self.loaded.read().unwrap().as_ref() {
            if !r.plugin.is_empty() && !loaded.contains(&r.plugin) {
                r.disabled = true;
            }
        }
        r
    }

    pub fn reload(&self) {
        *self.catalog.write().unwrap() = Catalog::new(self.builtin.clone(), load_custom(Some(&self.dir)).0);
    }

    pub fn all(&self) -> Vec<Role> {
        self.catalog.read().unwrap().all().iter().cloned().map(|r| self.overlay(r)).collect()
    }

    pub fn role(&self, id: &str) -> Option<Role> {
        self.catalog.read().unwrap().role(id).cloned().map(|r| self.overlay(r))
    }

    pub fn rendered(&self, id: &str) -> Role {
        self.overlay(self.catalog.read().unwrap().rendered(id))
    }

    pub fn exists(&self, id: &str) -> bool {
        self.catalog.read().unwrap().role(id).is_some()
    }

    /// The persona (system prompt) of `id`, None when unknown or blank.
    pub fn persona(&self, id: &str) -> Option<String> {
        self.role(id).map(|r| r.persona).filter(|p| !p.trim().is_empty())
    }

    fn stored_custom(&self, id: &str) -> Option<Role> {
        self.catalog.read().unwrap().role(id).filter(|r| r.source == RoleSource::Custom).cloned()
    }

    /// Validates `req`, writes `<dir>/<id>.md`, reloads and returns the stored role. Err = the refusal reason.
    pub fn save(&self, req: &RoleSave) -> Result<Role, String> {
        let _g = self.write_lock.lock().unwrap();
        let id = req.id.trim();
        let stored = self.stored_custom(id);
        let plugin = stored.as_ref().map(|r| r.plugin.clone()).unwrap_or_default();
        let edited = !plugin.is_empty();
        let cards = req.cards.clone().unwrap_or_else(|| stored.as_ref().map(|r| r.cards.clone()).unwrap_or_default());
        let tools = req.tools.clone().unwrap_or_else(|| stored.as_ref().map(|r| r.tools.clone()).unwrap_or_default());
        let mcp = match &req.mcp {
            Some(m) => dedup(m.iter().map(|s| s.trim().to_string()).filter(|s| !s.is_empty())),
            None => stored.as_ref().map(|r| r.mcp_servers.clone()).unwrap_or_default(),
        };
        let purpose = req.mcp_purpose.as_ref().map(|s| s.trim().to_string()).unwrap_or_else(|| stored.as_ref().map(|r| r.mcp_purpose.clone()).unwrap_or_default());
        let saved = self.write(req, &plugin, edited, &cards, &tools, &mcp, &purpose)?;
        self.unmark_removed(id);
        Ok(saved)
    }

    /// ROLE-PLUGIN-1: seeds a role module `module` declares (persona already resolved).
    pub fn seed(&self, module: &str, declared: &RoleSave) -> Result<Seeded, String> {
        let _g = self.write_lock.lock().unwrap();
        let id = declared.id.trim();
        if self.removed().contains(id) {
            return Ok(Seeded::Kept);
        }
        let existing = self.catalog.read().unwrap().role(id).cloned();
        let bundled = self.builtin.iter().any(|r| r.id == id) || ids::is_built_in(id);
        let cards = declared.cards.clone().unwrap_or_default();
        let tools = declared.tools.clone().unwrap_or_default();
        let (mcp, purpose) = existing.as_ref().map(|r| (r.mcp_servers.clone(), r.mcp_purpose.clone())).unwrap_or_default();
        match &existing {
            None if !bundled => {
                self.write(declared, module, false, &cards, &tools, &mcp, &purpose)?;
                Ok(Seeded::Added)
            }
            None => Ok(Seeded::Kept),
            Some(e) if e.source != RoleSource::Custom || e.plugin != module || e.edited => Ok(Seeded::Kept),
            Some(e) if same(e, declared, &cards, &tools) => Ok(Seeded::Kept),
            Some(_) => {
                self.write(declared, module, false, &cards, &tools, &mcp, &purpose)?;
                Ok(Seeded::Refreshed)
            }
        }
    }

    #[allow(clippy::too_many_arguments)]
    fn write(&self, req: &RoleSave, plugin: &str, edited: bool, cards: &[String], tools: &[String], mcp: &[String], mcp_purpose: &str) -> Result<Role, String> {
        let id = req.id.trim();
        if !ids::is_valid(id) {
            return Err(format!("'{}' is not a role id: a lowercase letter, then up to 31 lowercase letters, digits or '-'", take(id, 40)));
        }
        let current = self.catalog.read().unwrap().role(id).cloned();
        let name = req.name.trim();
        if name.is_empty() {
            return Err("name is blank".into());
        }
        if name.chars().count() > MAX_NAME_LENGTH {
            return Err(format!("name is longer than {MAX_NAME_LENGTH} characters"));
        }
        if name.chars().any(char::is_control) {
            return Err("name contains a control character".into());
        }
        let color = match req.color.as_deref().map(str::trim).filter(|c| !c.is_empty()) {
            Some(raw) => {
                let ok = raw.len() == 7 && raw.starts_with('#') && raw[1..].chars().all(|c| c.is_ascii_hexdigit());
                if !ok {
                    return Err(format!("colour '{}' is not #rrggbb", take(raw, 20)));
                }
                raw.to_lowercase()
            }
            None => current.as_ref().map(|r| r.color.clone()).unwrap_or_else(|| ids::color_for(id)),
        };
        let persona = normalize_text(&req.persona).trim().to_string();
        if persona.chars().count() > MAX_PERSONA_LENGTH {
            return Err(format!("persona is longer than {MAX_PERSONA_LENGTH} characters"));
        }
        let icon = match &req.icon {
            Some(raw) => {
                let raw = raw.trim();
                if !raw.is_empty() && !ids::is_icon(raw) {
                    return Err(format!("icon '{}' is not an icon key (1-32 lowercase letters, digits or '-')", take(raw, 40)));
                }
                raw.to_string()
            }
            None => current.as_ref().map(|r| r.icon.clone()).unwrap_or_default(),
        };
        let description = match &req.description {
            Some(raw) => {
                let raw = raw.trim();
                if raw.chars().count() > MAX_DESCRIPTION_LENGTH {
                    return Err(format!("description is longer than {MAX_DESCRIPTION_LENGTH} characters"));
                }
                if raw.chars().any(char::is_control) {
                    return Err("description must be a single line".into());
                }
                raw.to_string()
            }
            None => current.as_ref().map(|r| r.description.clone()).unwrap_or_default(),
        };
        let mut prefs = Vec::new();
        for (key, list) in [("cards", cards), ("tools", tools)] {
            let clean = dedup(list.iter().map(|s| s.trim().to_string()).filter(|s| !s.is_empty()));
            if let Some(bad) = clean.iter().find(|s| !ids::is_pref_id(s)) {
                return Err(format!("{key} entry '{}' is not an id (letters, digits, _ . : -)", take(bad, 40)));
            }
            if clean.len() > MAX_PREFERENCES {
                return Err(format!("more than {MAX_PREFERENCES} {key}"));
            }
            prefs.push((key, clean));
        }
        if let Some(bad) = mcp.iter().find(|s| !ids::is_pref_id(s)) {
            return Err(format!("mcp entry '{}' is not a server name", take(bad, 40)));
        }
        if mcp_purpose.chars().any(char::is_control) {
            return Err("mcp purpose must be a single line".into());
        }
        if !mcp.is_empty() && mcp_purpose.trim().is_empty() {
            return Err(format!("an mcp block needs a purpose: why this role needs {}", mcp.join(", ")));
        }
        let mut text = format!("---\nname: {name}\ncolor: {color}\n");
        if !icon.is_empty() {
            text += &format!("icon: {icon}\n");
        }
        if !description.is_empty() {
            text += &format!("description: {description}\n");
        }
        if !plugin.is_empty() {
            text += &format!("plugin: {plugin}\n");
            if edited {
                text += "edited: true\n";
            }
        }
        for (key, ids) in &prefs {
            if !ids.is_empty() {
                text += &format!("{key}: {}\n", ids.join(", "));
            }
        }
        if !mcp.is_empty() {
            text += &format!("mcp: {}\n", mcp.join(", "));
        }
        if !mcp_purpose.is_empty() {
            text += &format!("mcpPurpose: {mcp_purpose}\n");
        }
        text += &format!("---\n{persona}\n");
        let parsed = parse(id, &text)?;
        if parsed.name != name {
            return Err(format!("name '{name}' would not read back unchanged"));
        }
        if parsed.icon != icon {
            return Err(format!("icon '{icon}' would not read back unchanged"));
        }
        if parsed.description != description {
            return Err("description would not read back unchanged".into());
        }
        if parsed.plugin != plugin {
            return Err(format!("plugin '{plugin}' would not read back unchanged"));
        }
        fs::create_dir_all(&self.dir).map_err(|e| e.to_string())?;
        let target = self.dir.join(format!("{id}{EXTENSION}"));
        let tmp = self.dir.join(format!(".{id}{EXTENSION}.tmp"));
        fs::write(&tmp, text.as_bytes()).map_err(|e| e.to_string())?;
        fs::rename(&tmp, &target).map_err(|e| e.to_string())?;
        self.reload();
        self.role(id).ok_or_else(|| format!("role '{id}' was written to {id}{EXTENSION} but did not load back"))
    }

    fn removed_path(&self) -> PathBuf {
        self.dir.join(REMOVED_FILE)
    }

    fn removed(&self) -> BTreeSet<String> {
        fs::read_to_string(self.removed_path())
            .map(|t| t.lines().map(str::trim).filter(|l| ids::is_valid(l)).map(String::from).collect())
            .unwrap_or_default()
    }

    fn write_removed(&self, set: &BTreeSet<String>) {
        let _ = fs::create_dir_all(&self.dir);
        let mut text = set.iter().cloned().collect::<Vec<_>>().join("\n");
        text.push('\n');
        let _ = fs::write(self.removed_path(), text);
    }

    fn unmark_removed(&self, id: &str) {
        let mut now = self.removed();
        if now.remove(id) {
            self.write_removed(&now);
        }
    }

    /// Deletes the custom file of `id`. Ok(Some(role)) = reset to the bundled one; Ok(None) = removed.
    pub fn delete(&self, raw: &str, live_role_ids: &HashSet<String>) -> Result<Option<Role>, String> {
        let _g = self.write_lock.lock().unwrap();
        let id = raw.trim();
        if !ids::is_valid(id) {
            return Err(format!("'{}' is not a role id", take(id, 40)));
        }
        let file = self.dir.join(format!("{id}{EXTENSION}"));
        if !file.is_file() {
            return Err(if ids::is_built_in(id) || self.builtin.iter().any(|r| r.id == id) {
                format!("role '{id}' is built in and has no custom definition to delete")
            } else {
                format!("unknown role '{id}'")
            });
        }
        if live_role_ids.contains(id) {
            return Err(format!("role '{id}' has live sessions; end them first"));
        }
        let was_plugin = self.role(id).is_some_and(|r| !r.plugin.is_empty());
        fs::remove_file(&file).map_err(|e| e.to_string())?;
        if was_plugin {
            let mut set = self.removed();
            set.insert(id.to_string());
            self.write_removed(&set);
        }
        self.reload();
        Ok(if ids::is_built_in(id) || self.builtin.iter().any(|r| r.id == id) { self.role(id) } else { None })
    }
}

fn same(r: &Role, d: &RoleSave, cards: &[String], tools: &[String]) -> bool {
    let clean = |l: &[String]| dedup(l.iter().map(|s| s.trim().to_string()).filter(|s| !s.is_empty()));
    r.name == d.name.trim()
        && r.persona == normalize_text(&d.persona).trim()
        && d.color.as_deref().map(str::trim).filter(|c| !c.is_empty()).is_none_or(|c| r.color == c.to_lowercase())
        && d.icon.as_deref().is_none_or(|i| r.icon == i.trim())
        && d.description.as_deref().is_none_or(|x| r.description == x.trim())
        && r.cards == clean(cards)
        && r.tools == clean(tools)
}

fn dedup(it: impl Iterator<Item = String>) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    for s in it {
        if !out.contains(&s) {
            out.push(s);
        }
    }
    out
}

pub(crate) fn take(s: &str, n: usize) -> String {
    s.chars().take(n).collect()
}

// ---- plugin roles -------------------------------------------------------------------------------------------------

/// One role a module declares in its module.json `roles` (ROLE-PLUGIN-1).
#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct RoleDeclaration {
    pub id: String,
    pub name: String,
    pub color: String,
    pub icon: String,
    pub description: String,
    pub persona: String,
    pub cards: Vec<String>,
    pub tools: Vec<String>,
}

/// A loaded module as the seeding needs it: its id, folder and declared roles.
pub struct ModuleRoles {
    pub id: String,
    pub dir: Option<PathBuf>,
    pub roles: Vec<RoleDeclaration>,
}

/// A persona given as a module file name (`roles/x.md`) is read from inside the module folder; any other value is the text.
pub fn plugin_persona(value: &str, dir: Option<&Path>) -> Option<String> {
    let v = value.trim();
    let is_file = !v.contains(['\r', '\n']) && (v.ends_with(".md") || v.ends_with(".txt")) && v.len() > 3;
    if !is_file {
        return Some(value.to_string());
    }
    let root = dir?.canonicalize().ok()?;
    let file = root.join(v).canonicalize().ok()?;
    if !file.starts_with(&root) || file == root || !file.is_file() {
        return None;
    }
    let text = fs::read_to_string(file).ok()?;
    Some(text.chars().take(MAX_PERSONA_LENGTH + 1).collect())
}

/// Seeds every declared role of `modules`; answers (ids written, problems).
pub fn sync_plugin_roles(service: &RoleService, modules: &[ModuleRoles]) -> (Vec<String>, Vec<String>) {
    let (mut written, mut problems) = (vec![], vec![]);
    for m in modules {
        for d in &m.roles {
            let id = d.id.trim().to_string();
            let req = match plugin_persona(&d.persona, m.dir.as_deref()) {
                Some(persona) => RoleSave {
                    id: id.clone(),
                    name: if d.name.trim().is_empty() { ids::prettify(&id) } else { d.name.trim().to_string() },
                    color: Some(d.color.trim().to_string()).filter(|c| !c.is_empty()),
                    persona,
                    icon: Some(d.icon.trim().to_string()),
                    description: Some(d.description.trim().to_string()),
                    cards: Some(d.cards.clone()),
                    tools: Some(d.tools.clone()),
                    mcp: None,
                    mcp_purpose: None,
                },
                None => {
                    problems.push(format!("{} role '{}': persona file '{}' is not in the module folder (write the persona inline)", m.id, take(&id, 40), take(d.persona.trim(), 80)));
                    continue;
                }
            };
            match service.seed(&m.id, &req) {
                Ok(Seeded::Kept) => {}
                Ok(_) => written.push(id),
                Err(e) => problems.push(format!("{} role '{}': {e}", m.id, take(&id, 40))),
            }
        }
    }
    service.set_loaded_modules(Some(modules.iter().map(|m| m.id.clone()).collect()));
    (written, problems)
}

/// The text a new session of `role` receives: its persona, then a short block naming the preferred tools / cards that
/// are loaded now (`tools` = tool names served now, `cards` = loaded card types).
pub fn briefing(role: &Role, tools: &HashSet<String>, cards: &HashSet<String>) -> String {
    let t: Vec<&String> = role.tools.iter().filter(|x| tools.contains(*x)).collect();
    let c: Vec<&String> = role.cards.iter().filter(|x| cards.contains(*x)).collect();
    if t.is_empty() && c.is_empty() {
        return role.persona.clone();
    }
    let mut block = String::from("Preferred for this role (use them when they fit; nothing here is required):");
    if !t.is_empty() {
        block += &format!("\n- tools: {}", t.iter().map(|s| s.as_str()).collect::<Vec<_>>().join(", "));
    }
    if !c.is_empty() {
        block += &format!("\n- cards: {} (`infantry card show <type>` gives a card's format)", c.iter().map(|s| format!("card:{s}")).collect::<Vec<_>>().join(", "));
    }
    if role.persona.trim().is_empty() {
        block
    } else {
        format!("{}\n\n{block}", role.persona)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn core_bundles_no_role_text() {
        assert!(resource("/roles/index.json").is_none(), "role texts come from a plugin, never from core");
        assert!(RoleService::new(tempfile::tempdir().unwrap().path()).persona("pm").is_none());
    }

    #[test]
    fn plugin_bundle_loads_and_canonical_order() {
        let dir = tempfile::tempdir().unwrap();
        fs::write(dir.path().join("index.json"), r##"{"roles":[{"id":"pm","name":"PM","color":"#eab308","persona":"pm.md"},{"id":"zeta","name":"Zeta","persona":"../x.md"}]}"##).unwrap();
        fs::write(dir.path().join("pm.md"), "Run the board.\r\n").unwrap();
        assert!(bundled_from(&dir.path().join("index.json")).unwrap_err().contains("../x.md"), "a persona outside the folder is refused");
        fs::write(dir.path().join("index.json"), r##"{"roles":[{"id":"pm","name":"PM","color":"#eab308","persona":"pm.md"}]}"##).unwrap();
        let other = tempfile::tempdir().unwrap();
        fs::write(other.path().join("index.json"), r#"{"roles":[{"id":"pm","name":"Other"}]}"#).unwrap();
        let (b, problems) = plugin_bundle(&[dir.path().join("index.json"), other.path().join("index.json"), other.path().join("missing.json")]);
        assert_eq!(problems.len(), 1, "{problems:?}");
        assert_eq!(b.len(), 1, "the first plugin naming an id wins");
        assert!(b.iter().any(|r| r.id == "pm" && r.persona.trim() == "Run the board." && !r.persona.contains('\r')));
        let c = Catalog::new(b, vec![]);
        assert_eq!(c.all()[0].id, "architect");
        assert_eq!(c.all().iter().position(|r| r.id == "custom"), Some(16));
        assert_eq!(c.rendered("zzz").source, RoleSource::Generated);
    }

    #[test]
    fn save_reads_back_and_delete_resets() {
        let dir = tempfile::tempdir().unwrap();
        let s = RoleService::new(dir.path());
        let saved = s
            .save(&RoleSave { id: "backend".into(), name: "Backend".into(), persona: "Do backend.\r\n".into(), description: Some("Server side".into()), ..Default::default() })
            .unwrap();
        assert_eq!(saved.source, RoleSource::Custom);
        assert_eq!(saved.persona, "Do backend.");
        assert_eq!(saved.description, "Server side");
        assert!(s.save(&RoleSave { id: "Bad".into(), name: "x".into(), ..Default::default() }).is_err());
        assert!(s.save(&RoleSave { id: "x".into(), name: "x".into(), mcp: Some(vec!["gh".into()]), ..Default::default() }).unwrap_err().contains("needs a purpose"));
        let reset = s.delete("backend", &HashSet::new()).unwrap().unwrap();
        assert_eq!(reset.source, RoleSource::BuiltIn);
        assert!(s.delete("backend", &HashSet::new()).unwrap_err().contains("built in"));
    }

    #[test]
    fn plugin_role_seeded_disabled_and_removed_mark() {
        let dir = tempfile::tempdir().unwrap();
        let s = RoleService::new(dir.path());
        let m = ModuleRoles { id: "Infantry_News".into(), dir: None, roles: vec![RoleDeclaration { id: "news-agent".into(), name: "News Agent".into(), persona: "Fetch news.".into(), tools: vec!["news.headlines".into()], ..Default::default() }] };
        let (w, p) = sync_plugin_roles(&s, std::slice::from_ref(&m));
        assert_eq!((w, p.len()), (vec!["news-agent".to_string()], 0));
        assert_eq!(sync_plugin_roles(&s, std::slice::from_ref(&m)).0.len(), 0);
        s.set_loaded_modules(Some(HashSet::new()));
        assert!(s.role("news-agent").unwrap().disabled);
        assert!(s.delete("news-agent", &HashSet::new()).unwrap().is_none());
        assert_eq!(sync_plugin_roles(&s, &[m]).0.len(), 0, "an owner-removed plugin role is never re-seeded");
        let r = Role { tools: vec!["news.headlines".into()], ..Role::bare("x", RoleSource::Custom) };
        assert!(briefing(&r, &["news.headlines".to_string()].into(), &HashSet::new()).contains("- tools: news.headlines"));
    }
}
