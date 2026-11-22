// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Both sides' pairing state, in one folder (`<state>/runner-link/`): this machine's link id, the ONE orchestrator a runner
//! is paired with (only the credential's SHA-256 is kept), and the runners an orchestrator is paired with (it holds their
//! credentials). Written atomically; on unix the files are owner-only.

use crate::pairing::{random_bytes, same, sha256_hex};
use base64::Engine;
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use std::sync::Mutex;

const CREDENTIAL_SIZE: usize = 32;
const ID_SIZE: usize = 9;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PairedOrchestrator {
    pub id: String,
    pub name: String,
    pub credential_hash: String,
    pub issued_at: i64,
    pub runner_name: String,
}

#[derive(Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PairedRunner {
    pub name: String,
    pub url: String,
    #[serde(default)]
    pub fingerprint: String,
    pub credential: String,
    pub issued_at: i64,
    #[serde(default)]
    pub runner_id: String,
    #[serde(default)]
    pub contract: String,
    /// RR-VIS: the orchestrator name this runner was paired as (empty = paired before RR-VIS).
    #[serde(default)]
    pub paired_from: String,
}

impl std::fmt::Debug for PairedRunner {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "PairedRunner(name={}, url={}, runner_id={}, credential=redacted)", self.name, self.url, self.runner_id)
    }
}

#[derive(Debug, Default, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct State {
    #[serde(default)]
    self_id: Option<String>,
    #[serde(default)]
    orchestrator: Option<PairedOrchestrator>,
    #[serde(default)]
    peers: BTreeMap<String, PairedRunner>,
}

/// RR-8: reads the JSON state file `file`. A missing file is `None`; an UNREADABLE one (not JSON of this shape) is moved to
/// `<file>.unreadable` (a loud line on stderr names it) and is `None`: it is never silently reset in place, so what was in it
/// can still be recovered.
pub(crate) fn read_or_set_aside<T: serde::de::DeserializeOwned>(file: &Path) -> Option<T> {
    let text = std::fs::read_to_string(file).ok()?;
    match serde_json::from_str(&text) {
        Ok(v) => Some(v),
        Err(e) => {
            let aside = PathBuf::from(format!("{}.unreadable", file.display()));
            let _ = std::fs::remove_file(&aside);
            match std::fs::rename(file, &aside) {
                Ok(()) => eprintln!("[infantry] {} is unreadable ({e}); set aside as {}", file.display(), aside.display()),
                Err(re) => eprintln!("[infantry] {} is unreadable ({e}) and could not be set aside ({re})", file.display()),
            }
            None
        }
    }
}

pub fn token(bytes: usize) -> String {
    base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(random_bytes(bytes))
}

pub struct LinkStore {
    file: Option<PathBuf>,
    state: Mutex<State>,
}

impl LinkStore {
    /// The store in `dir` (made on first write).
    pub fn open(dir: &Path) -> Self {
        let file = dir.join("link.json");
        let state = read_or_set_aside(&file).unwrap_or_default();
        LinkStore { file: Some(file), state: Mutex::new(state) }
    }

    pub fn in_memory() -> Self {
        LinkStore { file: None, state: Mutex::new(State::default()) }
    }

    fn save(&self, s: &State) {
        let Some(file) = &self.file else { return };
        if let Some(dir) = file.parent() {
            let _ = std::fs::create_dir_all(dir);
        }
        let tmp = file.with_extension("tmp");
        if std::fs::write(&tmp, serde_json::to_string_pretty(s).expect("state")).is_ok() {
            #[cfg(unix)]
            {
                use std::os::unix::fs::PermissionsExt;
                let _ = std::fs::set_permissions(&tmp, std::fs::Permissions::from_mode(0o600));
            }
            let _ = std::fs::rename(&tmp, file);
        }
    }

    /// This machine's link id (`inf-…`), made once.
    pub fn self_id(&self) -> String {
        let mut s = self.state.lock().unwrap();
        if let Some(id) = &s.self_id {
            return id.clone();
        }
        let id = format!("inf-{}", token(ID_SIZE));
        s.self_id = Some(id.clone());
        self.save(&s);
        id
    }

