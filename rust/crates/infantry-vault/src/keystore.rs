// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The daemon-owned keystore: AES-256-GCM sealed values in `<stateDir>/keystore.json`, the same file the Kotlin daemon
//! wrote, so a migrated copy opens.
//!
//! - v1 (SECRETS-1): `entries` holds one sealed value per name, AAD = the name.
//! - v2 (SECRETS-2): `secrets` holds named fields per secret, AAD = `name NUL field`. A v1 entry reads as one field named
//!   [`VALUE_FIELD`] and is re-sealed as v2 on the next write; one that does not open (another key) stays untouched.
//! - SECRETS-2b: a project's own secret is stored under `@<project>/<name>`.
//!
//! The master key: `INFANTRY_KEYSTORE_KEY` / `INFANTRY_KEYSTORE_KEY_FILE` (SHA-256 of the trimmed text), else the OS vault
//! (Windows DPAPI blob), else `<stateDir>/keystore.key` (base64, owner-only). Never silently plaintext.
//!
//! Nothing here puts a value in an error, a log line or a `Debug` dump.
use std::collections::{BTreeMap, BTreeSet};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::time::SystemTime;

use base64::engine::general_purpose::STANDARD as B64;
use base64::Engine;
use ring::aead::{Aad, LessSafeKey, Nonce, UnboundKey, AES_256_GCM};
use ring::rand::{SecureRandom, SystemRandom};
use serde::{Deserialize, Serialize};

use crate::limits::{self, Limits};
use crate::os_vault::{self, OsVault};
use crate::VaultError;

pub const FILE_NAME: &str = "keystore.json";
pub const KEY_FILE_NAME: &str = "keystore.key";
pub const KEY_ENV: &str = "INFANTRY_KEYSTORE_KEY";
pub const KEY_FILE_ENV: &str = "INFANTRY_KEYSTORE_KEY_FILE";
pub const MAX_LENGTH: usize = 4096;
/// The one field of a single-value secret, and what a v1 entry migrates to.
pub const VALUE_FIELD: &str = "value";
/// The update token (a built-in single-value secret).
pub const UPDATE_TOKEN: &str = "update-token";
pub const SCOPE_APP: &str = "app";
pub const SCOPE_PROJECT: &str = "project";
const PROJECT_MARK: &str = "@";

/// How the master key is protected; reported by `secret_state`, never the key.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Protection {
    ExternalKey,
    Os,
    KeyFile,
}

impl Protection {
    pub fn wire(self) -> &'static str {
        match self {
            Protection::ExternalKey => "external-key",
            Protection::Os => "os",
            Protection::KeyFile => "key-file",
        }
    }
}

#[derive(Clone, Serialize, Deserialize)]
struct Sealed {
    nonce: String,
    ct: String,
}

fn two() -> i32 {
    2
}

#[derive(Default, Serialize, Deserialize)]
struct StoreFile {
    #[serde(default = "two")]
    v: i32,
    #[serde(default)]
    entries: BTreeMap<String, Sealed>,
    #[serde(default)]
    secrets: BTreeMap<String, BTreeMap<String, Sealed>>,
}

/// One field of a secret as a client may see it: its name and a masked tail.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct FieldInfo {
    pub name: String,
    pub tail: String,
}

/// Set / not set and a masked tail of a single-value secret.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SecretInfo {
    pub name: String,
    pub set: bool,
    pub tail: String,
}

/// One whole secret with its values, for the encrypted backup only. Its `Debug` names no value.
#[derive(Clone, PartialEq, Eq)]
pub struct StoredSecret {
    pub name: String,
    pub project: Option<String>,
    pub fields: BTreeMap<String, String>,
}

impl std::fmt::Debug for StoredSecret {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "StoredSecret(name={}, project={:?}, fields={:?})", self.name, self.project, self.fields.keys().collect::<Vec<_>>())
    }
}

pub type EnvFn = Arc<dyn Fn(&str) -> Option<String> + Send + Sync>;

