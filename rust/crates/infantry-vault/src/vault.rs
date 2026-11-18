// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! VAULT-REVEAL-1: the owner's window reads a stored value back (reveal, copy) or exports / imports the vault, only after
//! the person at this computer passes the OS user check ([`crate::verify`]). The frames are owner-only, refused under a
//! restricted policy, and answered directly with the request's own type (never pushed). No agent path reaches this:
//! agents keep `{{secret:...}}` rendering only.
//!
//! Every reveal, copy, export, import and change is appended to `vault-audit.jsonl` (time, action, secret, field, scope,
//! method, outcome). Never a value or a passphrase.
use std::collections::BTreeMap;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use serde::{Deserialize, Serialize};
use serde_json::{json, Map, Value};

use crate::backup;
use crate::keystore::{self, Keystore, VALUE_FIELD};
use crate::limits::{self, Limits};
use crate::verify::{OsUserVerifier, Outcome, Verification};
use crate::VaultError;

pub const AUDIT_FILE_NAME: &str = "vault-audit.jsonl";
pub const PURPOSE_REVEAL: &str = "reveal";
pub const PURPOSE_COPY: &str = "copy";
pub const KEEP: &str = "keep";
pub const REPLACE: &str = "replace";

fn value_field() -> String {
    VALUE_FIELD.into()
}
fn reveal_purpose() -> String {
    PURPOSE_REVEAL.into()
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct RevealRequest {
    pub name: String,
    #[serde(default = "value_field")]
    pub field: String,
    #[serde(default)]
    pub project: Option<String>,
    #[serde(default = "reveal_purpose")]
    pub purpose: String,
}

/// The direct answer to a reveal. Its `Debug` names no value.
#[derive(Serialize, Deserialize, Clone, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct Revealed {
    pub name: String,
    pub field: String,
    pub project: Option<String>,
    pub value: String,
    pub method: String,
    pub show_seconds: i64,
    pub clear_seconds: i64,
}

impl std::fmt::Debug for Revealed {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "Revealed(name={}, field={}, project={:?}, method={})", self.name, self.field, self.project, self.method)
    }
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ExportRequest {
    pub path: String,
    pub passphrase: String,
}

#[derive(Serialize, Deserialize, Debug, Clone, PartialEq, Eq)]
pub struct Exported {
    pub path: String,
    pub secrets: usize,
    pub method: String,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ImportRequest {
    pub path: String,
    pub passphrase: String,
    #[serde(default)]
    pub resolutions: BTreeMap<String, String>,
}

#[derive(Serialize, Deserialize, Debug, Clone, Default, PartialEq, Eq)]
pub struct Imported {
    pub applied: bool,
    pub added: Vec<String>,
    pub replaced: Vec<String>,
    pub kept: Vec<String>,
    pub same: Vec<String>,
    pub conflicts: Vec<String>,
}

pub type ClockFn = Arc<dyn Fn() -> i64 + Send + Sync>;

pub struct SecretVault {
    keystore: Arc<Keystore>,
    verifier: Box<dyn OsUserVerifier>,
    audit_file: PathBuf,
    limits: Limits,
    clock: ClockFn,
    home: String,
    last_verified: Mutex<Option<(i64, String)>>,
    audit_lock: Mutex<()>,
}

fn now_ms() -> i64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0)
}

impl SecretVault {
    /// The vault of `keystore` with the platform verifier, auditing to `<stateDir>/vault-audit.jsonl`.
    pub fn new(keystore: Arc<Keystore>, limits: Limits) -> Self {
        let audit = keystore.dir().join(AUDIT_FILE_NAME);
        Self::with(keystore, crate::verify::detect(), audit, limits, Arc::new(now_ms))
    }

    pub fn with(keystore: Arc<Keystore>, verifier: Box<dyn OsUserVerifier>, audit_file: PathBuf, limits: Limits, clock: ClockFn) -> Self {
        let home = std::env::var("USERPROFILE").or_else(|_| std::env::var("HOME")).unwrap_or_default();
        SecretVault { keystore, verifier, audit_file, limits, clock, home, last_verified: Mutex::new(None), audit_lock: Mutex::new(()) }
    }

