// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! RUST-UPD-2: the update feed, shared by every target. The desktop checks it through the Tauri updater plugin
//! (`update.rs`); the phone has no plugin, so it reads the same `latest.json` itself (`android_update.rs`) with the
//! rules here. Pure code: no I/O, so the tests run on the host.
//!
//! The manifest is the Tauri updater's `latest.json`. The Android build adds one platform entry per ABI
//! (`android-aarch64`, `android-armv7`, `android-x86_64`, or `android-universal`) with the APK `url`, its `sha256`
//! (lowercase hex) and its `size`. Tauri's desktop reader needs a `signature` string on every entry, so the Android entry
//! carries an empty one and the phone ignores it: the checksum guards the download, and Android itself refuses an APK
//! that is not signed with the installed app's key.

use semver::Version;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;

/// Ticket 1d57e853: the app reads GitHub's releases live; publishing a release is the only step (no promoted feed file).
/// Stable: the `latest.json` asset of GitHub's latest (non-prerelease) release. GitHub redirects this address itself.
pub const STABLE_FEED: &str = "https://github.com/SuperiorSoft-LLC/infantry-ai/releases/latest/download/latest.json";
/// Nightly: the release list; `nightly_feed` picks the newest prerelease that carries a `latest.json`.
pub const RELEASES_API: &str = "https://api.github.com/repos/SuperiorSoft-LLC/infantry-ai/releases?per_page=30";
/// What the settings show as the default feed: the per-channel GitHub source (a custom feed may use `{channel}`).
pub const DEFAULT_FEED: &str = "github:SuperiorSoft-LLC/infantry-ai (latest release; nightly = newest prerelease)";
pub const CHANNELS: [&str; 2] = ["stable", "nightly"];
/// Named setting `updater.checkMinutes`: how often the open app re-checks. 0 turns the timer off (start + button remain).
pub const DEFAULT_CHECK_MINUTES: u32 = 60;

/// `ui.json` `updater` (desktop) or `updater.json` (phone).
#[derive(Serialize, Deserialize, Default, Clone)]
#[serde(rename_all = "camelCase")]
pub struct UpdaterSettings {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub feed_url: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub channel: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub auto_check: Option<bool>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub auto_install: Option<bool>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub check_minutes: Option<u32>,
}

impl UpdaterSettings {
    pub fn channel(&self) -> &str {
        self.channel.as_deref().filter(|c| CHANNELS.contains(c)).unwrap_or("stable")
    }
    pub fn feed_template(&self) -> &str {
        self.feed_url.as_deref().map(str::trim).filter(|u| !u.is_empty()).unwrap_or(DEFAULT_FEED)
    }
    pub fn check_minutes(&self) -> u32 {
        self.check_minutes.unwrap_or(DEFAULT_CHECK_MINUTES)
    }
}

/// Where a check reads.
#[derive(Debug, PartialEq, Clone)]
pub enum Source {
    /// A `latest.json` address.
    Manifest(String),
    /// The GitHub release list (nightly): fetch it, then `nightly_feed` gives the manifest address.
    ReleaseList(String),
}

/// The source for `channel`. No custom feed: stable is GitHub's latest release, nightly the newest prerelease.
/// A custom feed has `{channel}` replaced.
pub fn source_for(template: &str, channel: &str) -> Source {
    if template == DEFAULT_FEED {
        return if channel == "nightly" { Source::ReleaseList(RELEASES_API.to_string()) } else { Source::Manifest(STABLE_FEED.to_string()) };
    }
    Source::Manifest(template.replace("{channel}", channel))
}

#[derive(Deserialize)]
struct GhRelease {
    tag_name: String,
    #[serde(default)]
    draft: bool,
    #[serde(default)]
    prerelease: bool,
    #[serde(default)]
    assets: Vec<GhAsset>,
}
#[derive(Deserialize)]
struct GhAsset {
    name: String,
    browser_download_url: String,
}

