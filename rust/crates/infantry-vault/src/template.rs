// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! SECRET-RENDER-1: the ONE placeholder syntax and the renders built on it. `{{secret:NAME}}` is secret NAME's single
//! value (or its only field); `{{secret:NAME#FIELD}}` one field. `{{!secret:NAME}}` is the escape and stays as written.
//! The syntax is the same in every text format; nothing here parses .env, JSON or YAML.
//!
//! [`RunSecrets`] is what a run, a render or an agent tool may see: the user's secrets (never a built-in or a harness
//! key), project scope first, then app. [`run_secrets`], [`secret_find`] and [`SecretRenderer`] answer names, placeholders
//! and paths only; the rendered text goes to an owner-only file and nowhere else.
use std::collections::{BTreeMap, HashMap};
use std::path::{Path, PathBuf};
use std::sync::{Arc, OnceLock};

use regex::Regex;

use crate::keystore::{self, Keystore, SCOPE_APP, SCOPE_PROJECT, VALUE_FIELD};
use crate::limits::{self, Limits};
use crate::VaultError;

pub const PLACEHOLDER_OPEN: &str = "{{secret:";
pub const ESCAPED_OPEN: &str = "{{!secret:";

fn re() -> &'static Regex {
    static R: OnceLock<Regex> = OnceLock::new();
    R.get_or_init(|| Regex::new(r"\{\{(!?)secret:([a-z0-9][a-z0-9._-]{0,63})(?:#([A-Za-z_][A-Za-z0-9_]{0,63}))?\}\}").expect("placeholder regex"))
}

/// The placeholder a UI shows for secret `name`.
pub fn placeholder(name: &str) -> String {
    format!("{PLACEHOLDER_OPEN}{name}}}}}")
}

/// The exact placeholder for each field (a one-field secret is just `{{secret:NAME}}`); names only.
pub fn placeholders(name: &str, fields: &[String]) -> Vec<(String, String)> {
    if fields.len() <= 1 {
        return vec![(fields.first().cloned().unwrap_or_else(|| VALUE_FIELD.into()), placeholder(name))];
    }
    let mut f = fields.to_vec();
    f.sort();
    f.into_iter().map(|x| {
        let p = format!("{PLACEHOLDER_OPEN}{name}#{x}}}}}");
        (x, p)
    }).collect()
}

fn norm(s: &str) -> String {
    s.to_lowercase().replace('-', "_")
}

/// The field of `fields` that `wrong` means when only case or `-`/`_` differ.
pub fn closest_field(wrong: &str, fields: &[String]) -> Option<String> {
    fields.iter().find(|f| norm(f) == norm(wrong)).cloned()
}

/// A missing name read as NAME + separator (`.` `/` `:`) + FIELD of a secret that exists: the placeholder to use instead.
pub fn did_you_mean(name: &str, fields_of: &mut dyn FnMut(&str) -> Option<Vec<String>>) -> Option<String> {
    for (i, c) in name.char_indices() {
        if !".:/".contains(c) {
            continue;
        }
        let cand = name[..i].to_lowercase();
        if cand.is_empty() {
            continue;
        }
        let Some(fields) = fields_of(&cand) else { continue };
        let Some(m) = closest_field(&name[i + c.len_utf8()..], &fields) else { continue };
        return placeholders(&cand, &fields).into_iter().find(|(f, _)| *f == m).map(|(_, p)| p);
    }
    None
}

/// One placeholder reference.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Ref {
    pub name: String,
    pub field: String,
}

impl Ref {
    pub fn label(&self) -> String {
        if self.field == VALUE_FIELD { self.name.clone() } else { format!("{}#{}", self.name, self.field) }
    }
}

/// What a render did: names only. `text` is the output and must never be logged.
pub struct Rendered {
    pub text: String,
    pub filled: Vec<String>,
    pub missing: Vec<String>,
}

impl std::fmt::Debug for Rendered {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "Rendered(filled={:?}, missing={:?})", self.filled, self.missing)
    }
}

/// The references in `text`, escapes left out, each label once.
pub fn refs(text: &str) -> Vec<Ref> {
    let mut seen = std::collections::HashSet::new();
    re().captures_iter(text)
        .filter(|c| c[1].is_empty())
        .map(|c| Ref { name: c[2].to_string(), field: c.get(3).map_or(VALUE_FIELD.to_string(), |m| m.as_str().to_string()) })
        .filter(|r| seen.insert(r.label()))
        .collect()
}