struct Inner {
    key: Option<([u8; 32], Protection)>,
    stamp: Option<(Option<SystemTime>, u64)>,
    values: Arc<Vec<String>>,
}

pub struct Keystore {
    dir: PathBuf,
    file: PathBuf,
    env: EnvFn,
    vault: Option<Box<dyn OsVault>>,
    limits: Limits,
    random: SystemRandom,
    inner: Mutex<Inner>,
}

impl std::fmt::Debug for Keystore {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "Keystore(dir={})", self.dir.display())
    }
}

// ---- name rules ----

fn chars_ok(s: &str, first: impl Fn(char) -> bool, rest: impl Fn(char) -> bool, max: usize) -> bool {
    let mut it = s.chars();
    match it.next() {
        Some(c) if first(c) => {}
        _ => return false,
    }
    let n = s.chars().count();
    n <= max && it.all(rest)
}

/// `^[a-z0-9][a-z0-9._-]{0,63}$`
pub fn is_name(name: &str) -> bool {
    let lo = |c: char| c.is_ascii_lowercase() || c.is_ascii_digit();
    chars_ok(name, lo, |c| lo(c) || matches!(c, '.' | '_' | '-'), 64)
}

/// `^[A-Za-z_][A-Za-z0-9_]{0,63}$`: environment-variable names, since a run gets each field as one.
pub fn is_field(field: &str) -> bool {
    chars_ok(field, |c| c.is_ascii_alphabetic() || c == '_', |c| c.is_ascii_alphanumeric() || c == '_', 64)
}

/// `^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$`
pub fn is_project(p: &str) -> bool {
    chars_ok(p, |c| c.is_ascii_alphanumeric(), |c| c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | '-'), 80)
}

pub fn check_name(name: &str) -> Result<(), VaultError> {
    if is_name(name) { Ok(()) } else { Err(VaultError::invalid("secret names are lowercase letters, digits, '.', '_' and '-'")) }
}

pub fn check_field(field: &str) -> Result<(), VaultError> {
    if is_field(field) { Ok(()) } else { Err(VaultError::invalid("field names are letters, digits and '_', not starting with a digit")) }
}

pub fn check_project(p: &str) -> Result<(), VaultError> {
    if is_project(p) { Ok(()) } else { Err(VaultError::invalid("invalid project id")) }
}

/// A frame's scope: None / blank = the app scope, else a valid project id.
pub fn scope(project: Option<&str>) -> Result<Option<String>, VaultError> {
    match project.map(str::trim).filter(|p| !p.is_empty()) {
        None => Ok(None),
        Some(p) => {
            check_project(p)?;
            Ok(Some(p.to_string()))
        }
    }
}

/// The last 4 characters, and only for a value long enough that 4 reveal little; shorter values show only dots.
pub fn masked_tail(value: &str) -> String {
    let n = value.chars().count();
    if n >= 16 {
        let tail: String = value.chars().skip(n - 4).collect();
        format!("••••{tail}")
    } else {
        "••••".into()
    }
}

fn store_key(name: &str, project: Option<&str>) -> Result<String, VaultError> {
    check_name(name)?;
    match project {
        None => Ok(name.to_string()),
        Some(p) => {
            check_project(p)?;
            Ok(format!("{PROJECT_MARK}{p}/{name}"))
        }
    }
}

fn aad(name: &str, field: &str) -> String {
    format!("{name}\u{0}{field}")
}

fn clean_value(value: &str) -> Result<String, VaultError> {
    let clean = value.trim();
    if clean.is_empty() {
        return Err(VaultError::invalid("the secret is empty"));
    }
    if clean.chars().count() > MAX_LENGTH {
        return Err(VaultError::invalid(format!("the secret is longer than {MAX_LENGTH} characters")));
    }
    Ok(clean.to_string())
}

fn std_env() -> EnvFn {
    Arc::new(|k| std::env::var(k).ok())
}

