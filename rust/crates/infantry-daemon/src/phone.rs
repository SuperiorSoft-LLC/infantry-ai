// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! BOS-33: phone access when Infantry AI runs inside BattalionOS (the standalone app's Settings > Daemon > Allow phone
//! connections, without a Tauri host). The state is `<state>/launch.json` (host `0.0.0.0`, the port, `tokenFile`), which
//! [crate::os_app::Launch] reads at the next start. The token never leaves this module except on an explicit reveal.
//!
//! - [get]/[set]: the Settings rows' read and write (served only to the app's own page, see `server.rs`).
//! - [migrate_standalone]: a person who had phone connections ON in the standalone app keeps it: the same port and the
//!   same token, found in the standalone app's `ui.json` (`remoteAccess`, `daemonPort`, `daemonStateDir`). Once only (a
//!   marker); off stays off; nothing ever opens by itself when it was off.

use crate::os_app::{Launch, LAUNCH_FILE};
use serde_json::{json, Value};
use std::net::{IpAddr, Ipv4Addr};
use std::path::{Path, PathBuf};

/// The phone-access token in the state dir (64 hex characters from the OS RNG), the standalone app's own name.
pub const TOKEN_FILE: &str = "remote-access.token";
/// Why the daemon is not on the network (a taken port), shown by Settings.
pub const NOTE_FILE: &str = "phone-access.note";
const MIGRATED_MARK: &str = ".phone-migrated";
/// The standalone daemon's default port (`infantry-distribution` DEFAULT_PORT).
pub const STANDALONE_PORT: u16 = 18990;
/// The standalone app's bundle identifier folder under the local app data (its `ui.json` lives there).
pub const STANDALONE_IDENTIFIER: &str = "com.superiorsoft.infantry";

/// A token file in `state`, made when missing or too short. Never printed.
pub fn token_file(state: &Path) -> Result<PathBuf, String> {
    let f = state.join(TOKEN_FILE);
    if !std::fs::read_to_string(&f).map(|t| t.trim().len() >= 32).unwrap_or(false) {
        std::fs::create_dir_all(state).map_err(|e| format!("{}: {e}", state.display()))?;
        let t = format!("{}{}", uuid::Uuid::new_v4().simple(), uuid::Uuid::new_v4().simple());
        std::fs::write(&f, t).map_err(|e| format!("token file {}: {e}", f.display()))?;
    }
    Ok(f)
}

fn read_launch_value(state: &Path) -> Value {
    std::fs::read_to_string(state.join(LAUNCH_FILE)).ok().and_then(|t| serde_json::from_str(t.trim_start_matches('\u{feff}')).ok()).unwrap_or_else(|| json!({}))
}

fn write_launch_value(state: &Path, v: &Value) -> Result<(), String> {
    let path = state.join(LAUNCH_FILE);
    let tmp = path.with_extension("tmp");
    std::fs::write(&tmp, serde_json::to_vec_pretty(v).map_err(|e| e.to_string())?).map_err(|e| format!("{}: {e}", tmp.display()))?;
    std::fs::rename(&tmp, &path).map_err(|e| format!("{}: {e}", path.display()))
}

/// Phone access is on when launch.json binds a non-loopback host and names a token file.
pub fn enabled(state: &Path) -> bool {
    match Launch::read(state) {
        Ok(l) => l.token_file.is_some() && l.host.as_deref().is_some_and(|h| !crate::auth::is_loopback_host(h)),
        Err(_) => false,
    }
}

#[derive(Debug, Clone, PartialEq, serde::Serialize)]
pub struct Address {
    pub address: String,
    pub label: String,
}

/// 100.64.0.0/10, the Tailscale (CGNAT) range.
fn tailscale(ip: Ipv4Addr) -> bool {
    let o = ip.octets();
    o[0] == 100 && (64..128).contains(&o[1])
}

/// The addresses a phone could reach: IPv4, not loopback or link-local; Tailscale first, then the local network.
pub fn reachable(ifaces: &[(String, IpAddr)]) -> Vec<Address> {
    let mut out: Vec<(u8, Address)> = ifaces
        .iter()
        .filter_map(|(name, ip)| match ip {
            IpAddr::V4(v4) if !v4.is_loopback() && !v4.is_link_local() && !v4.is_unspecified() => {
                let (rank, label) = if tailscale(*v4) || name.to_ascii_lowercase().contains("tailscale") {
                    (0, "Tailscale".to_string())
                } else if v4.is_private() {
                    (1, "Local network".to_string())
                } else {
                    (2, name.clone())
                };
                Some((rank, Address { address: v4.to_string(), label }))
            }
            _ => None,
        })
        .collect();
    out.sort_by(|a, b| a.0.cmp(&b.0).then(a.1.address.cmp(&b.1.address)));
    out.dedup_by(|a, b| a.1.address == b.1.address);
    out.into_iter().map(|(_, a)| a).collect()
}