/// From the GitHub release list: the `latest.json` of the highest-version published prerelease (`rust-v0.1.4-nightly.7`).
/// The rolling `rust-nightly` / `rust-stable` releases are not versions and are skipped.
pub fn nightly_feed(releases_json: &str) -> Result<String, String> {
    let list: Vec<GhRelease> = serde_json::from_str(releases_json).map_err(|e| format!("GitHub's release list is not readable: {e}"))?;
    list.iter()
        .filter(|r| r.prerelease && !r.draft)
        .filter_map(|r| {
            let v = parse_version(r.tag_name.trim_start_matches("rust-"))?;
            let a = r.assets.iter().find(|a| a.name == "latest.json")?;
            Some((v, a.browser_download_url.clone()))
        })
        .max_by(|a, b| a.0.cmp(&b.0))
        .map(|(_, url)| url)
        .ok_or_else(|| "no nightly (prerelease) with a latest.json is published yet".to_string())
}

/// Status line: "up to date (0.1.3, checked 14:05)" or "0.1.4 available".
pub fn status_line(current: &str, available: Option<&str>, checked_at: &str) -> String {
    match available {
        Some(v) => format!("{v} available"),
        None => format!("up to date ({current}, checked {checked_at})"),
    }
}

/// A feed address the app may read: https anywhere, plain http only on this machine.
pub fn feed_allowed(url: &str) -> bool {
    url.starts_with("https://") || url.starts_with("http://127.0.0.1") || url.starts_with("http://localhost")
}

#[derive(Deserialize)]
pub struct Manifest {
    pub version: String,
    #[serde(default)]
    pub notes: Option<String>,
    #[serde(default)]
    pub pub_date: Option<String>,
    #[serde(default)]
    pub platforms: HashMap<String, Platform>,
}

#[derive(Deserialize, Clone)]
pub struct Platform {
    pub url: String,
    #[serde(default)]
    pub sha256: Option<String>,
    #[serde(default)]
    pub size: Option<u64>,
}

/// What the phone should download: one APK.
#[derive(Serialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Offer {
    pub version: String,
    pub notes: Option<String>,
    pub date: Option<String>,
    pub url: String,
    pub sha256: String,
    pub size: Option<u64>,
}

fn parse_version(v: &str) -> Option<Version> {
    Version::parse(v.trim().trim_start_matches('v')).ok()
}

/// Whether `remote` should be offered to a build running `current`: only a strictly newer version, on every channel.
/// A feed that lags behind (a stable feed still at 0.1.0 while 0.1.2 is installed) must never downgrade the app.
pub fn offered(current: &str, remote: &str, _channel: &str) -> bool {
    matches!((parse_version(current), parse_version(remote)), (Some(c), Some(r)) if r > c)
}

/// The platform keys to try for an Android ABI (`aarch64`, `armv7`, `x86_64`), the specific one first.
pub fn android_keys(abi: &str) -> [String; 2] {
    [format!("android-{abi}"), "android-universal".to_string()]
}

/// Reads a `latest.json` and answers the APK to download for `abi`, or `None` when this build is current (or the
/// manifest has no Android entry). Errors name what is wrong with the manifest.
pub fn android_offer(json: &str, current: &str, channel: &str, abi: &str) -> Result<Option<Offer>, String> {
    let m: Manifest = serde_json::from_str(json).map_err(|e| format!("the update feed is not a latest.json: {e}"))?;
    if parse_version(&m.version).is_none() {
        return Err(format!("the update feed has an invalid version `{}`", m.version));
    }
    if !offered(current, &m.version, channel) {
        return Ok(None);
    }
    let Some(p) = android_keys(abi).iter().find_map(|k| m.platforms.get(k)) else {
        return Ok(None);
    };
    let sha = p.sha256.as_deref().map(|s| s.trim().to_lowercase()).filter(|s| s.len() == 64 && s.bytes().all(|b| b.is_ascii_hexdigit()));
    let Some(sha256) = sha else {
        return Err("the Android entry in the update feed has no valid sha256, so the download could not be verified".into());
    };
    if !feed_allowed(&p.url) {
        return Err(format!("the Android download address must be https: {}", p.url));
    }
    Ok(Some(Offer {
        version: m.version.trim_start_matches('v').to_string(),
        notes: m.notes,
        date: m.pub_date,
        url: p.url.clone(),
        sha256,
        size: p.size,
    }))
}