    // ---- runner side ----

    pub fn orchestrator(&self) -> Option<PairedOrchestrator> {
        self.state.lock().unwrap().orchestrator.clone()
    }

    /// Pairs (or re-keys) with orchestrator `id`; returns the NEW credential (only its hash is kept).
    pub fn issue(&self, id: &str, name: &str, runner_name: &str, now: i64) -> String {
        let credential = token(CREDENTIAL_SIZE);
        let mut s = self.state.lock().unwrap();
        s.orchestrator = Some(PairedOrchestrator {
            id: id.into(),
            name: if name.trim().is_empty() { id.into() } else { name.into() },
            credential_hash: sha256_hex(&credential),
            issued_at: now,
            runner_name: runner_name.into(),
        });
        self.save(&s);
        credential
    }

    /// Whether `credential` is the live credential of the paired orchestrator `id` (constant time).
    pub fn accepts(&self, id: Option<&str>, credential: Option<&str>) -> bool {
        let Some(p) = self.orchestrator() else { return false };
        let (Some(id), Some(c)) = (id.filter(|s| !s.trim().is_empty()), credential.filter(|s| !s.trim().is_empty())) else { return false };
        let id_ok = same(id.as_bytes(), p.id.as_bytes());
        let cred_ok = same(sha256_hex(c).as_bytes(), p.credential_hash.as_bytes());
        id_ok & cred_ok
    }

    pub fn unpair(&self) -> bool {
        let mut s = self.state.lock().unwrap();
        let was = s.orchestrator.take().is_some();
        self.save(&s);
        was
    }

    // ---- orchestrator side ----

    pub fn runners(&self) -> Vec<PairedRunner> {
        self.state.lock().unwrap().peers.values().cloned().collect()
    }

    pub fn runner(&self, name: &str) -> Option<PairedRunner> {
        self.state.lock().unwrap().peers.get(name).cloned()
    }

    pub fn save_runner(&self, r: PairedRunner) {
        let mut s = self.state.lock().unwrap();
        s.peers.insert(r.name.clone(), r);
        self.save(&s);
    }

    pub fn remove_runner(&self, name: &str) -> bool {
        let mut s = self.state.lock().unwrap();
        let was = s.peers.remove(name).is_some();
        self.save(&s);
        was
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn credential_is_checked_by_hash() {
        let s = LinkStore::in_memory();
        assert!(!s.accepts(Some("o"), Some("x")));
        let c = s.issue("inf-o", "pc", "box", 5);
        assert!(s.accepts(Some("inf-o"), Some(&c)));
        assert!(!s.accepts(Some("inf-p"), Some(&c)));
        assert!(!s.accepts(Some("inf-o"), Some("nope")));
        assert!(!s.accepts(Some("inf-o"), None));
        let c2 = s.issue("inf-o", "pc", "box", 6);
        assert!(!s.accepts(Some("inf-o"), Some(&c)) && s.accepts(Some("inf-o"), Some(&c2)));
        assert!(s.self_id().starts_with("inf-") && s.self_id() == s.self_id());
        assert!(s.unpair() && !s.accepts(Some("inf-o"), Some(&c2)));
    }

    #[test]
    fn an_unreadable_state_file_is_set_aside_not_reset() {
        let dir = std::env::temp_dir().join(format!("link-store-bad-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("link.json"), "{ not json").unwrap();
        let s = LinkStore::open(&dir);
        assert!(s.runners().is_empty());
        assert_eq!(std::fs::read_to_string(dir.join("link.json.unreadable")).unwrap(), "{ not json");
        assert!(!dir.join("link.json").exists());
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn persists() {
        let dir = std::env::temp_dir().join(format!("link-store-{}", uuid::Uuid::new_v4()));
        let id = LinkStore::open(&dir).self_id();
        assert_eq!(LinkStore::open(&dir).self_id(), id);
        std::fs::remove_dir_all(dir).unwrap();
    }
}