/// What Settings > Daemon shows (the standalone app's `PairingView`). `token` only on `reveal` and only when on.
pub fn get(state: &Path, bound_port: u16, ifaces: &[(String, IpAddr)], reveal: bool) -> Value {
    let on = enabled(state);
    let token = (reveal && on).then(|| std::fs::read_to_string(state.join(TOKEN_FILE)).ok().map(|t| t.trim().to_string())).flatten().filter(|t| !t.is_empty());
    let note = std::fs::read_to_string(state.join(NOTE_FILE)).ok().map(|n| n.trim().to_string()).filter(|n| !n.is_empty());
    // The port a phone dials: the one the saved launch asks for (what a restart will bind), else the bound one.
    let port = Launch::read(state).ok().and_then(|l| l.port).filter(|p| *p != 0).unwrap_or(bound_port);
    json!({
        "available": true,
        "enabled": on,
        "port": port,
        "addresses": reachable(ifaces),
        "hostName": std::env::var("COMPUTERNAME").ok().or_else(|| std::env::var("HOSTNAME").ok()),
        "token": token,
        "note": note,
    })
}

/// Turns phone access on or off in `launch.json` (it applies at the next start). On: host `0.0.0.0`, the token file, and
/// the port kept (`bound_port` when the file names none). Off: the host and token file removed; the token file itself and
/// the port stay, so turning it on again keeps the paired phones paired.
pub fn set(state: &Path, on: bool, bound_port: u16) -> Result<(), String> {
    let mut v = read_launch_value(state);
    let obj = v.as_object_mut().ok_or("launch.json is not an object")?;
    if on {
        let f = token_file(state)?;
        obj.insert("host".into(), json!("0.0.0.0"));
        obj.insert("tokenFile".into(), json!(f.to_string_lossy()));
        if obj.get("port").and_then(|p| p.as_u64()).is_none() && bound_port != 0 {
            obj.insert("port".into(), json!(bound_port));
        }
    } else {
        obj.remove("host");
        obj.remove("tokenFile");
    }
    write_launch_value(state, &v)?;
    let _ = std::fs::remove_file(state.join(NOTE_FILE));
    Ok(())
}

/// Where the standalone app keeps its `ui.json`: `%LOCALAPPDATA%\com.superiorsoft.infantry` (the Tauri app-local-data dir).
pub fn standalone_settings_dir() -> Option<PathBuf> {
    let base = std::env::var_os("LOCALAPPDATA").map(PathBuf::from).or_else(|| std::env::var_os("XDG_DATA_HOME").map(PathBuf::from))?;
    Some(base.join(STANDALONE_IDENTIFIER))
}

/// The standalone app's default daemon state dir (`%LOCALAPPDATA%\SuperiorSoft\InfantryAI-Rust`).
pub fn standalone_default_state() -> Option<PathBuf> {
    std::env::var_os("LOCALAPPDATA").map(|b| PathBuf::from(b).join("SuperiorSoft").join("InfantryAI-Rust"))
}

fn same_file(a: &Path, b: &Path) -> bool {
    match (std::fs::canonicalize(a), std::fs::canonicalize(b)) {
        (Ok(a), Ok(b)) => a == b,
        _ => false,
    }
}

/// One-time carry-over of the standalone app's phone access. `standalone_settings` is the folder holding its `ui.json`,
/// `standalone_state` the default state dir it would use. Does nothing (and never opens anything) unless that ui.json says
/// `remoteAccess: true` and a token exists there. Returns true when it turned phone access on.
pub fn migrate_standalone(state: &Path, standalone_settings: Option<&Path>, standalone_state: Option<&Path>) -> Result<bool, String> {
    let mark = state.join(MIGRATED_MARK);
    if mark.exists() {
        return Ok(false);
    }
    let _ = std::fs::create_dir_all(state);
    // The mark first: whatever follows, this runs once, so a later "off" is never undone.
    std::fs::write(&mark, b"1").map_err(|e| format!("{}: {e}", mark.display()))?;
    if state.join(LAUNCH_FILE).exists() {
        return Ok(false); // the person already set it here
    }
    let Some(dir) = standalone_settings else { return Ok(false) };
    let Some(ui) = std::fs::read_to_string(dir.join("ui.json")).ok().and_then(|t| serde_json::from_str::<Value>(t.trim_start_matches('\u{feff}')).ok()) else { return Ok(false) };
    if ui.get("remoteAccess").and_then(|v| v.as_bool()) != Some(true) {
        return Ok(false);
    }
    let their_state = ui.get("daemonStateDir").and_then(|v| v.as_str()).map(str::trim).filter(|s| !s.is_empty()).map(PathBuf::from).or_else(|| standalone_state.map(Path::to_path_buf));
    let Some(their_token) = their_state.as_ref().map(|s| s.join(TOKEN_FILE)).filter(|f| std::fs::read_to_string(f).map(|t| t.trim().len() >= 32).unwrap_or(false)) else { return Ok(false) };
    let ours = state.join(TOKEN_FILE);
    if !same_file(&their_token, &ours) {
        std::fs::copy(&their_token, &ours).map_err(|e| format!("copying the phone token: {e}"))?;
    }
    let port = ui.get("daemonPort").and_then(|p| p.as_u64()).and_then(|p| u16::try_from(p).ok()).filter(|p| *p != 0).unwrap_or(STANDALONE_PORT);
    set(state, true, port)?;
    Ok(true)
}