#[cfg(test)]
mod tests {
    use super::*;

    const SHA: &str = "ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12";

    fn feed(version: &str, entries: &str) -> String {
        format!(r#"{{"version":"{version}","notes":"n","pub_date":"2026-11-20T12:00:00Z","platforms":{{{entries}}}}}"#)
    }
    fn apk(key: &str) -> String {
        format!(r#""{key}":{{"signature":"","url":"https://x/a.apk","sha256":"{SHA}","size":42}}"#)
    }

    #[test]
    fn the_feed_source_per_channel_is_github_live_and_unknown_channels_fall_back_to_stable() {
        let s = UpdaterSettings::default();
        assert_eq!(
            source_for(s.feed_template(), "stable"),
            Source::Manifest("https://github.com/SuperiorSoft-LLC/infantry-ai/releases/latest/download/latest.json".into())
        );
        assert_eq!(source_for(s.feed_template(), "nightly"), Source::ReleaseList(RELEASES_API.into()));
        assert!(!STABLE_FEED.contains("rust-stable") && !RELEASES_API.contains("rust-nightly"));
        let odd = UpdaterSettings { channel: Some("beta".into()), feed_url: Some("  ".into()), ..Default::default() };
        assert_eq!(odd.channel(), "stable");
        assert_eq!(odd.feed_template(), DEFAULT_FEED);
        assert_eq!(source_for("https://x/{channel}/latest.json", "nightly"), Source::Manifest("https://x/nightly/latest.json".into()));
        assert_eq!(UpdaterSettings::default().check_minutes(), 60);
    }

    #[test]
    fn the_newest_published_prerelease_with_a_manifest_is_the_nightly() {
        let a = |n: &str| format!(r#"{{"name":"latest.json","browser_download_url":"https://gh/{n}/latest.json"}}"#);
        let json = format!(
            r#"[{{"tag_name":"rust-nightly","prerelease":true,"draft":false,"assets":[{}]}},
               {{"tag_name":"rust-v0.1.10-nightly.2","prerelease":true,"draft":false,"assets":[{}]}},
               {{"tag_name":"rust-v0.1.9-nightly.9","prerelease":true,"draft":false,"assets":[{}]}},
               {{"tag_name":"rust-v0.1.11-nightly.1","prerelease":true,"draft":true,"assets":[{}]}},
               {{"tag_name":"rust-v0.2.0","prerelease":false,"draft":false,"assets":[{}]}}]"#,
            a("roll"), a("n10"), a("n9"), a("draft"), a("stable")
        );
        assert_eq!(nightly_feed(&json).unwrap(), "https://gh/n10/latest.json");
        assert!(nightly_feed("[]").is_err() && nightly_feed("nope").is_err());
    }

    #[test]
    fn an_os_app_release_is_never_the_nightly() {
        // BOS-9: os-app-v* releases hold a .bosapp and feed.json, never latest.json; even a published prerelease
        // with a (wrongly) named latest.json must not be taken for a version of this app.
        let a = |n: &str| format!(r#"{{"name":"latest.json","browser_download_url":"https://gh/{n}/latest.json"}}"#);
        let json = format!(
            r#"[{{"tag_name":"os-app-v9.9.9","prerelease":true,"draft":false,"assets":[{}]}},
               {{"tag_name":"os-app-v0.7.3","prerelease":true,"draft":false,"assets":[{{"name":"feed.json","browser_download_url":"https://gh/os/feed.json"}}]}},
               {{"tag_name":"rust-v0.1.2-nightly.1","prerelease":true,"draft":false,"assets":[{}]}}]"#,
            a("os"), a("n")
        );
        assert_eq!(nightly_feed(&json).unwrap(), "https://gh/n/latest.json");
        let only_os = r#"[{"tag_name":"os-app-v9.9.9","prerelease":true,"draft":false,"assets":[{"name":"feed.json","browser_download_url":"https://gh/os/feed.json"}]}]"#;
        assert!(nightly_feed(only_os).is_err());
    }

    #[test]
    fn the_status_line_names_the_version_and_the_time() {
        assert_eq!(status_line("0.1.3", None, "14:05"), "up to date (0.1.3, checked 14:05)");
        assert_eq!(status_line("0.1.3", Some("0.1.4"), "14:05"), "0.1.4 available");
    }

    #[test]
    fn only_a_strictly_newer_version_is_offered_on_every_channel() {
        assert!(offered("0.1.0", "0.2.0", "nightly"));
        assert!(!offered("0.2.0", "0.1.0", "nightly"));
        assert!(!offered("0.2.0", "0.2.0", "nightly"));
        assert!(offered("0.1.0", "0.1.2", "stable"));
        assert!(offered("0.1.9", "0.1.10", "stable"));
        assert!(!offered("0.1.10", "0.1.9", "stable"));
        assert!(!offered("0.1.2", "0.1.0", "stable"));
        assert!(!offered("0.2.0-nightly.1", "0.1.9", "stable"));
        assert!(!offered("0.1.0", "v0.1.0", "stable"));
        assert!(!offered("0.1.0", "junk", "stable"));
    }

    #[test]
    fn a_newer_android_entry_becomes_an_offer_and_the_specific_abi_wins() {
        let json = feed("0.2.0", &format!("{},{}", apk("android-universal"), apk("android-aarch64").replace("a.apk", "arm.apk")));
        let o = android_offer(&json, "0.1.0", "stable", "aarch64").unwrap().unwrap();
        assert_eq!((o.version.as_str(), o.url.as_str(), o.size), ("0.2.0", "https://x/arm.apk", Some(42)));
        let o = android_offer(&json, "0.1.0", "stable", "x86_64").unwrap().unwrap();
        assert_eq!(o.url, "https://x/a.apk");
    }

    #[test]
    fn nothing_is_offered_when_current_or_when_there_is_no_android_entry() {
        let json = feed("0.2.0", &apk("android-aarch64"));
        assert_eq!(android_offer(&json, "0.2.0", "stable", "aarch64").unwrap(), None);
        let desktop_only = feed("0.2.0", r#""windows-x86_64":{"signature":"s","url":"https://x/a.exe"}"#);
        assert_eq!(android_offer(&desktop_only, "0.1.0", "stable", "aarch64").unwrap(), None);
    }

    #[test]
    fn an_unverifiable_or_unsafe_entry_is_refused() {
        let no_sha = feed("0.2.0", r#""android-aarch64":{"signature":"","url":"https://x/a.apk"}"#);
        assert!(android_offer(&no_sha, "0.1.0", "stable", "aarch64").unwrap_err().contains("sha256"));
        let http = feed("0.2.0", &apk("android-aarch64").replace("https://x", "http://evil.example"));
        assert!(android_offer(&http, "0.1.0", "stable", "aarch64").unwrap_err().contains("https"));
        assert!(android_offer("{}", "0.1.0", "stable", "aarch64").is_err());
    }

    /// tests/fixtures/latest.json was written by scripts/release-tools.mjs (`feed`), so the script and this reader agree.
    #[test]
    fn the_pipeline_scripts_manifest_gives_the_phone_its_apk() {
        let json = include_str!("../tests/fixtures/latest.json");
        let o = android_offer(json, "0.1.0", "stable", "aarch64").unwrap().unwrap();
        assert_eq!((o.version.as_str(), o.size), ("0.2.0", Some(14)));
        assert_eq!(o.sha256, "cb4870807289f0ebb14bbfc941421b08f5766fa0346c1828bd5f09a955ccd560");
        assert!(o.url.ends_with("/InfantryAI_0.2.0_arm64.apk"));
        assert_eq!(android_offer(json, "0.2.0", "stable", "aarch64").unwrap(), None);
        // The Tauri desktop reader needs a `signature` on every platform entry, the Android one included.
        let v: serde_json::Value = serde_json::from_str(json).unwrap();
        assert!(v["platforms"].as_object().unwrap().values().all(|p| p["signature"].is_string() && p["url"].is_string()));
    }
}