/// Substitutes every reference `lookup` knows; an unknown one is "missing" and its placeholder stays.
pub fn render(text: &str, lookup: &mut dyn FnMut(&str, &str) -> Option<String>) -> Rendered {
    let mut filled: Vec<String> = Vec::new();
    let mut missing: Vec<String> = Vec::new();
    let mut out = String::with_capacity(text.len());
    let mut last = 0;
    for c in re().captures_iter(text) {
        let m = c.get(0).expect("whole match");
        out.push_str(&text[last..m.start()]);
        last = m.end();
        if !c[1].is_empty() {
            out.push_str(m.as_str());
            continue;
        }
        let r = Ref { name: c[2].to_string(), field: c.get(3).map_or(VALUE_FIELD.to_string(), |m| m.as_str().to_string()) };
        let label = r.label();
        match lookup(&r.name, &r.field) {
            Some(v) => {
                if !filled.contains(&label) {
                    filled.push(label);
                }
                out.push_str(&v);
            }
            None => {
                if !missing.contains(&label) {
                    missing.push(label);
                }
                out.push_str(m.as_str());
            }
        }
    }
    out.push_str(&text[last..]);
    Rendered { text: out, filled, missing }
}

/// Case-insensitive search of secret names and field names. `names` is name -> scope. One line per hit: scope, name,
/// field, exact placeholder. Never a value. No hit: the closest names.
pub fn find(q: &str, names: &BTreeMap<String, String>, fields_of: &mut dyn FnMut(&str) -> Option<Vec<String>>) -> String {
    let needle = q.trim().to_lowercase();
    if needle.is_empty() {
        return "give some text to find".into();
    }
    let mut hits = Vec::new();
    for (n, scope) in names {
        let fields = fields_of(n);
        let ph = match &fields {
            None => vec![(VALUE_FIELD.to_string(), placeholder(n))],
            Some(f) => placeholders(n, f),
        };
        let multi = fields.as_ref().is_some_and(|f| f.len() > 1);
        let name_hit = n.to_lowercase().contains(&needle);
        for (f, p) in ph {
            if name_hit || (multi && f.to_lowercase().contains(&needle)) {
                hits.push(format!("{scope}  {n}{}  {p}", if multi { format!("  field {f}") } else { String::new() }));
            }
        }
    }
    if !hits.is_empty() {
        return hits.join("\n");
    }
    let nq = norm(&needle);
    let head: String = nq.chars().take(3).collect();
    let mut close: Vec<&String> = names.keys().filter(|n| (!head.is_empty() && norm(n).contains(&head)) || norm(n).chars().take(3).collect::<String>() == head).take(5).collect();
    if close.is_empty() {
        close = names.keys().take(5).collect();
    }
    let mut s = format!("no secret or field matches '{q}'");
    if !close.is_empty() {
        s.push_str("; closest names: ");
        s.push_str(&close.iter().map(|s| s.as_str()).collect::<Vec<_>>().join(", "));
    }
    s
}

pub type BuiltinFn = Arc<dyn Fn(&str) -> bool + Send + Sync>;

/// The secrets a run, a render or an agent tool may use: the user's, never a built-in (`is_builtin`) nor `harness.*`.
#[derive(Clone)]
pub struct RunSecrets {
    keystore: Arc<Keystore>,
    is_builtin: BuiltinFn,
}

impl RunSecrets {
    pub fn new(keystore: Arc<Keystore>, is_builtin: BuiltinFn) -> Self {
        RunSecrets { keystore, is_builtin }
    }

    pub fn is_run_secret(&self, name: &str) -> bool {
        !(self.is_builtin)(name) && !name.starts_with("harness.")
    }

    /// name -> scope (`app`, overridden by `project` where the project has its own).
    pub fn names(&self, project: Option<&str>) -> BTreeMap<String, String> {
        let mut m = BTreeMap::new();
        for n in self.keystore.names(None).unwrap_or_default() {
            if self.is_run_secret(&n) {
                m.insert(n, SCOPE_APP.to_string());
            }
        }
        if let Some(p) = project.filter(|p| !p.trim().is_empty()) {
            for n in self.keystore.names(Some(p)).unwrap_or_default() {
                if self.is_run_secret(&n) {
                    m.insert(n, SCOPE_PROJECT.to_string());
                }
            }
        }
        m
    }