impl Keystore {
    /// The keystore of state dir `dir` with this platform's OS vault and the process environment.
    pub fn open(dir: impl Into<PathBuf>, limits: Limits) -> Self {
        let dir = dir.into();
        let vault = os_vault::detect(&dir);
        Self::with(dir, std_env(), vault, limits)
    }

    /// Full control over the environment and the vault (tests pass `None` for the key-file fallback).
    pub fn with(dir: impl Into<PathBuf>, env: EnvFn, vault: Option<Box<dyn OsVault>>, limits: Limits) -> Self {
        let dir = dir.into();
        Keystore {
            file: dir.join(FILE_NAME),
            dir,
            env,
            vault,
            limits,
            random: SystemRandom::new(),
            inner: Mutex::new(Inner { key: None, stamp: None, values: Arc::new(Vec::new()) }),
        }
    }

    pub fn dir(&self) -> &Path {
        &self.dir
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, Inner> {
        self.inner.lock().unwrap_or_else(|p| p.into_inner())
    }

    pub fn protection(&self) -> Result<Protection, VaultError> {
        let mut g = self.lock();
        self.ensure_key(&mut g).map(|k| k.1)
    }

    // ---- single-value secrets ----

    pub fn get(&self, name: &str) -> Result<Option<String>, VaultError> {
        self.field(name, VALUE_FIELD, None)
    }

    pub fn set(&self, name: &str, value: &str) -> Result<(), VaultError> {
        self.set_field(name, VALUE_FIELD, value, None)
    }

    /// Removes the whole secret, every field of it. False when there was none.
    pub fn clear(&self, name: &str, project: Option<&str>) -> Result<bool, VaultError> {
        let key = store_key(name, project)?;
        let mut g = self.lock();
        let mut cur = self.read_for_write()?;
        if !cur.secrets.contains_key(&key) && !cur.entries.contains_key(&key) {
            return Ok(false);
        }
        cur.secrets.remove(&key);
        cur.entries.remove(&key);
        self.write(&mut g, cur)?;
        Ok(true)
    }

    pub fn field(&self, name: &str, field: &str, project: Option<&str>) -> Result<Option<String>, VaultError> {
        let key = store_key(name, project)?;
        check_field(field)?;
        let mut g = self.lock();
        let content = self.read();
        Ok(self.opened(&mut g, &content)?.remove(&key).and_then(|mut f| f.remove(field)))
    }

    /// Every field of `name` in `project` that opens; None when there is no such secret. Daemon side only.
    pub fn fields(&self, name: &str, project: Option<&str>) -> Result<Option<BTreeMap<String, String>>, VaultError> {
        let key = store_key(name, project)?;
        let mut g = self.lock();
        let content = self.read();
        Ok(self.opened(&mut g, &content)?.remove(&key))
    }

    /// The secret a run of `project` means by `name`: the project's own first, then the app's.
    pub fn resolve(&self, name: &str, project: Option<&str>) -> Result<Option<BTreeMap<String, String>>, VaultError> {
        if let Some(p) = project.filter(|p| !p.trim().is_empty()) {
            if let Some(f) = self.fields(name, Some(p))? {
                return Ok(Some(f));
            }
        }
        self.fields(name, None)
    }

    /// Where [`Keystore::resolve`] finds `name` for `project`: [`SCOPE_PROJECT`], [`SCOPE_APP`] or None.
    pub fn scope_of(&self, name: &str, project: Option<&str>) -> Result<Option<&'static str>, VaultError> {
        let keys = self.stored_keys();
        if let Some(p) = project.filter(|p| !p.trim().is_empty()) {
            if keys.contains(&store_key(name, Some(p))?) {
                return Ok(Some(SCOPE_PROJECT));
            }
        }
        Ok(keys.contains(&store_key(name, None)?).then_some(SCOPE_APP))
    }

