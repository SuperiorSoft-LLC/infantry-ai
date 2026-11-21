// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! What the plan endpoints last answered, kept on disk so the processes of one operator share it.
//!
//! Every daemon on a machine (the installed one, the ones a test or a worker starts) reads the SAME plan endpoint with
//! the SAME token, and the provider rate-limits that token. Alone in memory, each daemon starts blind: its first read is
//! a request that can earn a 429, a restart forgets both the backoff and the last good reading (the card then says "no
//! reading"), and N daemons ask N times as often. One small file per provider and credential keeps the last good
//! reading, the failures in a row and the time before which nobody should ask again; a source takes it in before it
//! asks, and leaves it after it has asked.
//!
//! SECURITY: the files hold readings only (percents, reset times, a reason code). The credential is never written; the
//! PLACE it was found in is hashed into the file name, so processes signed in elsewhere never read each other's files.
use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::Mutex;

use infantry_protocol::usage::ProviderLimits;
use serde::{Deserialize, Serialize};

use crate::credentials::Env;

/// Moves the shared folder (default: see [`default_cache_dir`]).
pub const CACHE_DIR_ENV: &str = "INFANTRY_LIMITS_CACHE_DIR";
/// Bumped when the file's shape changes; a file of another version reads as absent.
const FILE_VERSION: u32 = 1;

/// What one source remembers: its last good reading and how its reads have been failing.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct SourceMemory {
    pub last_good: Option<ProviderLimits>,
    /// Failed reads in a row (0 after a good one).
    pub failures: u32,
    /// Epoch ms before which the source is not asked again.
    pub retry_at: i64,
    pub last_reason: Option<String>,
    /// Epoch ms of the last change of the failure bookkeeping: the later writer's wins when two processes meet.
    pub written_at: i64,
}

impl SourceMemory {
    /// Takes in what another process left for the same provider and credential. The younger good reading wins (one
    /// older than `stale_max_ms` is not taken), and the failure bookkeeping (count, wait, reason) is the LATER
    /// writer's: a good read elsewhere lifts our backoff, and a failure elsewhere starts it here too.
    pub fn adopt(&mut self, other: SourceMemory, now: i64, stale_max_ms: i64) {
        let SourceMemory { last_good, failures, retry_at, last_reason, written_at } = other;
        let theirs = last_good.filter(|g| now - g.observed_at < stale_max_ms);
        let younger = match (&self.last_good, &theirs) {
            (_, None) => false,
            (None, Some(_)) => true,
            (Some(ours), Some(t)) => t.observed_at > ours.observed_at,
        };
        if younger {
            self.last_good = theirs;
        }
        if written_at > self.written_at {
            self.failures = failures;
            self.retry_at = retry_at;
            self.last_reason = last_reason;
            self.written_at = written_at;
        }
    }
}

/// Where the processes of one operator leave what they read. `scope` says whose sign-in the source reads with (see
/// [`crate::limits::LimitSource::shared_scope`]): only the same provider with the same scope shares a memory.
pub trait ReadingCache: Send + Sync {
    /// What the other processes left, when there is something readable.
    fn load(&self, provider: &str, scope: &str) -> Option<SourceMemory>;
    /// Leaves `memory` for them. Best effort: the error is for the log, the read goes on without it.
    fn store(&self, provider: &str, scope: &str, memory: &SourceMemory) -> Result<(), String>;
}

/// In memory only: two services over one of these behave like two processes over one folder (tests).
#[derive(Default)]
pub struct MemoryReadingCache(Mutex<HashMap<(String, String), SourceMemory>>);

impl ReadingCache for MemoryReadingCache {
    fn load(&self, provider: &str, scope: &str) -> Option<SourceMemory> {
        self.0.lock().unwrap_or_else(|p| p.into_inner()).get(&(provider.to_string(), scope.to_string())).cloned()
    }
    fn store(&self, provider: &str, scope: &str, memory: &SourceMemory) -> Result<(), String> {
        self.0.lock().unwrap_or_else(|p| p.into_inner()).insert((provider.to_string(), scope.to_string()), memory.clone());
        Ok(())
    }
}

/// One JSON file per provider and sign-in place in a folder, replaced atomically (temp file + rename) on every store.
pub struct FileReadingCache {
    dir: PathBuf,
}

#[derive(Serialize, Deserialize)]
struct CacheFile {
    v: u32,
    memory: SourceMemory,
}