    /// The fields of `name` for a run of `project` (project first, then app). A built-in is refused with its reason.
    pub fn fields(&self, name: &str, project: Option<&str>) -> Result<Option<BTreeMap<String, String>>, VaultError> {
        if !self.is_run_secret(name) {
            return Err(VaultError::invalid(format!("secret '{name}' is built in (a harness key or the update token) and never goes to a run or a render")));
        }
        self.keystore.resolve(name, project)
    }

    pub fn field_names(&self, name: &str, project: Option<&str>) -> Option<Vec<String>> {
        self.fields(name, project).ok().flatten().map(|f| f.into_keys().collect())
    }
}

/// `run_secrets` / `infantry_run_secrets`: names, scopes and exact placeholders. `scope` is project | app | all.
pub fn run_secrets(src: &RunSecrets, project: Option<&str>, scope: Option<&str>) -> Result<String, VaultError> {
    let scope = scope.map(str::to_lowercase).unwrap_or_else(|| "all".into());
    if !["project", "app", "all"].contains(&scope.as_str()) {
        return Err(VaultError::invalid("scope is project, app or all"));
    }
    let rows: BTreeMap<String, String> = src.names(project).into_iter().filter(|(_, s)| scope == "all" || *s == scope).collect();
    if rows.is_empty() {
        let mut s = "no secrets".to_string();
        if let Some(p) = project {
            s.push_str(&format!(" for project '{p}'"));
        }
        if scope != "all" {
            s.push_str(&format!(" in scope {scope}"));
        }
        return Ok(s);
    }
    Ok(rows
        .iter()
        .map(|(n, s)| match src.field_names(n, project) {
            None => format!("{n}  ({s})"),
            Some(f) if f.len() <= 1 => format!("{n}  ({s})  {}", placeholder(n)),
            Some(f) => format!("{n}  ({s})  fields: {}", placeholders(n, &f).iter().map(|(a, b)| format!("{a} -> {b}")).collect::<Vec<_>>().join(", ")),
        })
        .collect::<Vec<_>>()
        .join("\n"))
}

/// `secret_find` / `infantry_secret_find`.
pub fn secret_find(src: &RunSecrets, q: &str, project: Option<&str>, scope: Option<&str>) -> Result<String, VaultError> {
    let scope = scope.map(str::to_lowercase).unwrap_or_else(|| "all".into());
    if !["project", "app", "all"].contains(&scope.as_str()) {
        return Err(VaultError::invalid("scope is project, app or all"));
    }
    let rows: BTreeMap<String, String> = src.names(project).into_iter().filter(|(_, s)| scope == "all" || *s == scope).collect();
    Ok(find(q, &rows, &mut |n| src.field_names(n, project)))
}

/// The outcome of a file render: names and the written path only.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RenderResult {
    pub filled: Vec<String>,
    pub missing: Vec<String>,
    pub out: Option<PathBuf>,
    pub error: Option<String>,
    pub reasons: String,
}

impl RenderResult {
    fn no(why: impl Into<String>, missing: Vec<String>) -> Self {
        RenderResult { filled: vec![], missing, out: None, error: Some(why.into()), reasons: String::new() }
    }

    pub fn ok(&self) -> bool {
        self.error.is_none()
    }

    /// Lines safe for any log, CLI line or tool result: names and a path, never a value.
    pub fn summary(&self, check: bool) -> String {
        let mut s = String::new();
        if let Some(e) = &self.error {
            s.push_str(&format!("render refused: {e}"));
            if !self.missing.is_empty() {
                s.push_str(&format!("\nmissing: {}", self.missing.join(", ")));
            }
            return s;
        }
        if !self.filled.is_empty() {
            s.push_str(&format!("{}: {}\n", if check { "found" } else { "filled" }, self.filled.join(", ")));
        }
        if !self.missing.is_empty() {
            s.push_str(&format!("missing: {}{}\n", self.missing.join(", "), if self.reasons.is_empty() { String::new() } else { format!(" ({})", self.reasons) }));
        }
        if self.filled.is_empty() && self.missing.is_empty() {
            s.push_str("no secret placeholders found\n");
        }
        match &self.out {
            Some(p) => s.push_str(&format!("wrote: {}", p.display())),
            None => s.push_str(if check { "checked only, nothing written" } else { "nothing written" }),
        }
        s.trim_end().to_string()
    }
}

pub type RestrictedFn = Arc<dyn Fn() -> bool + Send + Sync>;

/// Renders a text file's placeholders from the run secrets into an owner-only file.
#[derive(Clone)]
pub struct SecretRenderer {
    src: RunSecrets,
    restricted: RestrictedFn,
    limits: Limits,
}