/// Records why the daemon could not take the phone port (Settings shows it).
pub fn write_note(state: &Path, text: &str) {
    let _ = std::fs::write(state.join(NOTE_FILE), text);
}

#[cfg(test)]
mod tests {
    use super::*;

    const T: &str = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    fn standalone(on: bool, with_token: bool) -> (tempfile::TempDir, PathBuf, PathBuf) {
        let d = tempfile::tempdir().unwrap();
        let settings = d.path().join("settings");
        let their_state = d.path().join("theirstate");
        std::fs::create_dir_all(&settings).unwrap();
        std::fs::create_dir_all(&their_state).unwrap();
        std::fs::write(settings.join("ui.json"), format!(r#"{{"remoteAccess":{on},"daemonPort":18991}}"#)).unwrap();
        if with_token {
            std::fs::write(their_state.join(TOKEN_FILE), T).unwrap();
        }
        (d, settings, their_state)
    }

    #[test]
    fn on_keeps_the_token_and_port_and_off_goes_back_to_loopback() {
        let d = tempfile::tempdir().unwrap();
        assert!(!enabled(d.path()));
        set(d.path(), true, 18990).unwrap();
        assert!(enabled(d.path()));
        let l = Launch::read(d.path()).unwrap();
        assert_eq!((l.host.as_deref(), l.port), (Some("0.0.0.0"), Some(18990)));
        let token = std::fs::read_to_string(d.path().join(TOKEN_FILE)).unwrap();
        assert!(token.trim().len() >= 32);
        set(d.path(), false, 18990).unwrap();
        assert!(!enabled(d.path()));
        let l = Launch::read(d.path()).unwrap();
        assert_eq!((l.host, l.token_file, l.port), (None, None, Some(18990)));
        // On again: the same token (paired phones stay paired).
        set(d.path(), true, 1).unwrap();
        assert_eq!(std::fs::read_to_string(d.path().join(TOKEN_FILE)).unwrap(), token);
        assert_eq!(Launch::read(d.path()).unwrap().port, Some(18990));
    }

    #[test]
    fn the_token_is_shown_only_on_reveal_and_only_when_on() {
        let d = tempfile::tempdir().unwrap();
        let ifaces = vec![("Ethernet".to_string(), "192.168.1.20".parse().unwrap()), ("Tailscale".to_string(), "100.67.51.8".parse().unwrap())];
        assert_eq!(get(d.path(), 5, &ifaces, true)["token"], Value::Null);
        set(d.path(), true, 5).unwrap();
        let hidden = get(d.path(), 5, &ifaces, false);
        assert_eq!((hidden["enabled"].as_bool(), hidden["token"].clone(), hidden["addresses"][0]["label"].as_str()), (Some(true), Value::Null, Some("Tailscale")));
        assert!(get(d.path(), 5, &ifaces, true)["token"].as_str().is_some_and(|t| t.len() >= 32));
    }

    #[test]
    fn a_standalone_on_carries_over_with_the_same_token_and_port_once() {
        let (_d, settings, their_state) = standalone(true, true);
        let state = tempfile::tempdir().unwrap();
        assert!(migrate_standalone(state.path(), Some(&settings), Some(&their_state)).unwrap());
        assert!(enabled(state.path()));
        assert_eq!(std::fs::read_to_string(state.path().join(TOKEN_FILE)).unwrap(), T);
        assert_eq!(Launch::read(state.path()).unwrap().port, Some(18991));
        // The person turns it off here: a restart does not bring it back.
        set(state.path(), false, 18991).unwrap();
        assert!(!migrate_standalone(state.path(), Some(&settings), Some(&their_state)).unwrap());
        assert!(!enabled(state.path()));
    }

    #[test]
    fn a_standalone_off_or_without_a_token_opens_nothing() {
        for (on, token) in [(false, true), (true, false)] {
            let (_d, settings, their_state) = standalone(on, token);
            let state = tempfile::tempdir().unwrap();
            assert!(!migrate_standalone(state.path(), Some(&settings), Some(&their_state)).unwrap());
            assert!(!state.path().join(LAUNCH_FILE).exists());
        }
        let state = tempfile::tempdir().unwrap();
        assert!(!migrate_standalone(state.path(), None, None).unwrap());
    }

    #[test]
    fn a_launch_file_already_here_is_never_overwritten_by_the_carry_over() {
        let (_d, settings, their_state) = standalone(true, true);
        let state = tempfile::tempdir().unwrap();
        std::fs::write(state.path().join(LAUNCH_FILE), r#"{"port": 7}"#).unwrap();
        assert!(!migrate_standalone(state.path(), Some(&settings), Some(&their_state)).unwrap());
        assert!(!enabled(state.path()));
    }
}
