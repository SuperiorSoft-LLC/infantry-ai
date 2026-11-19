// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! RUST-UPD-1: pairing a phone with this desktop's daemon (the other half of RUST-MOBILE-1's connect screen).
//!
//! Off by default: the sidecar listens on loopback and needs no token. "Allow phone connections" (`ui.json`
//! `remoteAccess`) restarts it (stopped by PID) on every interface with a bearer token kept in the daemon state dir
//! (`remote-access.token`, made from the OS RNG). Settings > Daemon then shows the `infantry://connect?url=..&token=..`
//! link and its QR code for an address this machine has (Tailscale first); the token is sent to the page only when the
//! user presses reveal.

use crate::desktop::AppState;
use serde::Serialize;
use std::net::{IpAddr, Ipv4Addr};
use tauri::State;

#[derive(Serialize, Debug, PartialEq)]
pub struct Address {
    pub address: String,
    /// `Tailscale`, `Local network` or the interface name.
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

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PairingView {
    /// Daemon mode (this app runs the sidecar); else pairing is the daemon owner's job.
    available: bool,
    enabled: bool,
    port: Option<u16>,
    addresses: Vec<Address>,
    host_name: Option<String>,
    /// Only when asked (`reveal`) and enabled.
    token: Option<String>,
}

#[tauri::command]
pub fn pairing_get(state: State<'_, AppState>, reveal: bool) -> PairingView {
    let enabled = state.settings.lock().unwrap().remote_access.unwrap_or(false);
    let ifaces: Vec<(String, IpAddr)> = if_addrs::get_if_addrs().map(|v| v.into_iter().map(|i| (i.name.clone(), i.ip())).collect()).unwrap_or_default();
    let token = (reveal && enabled).then(|| state.remote_token()).flatten();
    PairingView {
        available: state.daemon_state.is_some(),
        enabled,
        port: state.sidecar_port(),
        addresses: reachable(&ifaces),
        host_name: crate::env("HOST_NAME").or_else(|| std::env::var("COMPUTERNAME").ok()),
        token,
    }
}

/// Turns phone access on or off: saves it and restarts the sidecar with the matching bind and token.
#[tauri::command]
pub async fn pairing_set(state: State<'_, AppState>, enabled: bool) -> Result<(), String> {
    if state.daemon_state.is_none() {
        return Err("phone access is set where the daemon runs; this window connects to another daemon".into());
    }
    state.update(|s| s.remote_access = Some(enabled))?;
    state.restart_daemon()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tailscale_comes_first_and_loopback_and_link_local_are_left_out() {
        let ifaces = vec![
            ("Ethernet".to_string(), "192.168.1.20".parse().unwrap()),
            ("Loopback".to_string(), "127.0.0.1".parse().unwrap()),
            ("Wi-Fi".to_string(), "169.254.3.4".parse().unwrap()),
            ("Tailscale".to_string(), "100.67.51.8".parse().unwrap()),
            ("v6".to_string(), "fe80::1".parse().unwrap()),
        ];
        let got = reachable(&ifaces);
        assert_eq!(got.iter().map(|a| a.address.as_str()).collect::<Vec<_>>(), ["100.67.51.8", "192.168.1.20"]);
        assert_eq!(got[0].label, "Tailscale");
        assert_eq!(got[1].label, "Local network");
    }
}