impl FileReadingCache {
    pub fn new(dir: impl Into<PathBuf>) -> Self {
        FileReadingCache { dir: dir.into() }
    }

    /// `claude-3f2a9c1d5e7b0a42.json`: the provider, then a hash of the sign-in place.
    pub fn file_name(provider: &str, scope: &str) -> String {
        let slug: String = provider.chars().map(|c| if c.is_ascii_alphanumeric() || c == '-' || c == '_' { c.to_ascii_lowercase() } else { '_' }).collect();
        format!("{slug}-{:016x}.json", fnv1a(scope))
    }

    fn path(&self, provider: &str, scope: &str) -> PathBuf {
        self.dir.join(Self::file_name(provider, scope))
    }
}

/// FNV-1a: stable across processes and versions (a `std` hasher is not promised to be), and nothing secret to protect.
fn fnv1a(text: &str) -> u64 {
    let mut h: u64 = 0xcbf2_9ce4_8422_2325;
    for b in text.bytes() {
        h ^= u64::from(b);
        h = h.wrapping_mul(0x0000_0100_0000_01b3);
    }
    h
}

impl ReadingCache for FileReadingCache {
    fn load(&self, provider: &str, scope: &str) -> Option<SourceMemory> {
        let text = std::fs::read_to_string(self.path(provider, scope)).ok()?;
        let file: CacheFile = serde_json::from_str(&text).ok()?;
        (file.v == FILE_VERSION).then_some(file.memory)
    }

    fn store(&self, provider: &str, scope: &str, memory: &SourceMemory) -> Result<(), String> {
        std::fs::create_dir_all(&self.dir).map_err(|e| format!("cannot create {}: {e}", self.dir.display()))?;
        let path = self.path(provider, scope);
        // The temp name carries the process id: two processes storing at once never write the same temp file.
        let tmp = path.with_extension(format!("{}.tmp", std::process::id()));
        let text = serde_json::to_string(&CacheFile { v: FILE_VERSION, memory: memory.clone() }).map_err(|e| e.to_string())?;
        std::fs::write(&tmp, text).map_err(|e| format!("cannot write {}: {e}", tmp.display()))?;
        std::fs::rename(&tmp, &path).map_err(|e| {
            let _ = std::fs::remove_file(&tmp);
            format!("cannot replace {}: {e}", path.display())
        })
    }
}