    pub fn set_field(&self, name: &str, field: &str, value: &str, project: Option<&str>) -> Result<(), VaultError> {
        let key = store_key(name, project)?;
        check_field(field)?;
        let clean = clean_value(value)?;
        let max_fields = self.limits.get(limits::MAX_FIELDS) as usize;
        let max_secrets = self.limits.get(limits::MAX_SECRETS) as usize;
        let mut g = self.lock();
        let mut cur = self.read_for_write()?;
        let fields = cur.secrets.get(&key);
        if !fields.is_some_and(|f| f.contains_key(field)) && fields.map_or(0, |f| f.len()) >= max_fields {
            return Err(VaultError::invalid(format!("a secret holds at most {max_fields} fields")));
        }
        if !cur.secrets.contains_key(&key) && !cur.entries.contains_key(&key) && cur.secrets.len() >= max_secrets {
            return Err(VaultError::invalid(format!("the keystore holds at most {max_secrets} secrets")));
        }
        let k = self.ensure_key(&mut g)?.0;
        let sealed = self.seal(&k, &aad(&key, field), &clean)?;
        cur.secrets.entry(key).or_default().insert(field.to_string(), sealed);
        self.write(&mut g, cur)
    }

    /// Removes one field; the secret goes with its last field. False when there was no such field.
    pub fn delete_field(&self, name: &str, field: &str, project: Option<&str>) -> Result<bool, VaultError> {
        let key = store_key(name, project)?;
        check_field(field)?;
        let mut g = self.lock();
        let mut cur = self.read_for_write()?;
        let in_v2 = cur.secrets.get(&key).is_some_and(|f| f.contains_key(field));
        if field == VALUE_FIELD && cur.entries.contains_key(&key) && !in_v2 {
            cur.entries.remove(&key);
            self.write(&mut g, cur)?;
            return Ok(true);
        }
        if !in_v2 {
            return Ok(false);
        }
        let fields = cur.secrets.get_mut(&key).expect("checked");
        fields.remove(field);
        if fields.is_empty() {
            cur.secrets.remove(&key);
        }
        self.write(&mut g, cur)?;
        Ok(true)
    }

    /// Moves secret `name` from scope `from` to scope `to` (None = app), every field re-sealed under its new place.
    pub fn move_secret(&self, name: &str, from: Option<&str>, to: Option<&str>) -> Result<(), VaultError> {
        let src = store_key(name, from)?;
        let dst = store_key(name, to)?;
        if src == dst {
            return Err(VaultError::invalid("the secret is already there"));
        }
        let mut g = self.lock();
        let mut cur = self.read_for_write()?;
        let fields = self.opened(&mut g, &cur)?.remove(&src).ok_or_else(|| VaultError::invalid("no such secret"))?;
        if cur.secrets.contains_key(&dst) || cur.entries.contains_key(&dst) {
            let place = to.map_or("the app scope".to_string(), |p| format!("project '{p}'"));
            return Err(VaultError::invalid(format!("a secret named '{name}' already exists in {place}")));
        }
        let k = self.ensure_key(&mut g)?.0;
        let mut resealed = BTreeMap::new();
        for (f, v) in &fields {
            resealed.insert(f.clone(), self.seal(&k, &aad(&dst, f), v)?);
        }
        cur.secrets.remove(&src);
        cur.entries.remove(&src);
        cur.secrets.insert(dst, resealed);
        self.write(&mut g, cur)
    }

    /// Every secret that opens, every scope's, for an encrypted backup only.
    pub fn snapshot(&self) -> Result<Vec<StoredSecret>, VaultError> {
        let mut g = self.lock();
        let content = self.read();
        Ok(self
            .opened(&mut g, &content)?
            .into_iter()
            .map(|(key, fields)| match key.strip_prefix(PROJECT_MARK) {
                Some(rest) => {
                    let (p, n) = rest.split_once('/').unwrap_or((rest, ""));
                    StoredSecret { name: n.to_string(), project: Some(p.to_string()), fields }
                }
                None => StoredSecret { name: key, project: None, fields },
            })
            .collect())
    }