/// UTF-8 text without NUL bytes, or None.
pub fn decode_text(bytes: &[u8]) -> Option<String> {
    if bytes.contains(&0) {
        return None;
    }
    String::from_utf8(bytes.to_vec()).ok()
}

impl SecretRenderer {
    pub fn new(src: RunSecrets, restricted: RestrictedFn, limits: Limits) -> Self {
        SecretRenderer { src, restricted, limits }
    }

    pub fn render(&self, template: &Path, out: Option<&Path>, project: Option<&str>, allow_missing: bool, check: bool, path_hint: &str) -> RenderResult {
        if (self.restricted)() {
            return RenderResult::no("secrets are locked under a restricted policy", vec![]);
        }
        let abs = std::path::absolute(template).unwrap_or_else(|_| template.to_path_buf());
        let fname = template.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
        if !template.is_file() {
            return RenderResult::no(format!("template '{}' is not a file{path_hint}", abs.display()), vec![]);
        }
        let max = self.limits.get(limits::RENDER_MAX_BYTES);
        if std::fs::metadata(template).map(|m| m.len() as i64).unwrap_or(0) > max {
            return RenderResult::no(format!("template '{fname}' is over limits.secrets.renderMaxBytes ({max} bytes)"), vec![]);
        }
        let Some(text) = std::fs::read(template).ok().and_then(|b| decode_text(&b)) else {
            return RenderResult::no(format!("'{fname}' is not a text file (binary or not UTF-8); only text files can be rendered"), vec![]);
        };
        let mut cache: HashMap<String, Option<BTreeMap<String, String>>> = HashMap::new();
        let mut refused: HashMap<String, String> = HashMap::new();
        let r = {
            let src = &self.src;
            let mut lookup = |name: &str, field: &str| -> Option<String> {
                let fields = cache.entry(name.to_string()).or_insert_with(|| match src.fields(name, project) {
                    Ok(f) => f,
                    Err(e) => {
                        refused.insert(name.to_string(), e.to_string());
                        None
                    }
                });
                let f = fields.as_ref()?;
                // A ONE-field secret is that field under `{{secret:NAME}}`, whatever the field is called.
                f.get(field).cloned().or_else(|| (field == VALUE_FIELD && f.len() == 1).then(|| f.values().next().cloned()).flatten())
            };
            render(&text, &mut lookup)
        };
        let why = r
            .missing
            .iter()
            .map(|label| {
                let name = label.split('#').next().unwrap_or(label).to_string();
                match cache.get(&name).cloned().flatten() {
                    None if refused.contains_key(&name) => format!("{name}: {}", refused[&name]),
                    None => {
                        let place = if project.is_none_or(|p| p.trim().is_empty()) { "app scope" } else { "project or app scope" };
                        match did_you_mean(&name, &mut |n| self.src.field_names(n, project)) {
                            Some(h) => format!("{label} not found. Did you mean {h}? (fields use NAME#FIELD)"),
                            None => format!("{name}: not found in {place}"),
                        }
                    }
                    Some(fields) => {
                        let keys: Vec<String> = fields.keys().cloned().collect();
                        if let Some((_, wrong)) = label.split_once('#') {
                            let near = closest_field(wrong, &keys).map(|f| format!(" Did you mean {{{{secret:{name}#{f}}}}}?")).unwrap_or_default();
                            format!("{label}: secret '{name}' has no field '{wrong}' (fields: {}).{near}", keys.join(", "))
                        } else {
                            format!("{label}: secret '{name}' has {} fields ({}); use {{{{secret:{name}#FIELD}}}}", keys.len(), keys.join(", "))
                        }
                    }
                }
            })
            .collect::<Vec<_>>()
            .join("; ");
        if check {
            return RenderResult { filled: r.filled, missing: r.missing, out: None, error: None, reasons: why };
        }
        if !r.missing.is_empty() && !allow_missing {
            return RenderResult::no(format!("missing secrets, nothing written ({why})"), r.missing);
        }
        let Some(target) = out else { return RenderResult::no("an output file is required", vec![]) };
        if same_file(template, target) {
            return RenderResult::no("the output may not be the template itself", vec![]);
        }
        let tname = target.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
        match crate::fsutil::write_private(target, r.text.as_bytes()) {
            Ok(()) => RenderResult { filled: r.filled, missing: r.missing, out: Some(target.to_path_buf()), error: None, reasons: String::new() },
            Err(e) => RenderResult::no(format!("could not write '{tname}' ({:?})", e.kind()), vec![]),
        }
    }

