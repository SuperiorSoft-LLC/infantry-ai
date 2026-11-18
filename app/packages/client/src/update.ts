// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// RUST-UPD-1: the desktop's update notice and its phone pairing link. Pure: the Tauri calls live in the shell.
import { pairingLink } from "./pairing";
import type { BusNotification } from "./stores/attention";

/** What `update_check` answers (src-tauri/src/update.rs `UpdateInfo`). */
export interface UpdateInfo {
  available: boolean;
  current: string;
  version: string | null;
  notes: string | null;
  date: string | null;
  channel: string;
  canInstall: boolean;
}

/** The "update available" notice (never an install: installing is the user's call), or null when there is none. */
export function updateNotice(u: UpdateInfo): BusNotification | null {
  if (!u.available || !u.version) return null;
  const how = u.canInstall ? "Install it from Settings > About." : "This build cannot verify updates yet (no signing key), so it cannot install them.";
  return {
    id: `app-update-${u.version}`,
    title: `Infantry AI ${u.version} is available`,
    body: `You run ${u.current} (${u.channel} channel). ${how}`,
    level: "info",
  };
}

/** The `infantry://connect` link a phone scans for this desktop's daemon (RUST-MOBILE-1's connect screen reads it). */
export function desktopPairingLink(address: string, port: number, token: string | null, name: string | null): string {
  const host = address.includes(":") ? `[${address}]` : address;
  return pairingLink({ url: `ws://${host}:${port}/ws`, token, name });
}

/** The dark modules of a QR code as one SVG path (one 1x1 square per module), for a `viewBox="0 0 n n"`. */
export function qrPath(size: number, isDark: (row: number, col: number) => boolean): string {
  let d = "";
  for (let r = 0; r < size; r++) for (let c = 0; c < size; c++) if (isDark(r, c)) d += `M${c} ${r}h1v1h-1z`;
  return d;
}