    /// Secret `name` in `project` becomes exactly `fields`, in one write (an import's add or replace).
    pub fn replace_secret(&self, name: &str, project: Option<&str>, fields: &BTreeMap<String, String>) -> Result<(), VaultError> {
        let key = store_key(name, project)?;
        let max_fields = self.limits.get(limits::MAX_FIELDS) as usize;
        let max_secrets = self.limits.get(limits::MAX_SECRETS) as usize;
        if fields.is_empty() {
            return Err(VaultError::invalid("a secret holds at least one field"));
        }
        if fields.len() > max_fields {
            return Err(VaultError::invalid(format!("a secret holds at most {max_fields} fields")));
        }
        let mut g = self.lock();
        let k = self.ensure_key(&mut g)?.0;
        let mut sealed = BTreeMap::new();
        for (f, v) in fields {
            check_field(f)?;
            let clean = clean_value(v)?;
            sealed.insert(f.clone(), self.seal(&k, &aad(&key, f), &clean)?);
        }
        let mut cur = self.read_for_write()?;
        if !cur.secrets.contains_key(&key) && !cur.entries.contains_key(&key) && cur.secrets.len() >= max_secrets {
            return Err(VaultError::invalid(format!("the keystore holds at most {max_secrets} secrets")));
        }
        cur.entries.remove(&key);
        cur.secrets.insert(key, sealed);
        self.write(&mut g, cur)
    }

    /// The names of the stored secrets of `project` (None = the app scope), sorted.
    pub fn names(&self, project: Option<&str>) -> Result<Vec<String>, VaultError> {
        let prefix = match project {
            Some(p) => {
                check_project(p)?;
                Some(format!("{PROJECT_MARK}{p}/"))
            }
            None => None,
        };
        let keys = self.stored_keys();
        Ok(keys
            .into_iter()
            .filter_map(|k| match &prefix {
                None => (!k.starts_with(PROJECT_MARK)).then_some(k),
                Some(px) => k.strip_prefix(px.as_str()).map(str::to_string),
            })
            .collect())
    }

    /// Every project that holds at least one secret of its own, sorted.
    pub fn projects(&self) -> Vec<String> {
        let set: BTreeSet<String> = self
            .stored_keys()
            .into_iter()
            .filter_map(|k| k.strip_prefix(PROJECT_MARK).map(|r| r.split('/').next().unwrap_or("").to_string()))
            .collect();
        set.into_iter().collect()
    }

    /// Each field of `name` in `project` with a masked tail; empty when there is no such secret.
    pub fn describe(&self, name: &str, project: Option<&str>) -> Result<Vec<FieldInfo>, VaultError> {
        Ok(self.fields(name, project)?.unwrap_or_default().into_iter().map(|(f, v)| FieldInfo { name: f, tail: masked_tail(&v) }).collect())
    }

    /// Set / not set and a masked tail for each of `names` (single-value secrets).
    pub fn status(&self, names: &[String]) -> Result<Vec<SecretInfo>, VaultError> {
        names
            .iter()
            .map(|n| {
                let v = self.get(n)?;
                Ok(SecretInfo { name: n.clone(), set: v.is_some(), tail: v.as_deref().map(masked_tail).unwrap_or_default() })
            })
            .collect()
    }

    /// Every value the keystore holds, for the scrubber only. The file is re-read only when it changed (time, size or a
    /// write through this instance); the SAME `Arc` is returned until then so a caller can cache on identity.
    pub fn all_values(&self) -> Arc<Vec<String>> {
        let stamp = std::fs::metadata(&self.file).ok().map(|m| (m.modified().ok(), m.len())).unwrap_or((None, 0));
        let mut g = self.lock();
        if g.stamp != Some(stamp) {
            let content = self.read();
            let set: BTreeSet<String> = match self.opened(&mut g, &content) {
                Ok(o) => o.into_values().flat_map(|f| f.into_values()).collect(),
                Err(_) => BTreeSet::new(),
            };
            g.values = Arc::new(set.into_iter().collect());
            g.stamp = Some(stamp);
        }
        g.values.clone()
    }