/// The folder the processes of one operator share: `INFANTRY_LIMITS_CACHE_DIR`, else `%LOCALAPPDATA%\SuperiorSoft\
/// InfantryAI-Cache\limits` (Windows), else `$XDG_CACHE_HOME/infantry-ai/limits`, else `~/.cache/infantry-ai/limits`.
/// Never a daemon's own state dir: a test or worker daemon runs on its own and must still meet the installed one here.
pub fn default_cache_dir(env: &Env, home: &std::path::Path) -> PathBuf {
    let set = |k: &str| env(k).filter(|v| !v.trim().is_empty());
    if let Some(d) = set(CACHE_DIR_ENV) {
        return PathBuf::from(d);
    }
    if let Some(l) = set("LOCALAPPDATA") {
        return PathBuf::from(l).join("SuperiorSoft").join("InfantryAI-Cache").join("limits");
    }
    if let Some(x) = set("XDG_CACHE_HOME") {
        return PathBuf::from(x).join("infantry-ai").join("limits");
    }
    home.join(".cache").join("infantry-ai").join("limits")
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::Path;

    fn good(at: i64) -> ProviderLimits {
        ProviderLimits { provider: "claude".into(), status: "ok".into(), windows: vec![], reason: None, observed_at: at, stale: false, retry_at: None }
    }

    fn memory(good_at: Option<i64>, failures: u32, retry_at: i64, written_at: i64) -> SourceMemory {
        SourceMemory { last_good: good_at.map(good), failures, retry_at, last_reason: (failures > 0).then(|| "rate_limited".to_string()), written_at }
    }

    #[test]
    fn the_younger_good_reading_and_the_later_writers_failure_state_win() {
        const H6: i64 = 6 * 3_600_000;
        // another process read well after our failure: its reading is younger and its (empty) bookkeeping is later
        let mut ours = memory(Some(100), 3, 9_000, 500);
        ours.adopt(memory(Some(800), 0, 0, 800), 1_000, H6);
        assert_eq!((ours.last_good.as_ref().map(|g| g.observed_at), ours.failures, ours.retry_at, ours.written_at), (Some(800), 0, 0, 800), "a good read elsewhere lifts our backoff");
        // another process failed after our good read: we take its wait, and keep the better reading we hold
        let mut ours = memory(Some(900), 0, 0, 900);
        ours.adopt(memory(Some(100), 2, 7_000, 950), 1_000, H6);
        assert_eq!((ours.last_good.as_ref().map(|g| g.observed_at), ours.failures, ours.retry_at), (Some(900), 2, 7_000), "a failure elsewhere starts our backoff, the younger reading stays");
        // an older write never undoes a newer state
        let mut ours = memory(None, 1, 5_000, 900);
        ours.adopt(memory(None, 4, 99_000, 100), 1_000, H6);
        assert_eq!((ours.failures, ours.retry_at), (1, 5_000));
        // a reading past the stale limit is not taken
        let mut ours = SourceMemory::default();
        ours.adopt(memory(Some(0), 0, 0, 0), H6 + 1, H6);
        assert!(ours.last_good.is_none());
    }

    #[test]
    fn files_round_trip_per_provider_and_sign_in_place_and_garbage_reads_as_nothing() {
        let dir = std::env::temp_dir().join(format!("infantry-usage-readings-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        let cache = FileReadingCache::new(&dir);
        assert_eq!(cache.load("claude", "c:/a/.claude"), None, "no folder yet is not an error");
        let m = memory(Some(1_234), 2, 99_000, 1_500);
        cache.store("claude", "c:/a/.claude", &m).unwrap();
        assert_eq!(cache.load("claude", "c:/a/.claude"), Some(m.clone()));
        assert_eq!(cache.load("claude", "c:/b/.claude"), None, "another sign-in place is another account");
        assert_eq!(cache.load("opencode-go", "c:/a/.claude"), None, "another provider");
        let on_disk = std::fs::read_to_string(dir.join(FileReadingCache::file_name("claude", "c:/a/.claude"))).unwrap();
        assert!(on_disk.contains("\"lastGood\"") && on_disk.contains("\"retryAt\":99000") && !on_disk.contains(".claude"), "readings only, the place only as a hash: {on_disk}");
        // a later store replaces it whole
        cache.store("claude", "c:/a/.claude", &memory(None, 0, 0, 2_000)).unwrap();
        assert_eq!(cache.load("claude", "c:/a/.claude").unwrap().written_at, 2_000);
        // garbage and another version read as nothing, never as a crash
        let path = dir.join(FileReadingCache::file_name("claude", "c:/a/.claude"));
        std::fs::write(&path, "{not json").unwrap();
        assert_eq!(cache.load("claude", "c:/a/.claude"), None);
        std::fs::write(&path, r#"{"v":99,"memory":{}}"#).unwrap();
        assert_eq!(cache.load("claude", "c:/a/.claude"), None);
        let leftovers = std::fs::read_dir(&dir).unwrap().filter_map(Result::ok).filter(|e| e.file_name().to_string_lossy().ends_with(".tmp")).count();
        assert_eq!(leftovers, 0, "no temp file is left behind");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn the_shared_folder_follows_the_environment() {
        let home = Path::new("/home/u");
        let env = |pairs: &'static [(&'static str, &'static str)]| move |k: &str| pairs.iter().find(|(n, _)| *n == k).map(|(_, v)| v.to_string());
        let win = env(&[("LOCALAPPDATA", "C:\\L")]);
        assert_eq!(default_cache_dir(&win, home), PathBuf::from("C:\\L").join("SuperiorSoft").join("InfantryAI-Cache").join("limits"));
        let xdg = env(&[("XDG_CACHE_HOME", "/x")]);
        assert_eq!(default_cache_dir(&xdg, home), PathBuf::from("/x").join("infantry-ai").join("limits"));
        assert_eq!(default_cache_dir(&env(&[]), home), home.join(".cache").join("infantry-ai").join("limits"));
        let moved = env(&[(CACHE_DIR_ENV, "/elsewhere"), ("LOCALAPPDATA", "C:\\L")]);
        assert_eq!(default_cache_dir(&moved, home), PathBuf::from("/elsewhere"), "the override wins");
        let blank = env(&[(CACHE_DIR_ENV, "  "), ("XDG_CACHE_HOME", "/x")]);
        assert_eq!(default_cache_dir(&blank, home), PathBuf::from("/x").join("infantry-ai").join("limits"));
    }
}
