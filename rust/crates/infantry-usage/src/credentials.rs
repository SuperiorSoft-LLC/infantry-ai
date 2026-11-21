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

/// Where the Claude CLI keeps its sign-in: `$CLAUDE_CONFIG_DIR`, else `~/.claude`.
pub fn anthropic_config_dir(env: &Env, home: &Path) -> PathBuf {
    env("CLAUDE_CONFIG_DIR").filter(|d| !d.trim().is_empty()).map(PathBuf::from).unwrap_or_else(|| home.join(".claude"))
}

/// `$CLAUDE_CONFIG_DIR/.credentials.json`, else `~/.claude/.credentials.json` -> `claudeAiOauth.accessToken`.
pub fn anthropic_access_token(env: &Env, home: &Path) -> Option<String> {
    let root = json_file(&anthropic_config_dir(env, home).join(".credentials.json"))?;
    non_blank(root.get("claudeAiOauth")?.get("accessToken"))
}

/// Where opencode keeps its sign-in: `$XDG_DATA_HOME/opencode`, else `~/.local/share/opencode`.
pub fn opencode_data_dir(env: &Env, home: &Path) -> PathBuf {
    env("XDG_DATA_HOME")
        .filter(|d| !d.trim().is_empty())
        .map(|d| PathBuf::from(d).join("opencode"))
        .unwrap_or_else(|| home.join(".local").join("share").join("opencode"))
}

/// `$XDG_DATA_HOME/opencode/auth.json`, else `~/.local/share/opencode/auth.json` -> `["opencode-go"].key`.
pub fn opencode_go_key(env: &Env, home: &Path) -> Option<String> {
    let root = json_file(&opencode_data_dir(env, home).join("auth.json"))?;
    non_blank(root.get("opencode-go")?.get("key"))
}

/// A folder as a stable text, the same for the same place however it was spelled (`\` or `/`, any case on Windows):
/// what tells two processes they read the same sign-in. It names a place, never a credential.
pub fn place_key(dir: &Path) -> String {
    let text = dir.to_string_lossy().replace('\\', "/");
    let text = text.trim_end_matches('/');
    if cfg!(windows) { text.to_lowercase() } else { text.to_string() }
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

    #[test]
    fn a_place_has_one_key_however_it_is_spelled_and_the_dirs_follow_the_environment() {
        let none = |_: &str| None;
        let home = Path::new("/home/u");
        assert_eq!(anthropic_config_dir(&none, home), home.join(".claude"));
        let moved = |k: &str| (k == "CLAUDE_CONFIG_DIR").then(|| "/work/claude".to_string());
        assert_eq!(anthropic_config_dir(&moved, home), PathBuf::from("/work/claude"));
        let blank = |k: &str| (k == "CLAUDE_CONFIG_DIR").then(|| "  ".to_string());
        assert_eq!(anthropic_config_dir(&blank, home), home.join(".claude"));
        assert_eq!(opencode_data_dir(&none, home), home.join(".local").join("share").join("opencode"));
        assert_eq!(place_key(Path::new("C:\\Users\\U\\.claude\\")), place_key(Path::new("C:/Users/U/.claude")));
        assert_ne!(place_key(Path::new("/a/.claude")), place_key(Path::new("/b/.claude")));
    }
}