    // ---- internals ----

    fn stored_keys(&self) -> BTreeSet<String> {
        let c = self.read();
        c.secrets.into_keys().chain(c.entries.into_keys()).collect()
    }

    /// The file as it is; a missing or damaged file reads as empty.
    fn read(&self) -> StoreFile {
        std::fs::read_to_string(&self.file).ok().and_then(|t| serde_json::from_str(&t).ok()).unwrap_or_default()
    }

    /// The file for a change: a damaged file is refused instead of overwritten with an empty store.
    fn read_for_write(&self) -> Result<StoreFile, VaultError> {
        match std::fs::read_to_string(&self.file) {
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(StoreFile::default()),
            Err(_) => Err(VaultError::failed("the keystore file cannot be read")),
            Ok(t) if t.trim().is_empty() => Ok(StoreFile::default()),
            Ok(t) => serde_json::from_str(&t).map_err(|_| VaultError::failed("the keystore file is damaged; nothing was written")),
        }
    }

    fn opened(&self, g: &mut Inner, content: &StoreFile) -> Result<BTreeMap<String, BTreeMap<String, String>>, VaultError> {
        let mut out: BTreeMap<String, BTreeMap<String, String>> = BTreeMap::new();
        if content.secrets.is_empty() && content.entries.is_empty() {
            return Ok(out);
        }
        let k = self.ensure_key(g)?.0;
        for (name, fields) in &content.secrets {
            for (f, s) in fields {
                if let Some(v) = open(&k, s, &aad(name, f)) {
                    out.entry(name.clone()).or_default().insert(f.clone(), v);
                }
            }
        }
        for (name, s) in &content.entries {
            if out.get(name).is_some_and(|f| f.contains_key(VALUE_FIELD)) {
                continue;
            }
            if let Some(v) = open(&k, s, name) {
                out.entry(name.clone()).or_default().insert(VALUE_FIELD.into(), v);
            }
        }
        Ok(out)
    }

    /// Writes v2: `cur.secrets` plus every v1 entry that opens, re-sealed as its value field. One that does not open stays.
    fn write(&self, g: &mut Inner, cur: StoreFile) -> Result<(), VaultError> {
        let k = self.ensure_key(g)?.0;
        let mut merged = cur.secrets;
        let mut kept = BTreeMap::new();
        for (name, s) in cur.entries {
            if merged.get(&name).is_some_and(|f| f.contains_key(VALUE_FIELD)) {
                continue;
            }
            match open(&k, &s, &name) {
                None => {
                    kept.insert(name, s);
                }
                Some(v) => {
                    let sealed = self.seal(&k, &aad(&name, VALUE_FIELD), &v)?;
                    merged.entry(name).or_default().insert(VALUE_FIELD.into(), sealed);
                }
            }
        }
        let text = serde_json::to_string(&StoreFile { v: 2, entries: kept, secrets: merged }).map_err(|_| VaultError::failed("the keystore could not be encoded"))?;
        std::fs::create_dir_all(&self.dir).map_err(|_| VaultError::failed("the state dir cannot be created"))?;
        let tmp = self.dir.join(format!("{FILE_NAME}.tmp"));
        std::fs::write(&tmp, text).map_err(|_| VaultError::failed("the keystore cannot be written"))?;
        crate::fsutil::restrict(&tmp);
        std::fs::rename(&tmp, &self.file).map_err(|_| VaultError::failed("the keystore cannot be replaced"))?;
        g.stamp = None;
        Ok(())
    }

    fn seal(&self, key: &[u8; 32], aad: &str, value: &str) -> Result<Sealed, VaultError> {
        let mut nonce = [0u8; 12];
        self.random.fill(&mut nonce).map_err(|_| VaultError::failed("no system randomness"))?;
        let k = LessSafeKey::new(UnboundKey::new(&AES_256_GCM, key).map_err(|_| VaultError::failed("bad key"))?);
        let mut buf = value.as_bytes().to_vec();
        k.seal_in_place_append_tag(Nonce::assume_unique_for_key(nonce), Aad::from(aad.as_bytes()), &mut buf)
            .map_err(|_| VaultError::failed("sealing failed"))?;
        Ok(Sealed { nonce: B64.encode(nonce), ct: B64.encode(buf) })
    }

