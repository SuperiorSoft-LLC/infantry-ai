// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Reads the providers' local credentials, exactly where their own CLIs keep them. Values go back to the caller only:
//! nothing here logs, stores or formats them, and a read failure is just `None`.
use std::path::{Path, PathBuf};

/// Environment lookup, injectable for tests.
pub type Env = dyn Fn(&str) -> Option<String> + Send + Sync;

pub fn process_env(key: &str) -> Option<String> {
    std::env::var(key).ok()
}

pub fn user_home() -> PathBuf {
    std::env::var_os("USERPROFILE").or_else(|| std::env::var_os("HOME")).map(PathBuf::from).unwrap_or_else(|| PathBuf::from("."))
}

fn json_file(path: &Path) -> Option<serde_json::Value> {
    let text = std::fs::read_to_string(path).ok()?;
    serde_json::from_str(&text).ok()
}

fn non_blank(v: Option<&serde_json::Value>) -> Option<String> {
    v?.as_str().filter(|s| !s.trim().is_empty()).map(str::to_owned)
}

/// `$CLAUDE_CONFIG_DIR/.credentials.json`, else `~/.claude/.credentials.json` -> `claudeAiOauth.accessToken`.
pub fn anthropic_access_token(env: &Env, home: &Path) -> Option<String> {
    let dir = env("CLAUDE_CONFIG_DIR").filter(|d| !d.trim().is_empty()).map(PathBuf::from).unwrap_or_else(|| home.join(".claude"));
    let root = json_file(&dir.join(".credentials.json"))?;
    non_blank(root.get("claudeAiOauth")?.get("accessToken"))
}

/// `$XDG_DATA_HOME/opencode/auth.json`, else `~/.local/share/opencode/auth.json` -> `["opencode-go"].key`.
pub fn opencode_go_key(env: &Env, home: &Path) -> Option<String> {
    let dir = env("XDG_DATA_HOME")
        .filter(|d| !d.trim().is_empty())
        .map(|d| PathBuf::from(d).join("opencode"))
        .unwrap_or_else(|| home.join(".local").join("share").join("opencode"));
    let root = json_file(&dir.join("auth.json"))?;
    non_blank(root.get("opencode-go")?.get("key"))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_both_credential_files_from_their_own_places() {
        let home = std::env::temp_dir().join(format!("infantry-usage-cred-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&home);
        std::fs::create_dir_all(home.join(".claude")).unwrap();
        std::fs::create_dir_all(home.join(".local/share/opencode")).unwrap();
        std::fs::write(home.join(".claude/.credentials.json"), r#"{"claudeAiOauth":{"accessToken":"t-1"}}"#).unwrap();
        std::fs::write(home.join(".local/share/opencode/auth.json"), r#"{"opencode-go":{"key":"k-1"}}"#).unwrap();
        let none = |_: &str| None;
        assert_eq!(anthropic_access_token(&none, &home).as_deref(), Some("t-1"));
        assert_eq!(opencode_go_key(&none, &home).as_deref(), Some("k-1"));
        let elsewhere = |k: &str| (k == "CLAUDE_CONFIG_DIR").then(|| "Z:/nowhere".to_string());
        assert_eq!(anthropic_access_token(&elsewhere, &home), None);
        let _ = std::fs::remove_dir_all(&home);
    }
}