    pub fn verifier_label(&self) -> &'static str {
        self.verifier.label()
    }

    /// What a reveal would use now, without a prompt (the panel shows "Windows Hello" or why not).
    pub fn check(&self) -> Verification {
        self.verifier.check()
    }

    pub fn reveal(&self, req: &RevealRequest) -> Result<Revealed, VaultError> {
        if req.purpose != PURPOSE_REVEAL && req.purpose != PURPOSE_COPY {
            return Err(VaultError::invalid("purpose must be reveal or copy"));
        }
        keystore::check_name(&req.name)?;
        keystore::check_field(&req.field)?;
        let project = keystore::scope(req.project.as_deref())?;
        // Ask the OS only for a value that exists: the check leaks nothing a `secret_state` does not already show.
        if !self.keystore.fields(&req.name, project.as_deref())?.is_some_and(|f| f.contains_key(&req.field)) {
            return Err(VaultError::invalid("no such secret field"));
        }
        let shown = project.as_ref().map_or(req.name.clone(), |p| format!("{p}/{}", req.name));
        let check = self.verified(&format!("Infantry AI: {} the secret {shown}#{}", req.purpose, req.field));
        self.audit_check(&req.purpose, &req.name, &req.field, project.as_deref(), &check, "");
        if !check.is_verified() {
            return Err(VaultError::invalid(self.refusal(&check)));
        }
        let value = self.keystore.field(&req.name, &req.field, project.as_deref())?.ok_or_else(|| VaultError::invalid("no such secret field"))?;
        Ok(Revealed {
            name: req.name.clone(),
            field: req.field.clone(),
            project,
            value,
            method: check.method,
            show_seconds: self.limits.get(limits::REVEAL_SECONDS),
            clear_seconds: self.limits.get(limits::CLIPBOARD_CLEAR_SECONDS),
        })
    }

    pub fn export(&self, req: &ExportRequest) -> Result<Exported, VaultError> {
        let min = self.limits.get(limits::BACKUP_MIN_PASSPHRASE);
        if (req.passphrase.chars().count() as i64) < min {
            return Err(VaultError::invalid(format!("the passphrase needs at least {min} characters (limits.secrets.backupMinPassphrase)")));
        }
        let target = self.path(&req.path)?;
        if target.exists() {
            return Err(VaultError::invalid(format!("{} already exists; choose a new file name", target.display())));
        }
        let check = self.verified("Infantry AI: export an encrypted backup of every secret");
        self.audit_check("export", "*", "", None, &check, &target.display().to_string());
        if !check.is_verified() {
            return Err(VaultError::invalid(self.refusal(&check)));
        }
        let entries: Vec<backup::Entry> = self.keystore.snapshot()?.into_iter().map(|s| backup::Entry { name: s.name, project: s.project, fields: s.fields }).collect();
        let n = entries.len();
        let text = backup::seal(entries, &req.passphrase, self.limits.get(limits::BACKUP_KDF_ITERATIONS), (self.clock)())?;
        crate::fsutil::write_private(&target, text.as_bytes()).map_err(|e| VaultError::failed(format!("could not write the backup ({:?})", e.kind())))?;
        Ok(Exported { path: target.display().to_string(), secrets: n, method: check.method })
    }

    /// Merges a backup. While a conflict is unanswered nothing is written and the answer lists the conflicts.
    pub fn import(&self, req: &ImportRequest) -> Result<Imported, VaultError> {
        let source = self.path(&req.path)?;
        if !source.is_file() {
            return Err(VaultError::invalid(format!("{} is not a file", source.display())));
        }
        let max = self.limits.get(limits::BACKUP_MAX_BYTES);
        if std::fs::metadata(&source).map(|m| m.len() as i64).unwrap_or(0) > max {
            return Err(VaultError::invalid(format!("{} is bigger than {max} bytes (limits.secrets.backupMaxBytes)", source.display())));
        }
        if req.resolutions.values().any(|v| v != KEEP && v != REPLACE) {
            return Err(VaultError::invalid("a conflict is answered keep or replace"));
        }
        let text = std::fs::read_to_string(&source).map_err(|_| VaultError::invalid("this is not an Infantry AI vault backup"))?;
        let kdf = limits::BACKUP_KDF_ITERATIONS;
        let payload = backup::open(&text, &req.passphrase, kdf.min, kdf.max)?;
        let mut r = Imported::default();
        let mut writes = Vec::new();
        for e in &payload.entries {
            keystore::check_name(&e.name)?;
            let project = keystore::scope(e.project.as_deref())?;
            for f in e.fields.keys() {
                keystore::check_field(f)?;
            }
            let id = e.id();
            let trimmed: BTreeMap<String, String> = e.fields.iter().map(|(k, v)| (k.clone(), v.trim().to_string())).collect();
            match self.keystore.fields(&e.name, project.as_deref())? {
                None => {
                    r.added.push(id);
                    writes.push((e, project));
                }
                Some(here) if here == trimmed => r.same.push(id),
                Some(_) => match req.resolutions.get(&id).map(String::as_str) {
                    Some(REPLACE) => {
                        r.replaced.push(id);
                        writes.push((e, project));
                    }
                    Some(KEEP) => r.kept.push(id),
                    _ => r.conflicts.push(id),
                },
            }
        }
        if !r.conflicts.is_empty() {
            return Ok(Imported { applied: false, conflicts: r.conflicts, ..Default::default() });
        }
        for (e, project) in &writes {
            self.keystore.replace_secret(&e.name, project.as_deref(), &e.fields)?;
        }
        let detail = format!("{} (added {}, replaced {}, kept {})", source.display(), r.added.len(), r.replaced.len(), r.kept.len());
        self.append_audit("import", "*", "", None, "", "ok", &detail);
        r.applied = true;
        Ok(r)
    }

    /// The last `limit` audit lines, newest last. Each is the JSON object as written (names and outcomes only).
    pub fn audit_tail(&self, limit: usize) -> Vec<Value> {
        let Ok(text) = std::fs::read_to_string(&self.audit_file) else { return Vec::new() };
        let lines: Vec<&str> = text.lines().filter(|l| !l.trim().is_empty()).collect();
        let start = lines.len().saturating_sub(limit);
        lines[start..].iter().filter_map(|l| serde_json::from_str(l).ok()).collect()
    }

    /// One OS verification, or the one before it while `limits.secrets.verifyCacheSeconds` still covers it. One at a time.
    fn verified(&self, reason: &str) -> Verification {
        let mut last = self.last_verified.lock().unwrap_or_else(|p| p.into_inner());
        let cache_ms = self.limits.get(limits::VERIFY_CACHE_SECONDS) * 1_000;
        let now = (self.clock)();
        if let Some((at, method)) = last.as_ref() {
            if cache_ms > 0 && now - at >= 0 && now - at < cache_ms {
                return Verification::verified(&format!("{method} (cached)"));
            }
        }
        let timeout = Duration::from_secs(self.limits.get(limits::VERIFY_TIMEOUT_SECONDS) as u64);
        let r = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| self.verifier.verify(reason, timeout)))
            .unwrap_or_else(|_| Verification::unavailable("the verification prompt failed"));
        *last = r.is_verified().then(|| ((self.clock)(), r.method.clone()));
        r
    }

    fn refusal(&self, check: &Verification) -> String {
        match check.outcome {
            Outcome::Denied => format!("{} verification was not passed ({})", self.verifier.label(), if check.detail.is_empty() { "denied" } else { &check.detail }),
            _ if check.detail.is_empty() => format!("{} is not available here", self.verifier.label()),
            _ => check.detail.clone(),
        }
    }

    /// A path on the daemon's machine: `~` is the daemon user's home; it must then be absolute.
    fn path(&self, raw: &str) -> Result<PathBuf, VaultError> {
        let t = raw.trim();
        if t.is_empty() {
            return Err(VaultError::invalid("give the backup file's path"));
        }
        let expanded = if t == "~" || t.starts_with("~/") || t.starts_with("~\\") { format!("{}{}", self.home, &t[1..]) } else { t.to_string() };
        let p = PathBuf::from(expanded);
        if !p.is_absolute() {
            return Err(VaultError::invalid("give a full path (or one starting with ~)"));
        }
        Ok(p)
    }

    fn audit_check(&self, action: &str, name: &str, field: &str, project: Option<&str>, check: &Verification, detail: &str) {
        let d = if detail.is_empty() { check.detail.as_str() } else { detail };
        self.append_audit(action, name, field, project, &check.method, check.outcome.wire(), d);
    }

    /// One JSON line per action. Its arguments are names, scopes and outcomes; never a value.
    pub fn append_audit(&self, action: &str, name: &str, field: &str, project: Option<&str>, method: &str, outcome: &str, detail: &str) {
        let _g = self.audit_lock.lock().unwrap_or_else(|p| p.into_inner());
        let at = chrono::DateTime::from_timestamp_millis((self.clock)()).map(|t| t.to_rfc3339_opts(chrono::SecondsFormat::Millis, true)).unwrap_or_default();
        let mut o = Map::new();
        o.insert("at".into(), json!(at));
        o.insert("action".into(), json!(action));
        o.insert("secret".into(), json!(name));
        if !field.is_empty() {
            o.insert("field".into(), json!(field));
        }
        if let Some(p) = project {
            o.insert("project".into(), json!(p));
        }
        o.insert("method".into(), json!(method));
        o.insert("outcome".into(), json!(outcome));
        if !detail.is_empty() {
            o.insert("detail".into(), json!(detail));
        }
        let line = Value::Object(o).to_string();
        if let Some(dir) = self.audit_file.parent() {
            let _ = std::fs::create_dir_all(dir);
        }
        if let Ok(mut f) = std::fs::OpenOptions::new().create(true).append(true).open(&self.audit_file) {
            let _ = writeln!(f, "{line}");
        }
    }

    pub fn audit_path(&self) -> &Path {
        &self.audit_file
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::verify::Fixed;

    fn setup(tag: &str, v: Verification) -> (PathBuf, Arc<Keystore>, SecretVault) {
        let d = std::env::temp_dir().join(format!("infantry-vault-v-{tag}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        let ks = Arc::new(Keystore::with(&d, Arc::new(|_| None), None, Limits::default()));
        let vault = SecretVault::with(ks.clone(), Box::new(Fixed(v)), d.join(AUDIT_FILE_NAME), Limits::default(), Arc::new(|| 1_800_000_000_000));
        (d, ks, vault)
    }

    #[test]
    fn reveal_needs_the_check_and_audits_without_values() {
        let (d, ks, vault) = setup("rev", Verification::unavailable("Windows Hello is not set up for this Windows user"));
        ks.set_field("db", "PASS", "fake-pass-xyz", None).unwrap();
        let req = RevealRequest { name: "db".into(), field: "PASS".into(), project: None, purpose: PURPOSE_COPY.into() };
        assert_eq!(vault.reveal(&req).unwrap_err().to_string(), "Windows Hello is not set up for this Windows user");
        let (_, _, ok) = setup("rev2", Verification::verified("windows-hello"));
        let ks2 = &ok.keystore;
        ks2.set_field("db", "PASS", "fake-pass-xyz", None).unwrap();
        let r = ok.reveal(&req).unwrap();
        assert_eq!((r.value.as_str(), r.show_seconds, r.method.as_str()), ("fake-pass-xyz", 30, "windows-hello"));
        let audit = std::fs::read_to_string(d.join(AUDIT_FILE_NAME)).unwrap();
        assert!(audit.contains("\"outcome\":\"unavailable\"") && !audit.contains("fake-pass"));
        let _ = std::fs::remove_dir_all(&d);
        let _ = std::fs::remove_dir_all(ok.keystore.dir());
    }

    #[test]
    fn export_then_import_reports_conflicts_until_answered() {
        let (d, ks, vault) = setup("bk", Verification::verified("windows-hello"));
        ks.set_field("db", "PASS", "fake-1", None).unwrap();
        ks.set_field("api", "KEY", "fake-2", Some("p1")).unwrap();
        let path = d.join("backup.json").display().to_string();
        assert!(vault.export(&ExportRequest { path: path.clone(), passphrase: "short".into() }).is_err());
        let e = vault.export(&ExportRequest { path: path.clone(), passphrase: "a fake passphrase".into() }).unwrap();
        assert_eq!(e.secrets, 2);
        ks.set_field("db", "PASS", "fake-changed", None).unwrap();
        ks.clear("api", Some("p1")).unwrap();
        let mut req = ImportRequest { path: path.clone(), passphrase: "a fake passphrase".into(), resolutions: BTreeMap::new() };
        let r = vault.import(&req).unwrap();
        assert_eq!((r.applied, r.conflicts.clone()), (false, vec!["db".to_string()]));
        assert_eq!(ks.fields("api", Some("p1")).unwrap(), None, "nothing written while a conflict is open");
        req.resolutions.insert("db".into(), REPLACE.into());
        let r = vault.import(&req).unwrap();
        assert!(r.applied && r.added == vec!["p1/api".to_string()] && r.replaced == vec!["db".to_string()]);
        assert_eq!(ks.field("db", "PASS", None).unwrap().as_deref(), Some("fake-1"));
        assert_eq!(vault.audit_tail(10).len(), 2, "export and the applied import");
        let _ = std::fs::remove_dir_all(&d);
    }
}