    fn ensure_key(&self, g: &mut Inner) -> Result<([u8; 32], Protection), VaultError> {
        if let Some(k) = g.key {
            return Ok(k);
        }
        let nonblank = |s: Option<String>| s.filter(|v| !v.trim().is_empty());
        let external = match nonblank((self.env)(KEY_ENV)) {
            Some(v) => Some(v),
            None => match nonblank((self.env)(KEY_FILE_ENV)) {
                Some(path) => Some(
                    std::fs::read_to_string(path.trim())
                        .ok()
                        .filter(|t| !t.trim().is_empty())
                        .ok_or_else(|| VaultError::failed(format!("{KEY_FILE_ENV} points at a missing or empty file")))?,
                ),
                None => None,
            },
        };
        let k = match external {
            Some(text) => {
                let d = ring::digest::digest(&ring::digest::SHA256, text.trim().as_bytes());
                (to_key(d.as_ref())?, Protection::ExternalKey)
            }
            None => match self.os_key()? {
                Some(k) => k,
                None => self.file_key()?,
            },
        };
        g.key = Some(k);
        Ok(k)
    }

    fn fresh(&self) -> Result<[u8; 32], VaultError> {
        let mut k = [0u8; 32];
        self.random.fill(&mut k).map_err(|_| VaultError::failed("no system randomness"))?;
        Ok(k)
    }

    fn os_key(&self) -> Result<Option<([u8; 32], Protection)>, VaultError> {
        let Some(v) = &self.vault else { return Ok(None) };
        match v.fetch() {
            Ok(Some(b64)) => {
                let raw = B64.decode(b64.trim()).map_err(|_| VaultError::failed("the OS vault key is damaged"))?;
                Ok(Some((to_key(&raw)?, Protection::Os)))
            }
            // A key is there but does not open: never replace it (that would lose every secret).
            Err(why) => Err(VaultError::failed(why)),
            Ok(None) => {
                let fresh = self.fresh()?;
                Ok(v.store(&B64.encode(fresh)).then_some((fresh, Protection::Os)))
            }
        }
    }

    fn file_key(&self) -> Result<([u8; 32], Protection), VaultError> {
        let f = self.dir.join(KEY_FILE_NAME);
        if f.is_file() {
            let text = std::fs::read_to_string(&f).map_err(|_| VaultError::failed("the key file cannot be read"))?;
            let raw = B64.decode(text.trim()).map_err(|_| VaultError::failed("the key file is damaged"))?;
            return Ok((to_key(&raw)?, Protection::KeyFile));
        }
        std::fs::create_dir_all(&self.dir).map_err(|_| VaultError::failed("the state dir cannot be created"))?;
        let fresh = self.fresh()?;
        std::fs::write(&f, B64.encode(fresh)).map_err(|_| VaultError::failed("the key file cannot be written"))?;
        crate::fsutil::restrict(&f);
        Ok((fresh, Protection::KeyFile))
    }
}

fn to_key(raw: &[u8]) -> Result<[u8; 32], VaultError> {
    raw.try_into().map_err(|_| VaultError::failed("the master key has the wrong length"))
}