    /// [`SecretRenderer::render`] with paths as a caller wrote them: a relative one is inside `root` (the project folder).
    pub fn render_in(&self, root: Option<&Path>, template: &str, out: Option<&str>, project: Option<&str>, allow_missing: bool, check: bool) -> RenderResult {
        let file = |p: &str| -> PathBuf {
            let pb = PathBuf::from(p);
            match root {
                Some(r) if !pb.is_absolute() => r.join(pb),
                _ => pb,
            }
        };
        let hint = if Path::new(template).is_absolute() {
            String::new()
        } else if let Some(r) = root {
            format!(" (a relative path is read inside the project folder {})", r.display())
        } else {
            " (a relative path is read inside the project folder, and no project folder applies here: pass project, or an absolute path)".to_string()
        };
        let out = out.map(file);
        self.render(&file(template), out.as_deref(), project, allow_missing, check, &hint)
    }
}

fn same_file(a: &Path, b: &Path) -> bool {
    match (std::fs::canonicalize(a), std::fs::canonicalize(b)) {
        (Ok(x), Ok(y)) => x == y,
        _ => std::path::absolute(a).ok() == std::path::absolute(b).ok(),
    }
}

/// Re-export for callers that check names before a lookup.
pub fn is_secret_name(name: &str) -> bool {
    keystore::is_name(name)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn render_fills_escapes_and_reports_missing() {
        let mut look = |n: &str, f: &str| (n == "db" && f == "PASS").then(|| "fake-pass".to_string());
        let r = render("a={{secret:db#PASS}} b={{!secret:db}} c={{secret:nope}} d={{secret:db#PASS}}", &mut look);
        assert_eq!(r.text, "a=fake-pass b={{!secret:db}} c={{secret:nope}} d=fake-pass");
        assert_eq!((r.filled, r.missing), (vec!["db#PASS".to_string()], vec!["nope".to_string()]));
        assert_eq!(refs("{{secret:x}} {{!secret:y}} {{secret:x}}").len(), 1);
    }

    #[test]
    fn find_and_did_you_mean_name_placeholders_only() {
        let names: BTreeMap<String, String> = [("stripe".to_string(), "app".to_string())].into();
        let mut fields = |n: &str| (n == "stripe").then(|| vec!["SECRET_KEY".to_string(), "PUB_KEY".to_string()]);
        let out = find("secret", &names, &mut fields);
        assert_eq!(out, "app  stripe  field SECRET_KEY  {{secret:stripe#SECRET_KEY}}");
        assert_eq!(did_you_mean("stripe.secret-key", &mut fields).as_deref(), Some("{{secret:stripe#SECRET_KEY}}"));
        assert!(find("zzz", &names, &mut fields).starts_with("no secret or field matches 'zzz'"));
    }

    #[test]
    fn file_render_writes_owner_only_and_refuses_builtins() {
        let d = std::env::temp_dir().join(format!("infantry-vault-tpl-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        let ks = Arc::new(Keystore::with(d.join("state"), Arc::new(|_| None), None, Limits::default()));
        ks.set_field("db", "PASS", "fake-pass-1", None).unwrap();
        ks.set("update-token", "fake-token").unwrap();
        let src = RunSecrets::new(ks, Arc::new(|n| n == "update-token"));
        std::fs::create_dir_all(&d).unwrap();
        std::fs::write(d.join("t.env"), "P={{secret:db}}\nT={{secret:update-token}}\n").unwrap();
        let r = SecretRenderer::new(src.clone(), Arc::new(|| false), Limits::default());
        let res = r.render_in(Some(&d), "t.env", Some("o.env"), None, false, false);
        assert!(!res.ok() && res.summary(false).contains("update-token: secret 'update-token' is built in"));
        std::fs::write(d.join("t.env"), "P={{secret:db}}\n").unwrap();
        let res = r.render_in(Some(&d), "t.env", Some("o.env"), None, false, false);
        assert!(res.ok(), "{}", res.summary(false));
        assert_eq!(std::fs::read_to_string(d.join("o.env")).unwrap(), "P=fake-pass-1\n");
        assert!(!res.summary(false).contains("fake-pass-1"));
        assert_eq!(run_secrets(&src, None, None).unwrap(), "db  (app)  {{secret:db}}");
        let _ = std::fs::remove_dir_all(&d);
    }
}