fn open(key: &[u8; 32], s: &Sealed, aad: &str) -> Option<String> {
    let nonce: [u8; 12] = B64.decode(&s.nonce).ok()?.try_into().ok()?;
    let mut buf = B64.decode(&s.ct).ok()?;
    let k = LessSafeKey::new(UnboundKey::new(&AES_256_GCM, key).ok()?);
    let plain = k.open_in_place(Nonce::assume_unique_for_key(nonce), Aad::from(aad.as_bytes()), &mut buf).ok()?;
    String::from_utf8(plain.to_vec()).ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp(tag: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("infantry-vault-ks-{tag}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        d
    }

    fn ks(dir: &Path) -> Keystore {
        Keystore::with(dir, Arc::new(|_| None), None, Limits::default())
    }

    #[test]
    fn fields_round_trip_scopes_and_masking() {
        let d = temp("rt");
        let k = ks(&d);
        k.set_field("stripe", "SECRET_KEY", "  fake-value-0123456789  ", None).unwrap();
        k.set_field("stripe", "PUB", "fakepub", Some("proj1")).unwrap();
        assert_eq!(k.protection().unwrap(), Protection::KeyFile);
        assert_eq!(k.field("stripe", "SECRET_KEY", None).unwrap().as_deref(), Some("fake-value-0123456789"));
        assert_eq!(k.resolve("stripe", Some("proj1")).unwrap().unwrap().keys().collect::<Vec<_>>(), vec!["PUB"]);
        assert_eq!(k.scope_of("stripe", Some("other")).unwrap(), Some(SCOPE_APP));
        assert_eq!(k.projects(), vec!["proj1"]);
        assert_eq!(k.names(Some("proj1")).unwrap(), vec!["stripe"]);
        assert_eq!(k.describe("stripe", None).unwrap()[0].tail, "••••6789");
        assert!(k.move_secret("stripe", Some("proj1"), None).is_err(), "target exists");
        k.move_secret("stripe", Some("proj1"), Some("proj2")).unwrap();
        assert_eq!(k.field("stripe", "PUB", Some("proj2")).unwrap().as_deref(), Some("fakepub"));
        // A fresh instance (same key file) opens it.
        assert_eq!(ks(&d).field("stripe", "SECRET_KEY", None).unwrap().as_deref(), Some("fake-value-0123456789"));
        assert!(k.delete_field("stripe", "SECRET_KEY", None).unwrap());
        assert_eq!(k.fields("stripe", None).unwrap(), None);
        assert!(check_name("Bad").is_err() && check_field("1x").is_err());
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn v1_entries_open_and_migrate_and_foreign_ones_are_kept() {
        let d = temp("v1");
        let k = ks(&d);
        k.protection().unwrap(); // makes the key file
        let key = k.lock().key.unwrap().0;
        let v1 = k.seal(&key, "update-token", "fake-token-1").unwrap();
        let foreign = Sealed { nonce: B64.encode([1u8; 12]), ct: B64.encode([2u8; 32]) };
        let mut f = StoreFile::default();
        f.v = 1;
        f.entries.insert("update-token".into(), v1);
        f.entries.insert("other".into(), foreign);
        std::fs::write(d.join(FILE_NAME), serde_json::to_string(&f).unwrap()).unwrap();
        assert_eq!(k.get("update-token").unwrap().as_deref(), Some("fake-token-1"));
        k.set("x.y", "fake").unwrap();
        let after: StoreFile = serde_json::from_str(&std::fs::read_to_string(d.join(FILE_NAME)).unwrap()).unwrap();
        assert!(after.secrets["update-token"].contains_key(VALUE_FIELD));
        assert!(after.entries.contains_key("other") && !after.entries.contains_key("update-token"));
        assert!(k.all_values().contains(&"fake-token-1".to_string()));
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn external_key_and_damaged_file_refuses_writes() {
        let d = temp("ext");
        let k = Keystore::with(&d, Arc::new(|n| (n == KEY_ENV).then(|| "fake-master".to_string())), None, Limits::default());
        assert_eq!(k.protection().unwrap(), Protection::ExternalKey);
        std::fs::create_dir_all(&d).unwrap();
        std::fs::write(d.join(FILE_NAME), "{not json").unwrap();
        assert!(k.set("a", "fake").is_err());
        assert_eq!(std::fs::read_to_string(d.join(FILE_NAME)).unwrap(), "{not json");
        let _ = std::fs::remove_dir_all(&d);
    }
}
