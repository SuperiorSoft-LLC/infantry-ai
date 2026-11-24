// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// BOS-8: the pure half of the battalion host (no Tauri import), tested without a window.
import type { HostEndpoint } from "@infantry/client";

/** `os_app_status` (docs/commands.md): state, and the url the app's server listens on. */
export interface OsAppStatus {
  id: string;
  state: string;
  url: string | null;
  lastError?: string | null;
}

/** An OS app id: `[a-z0-9-]{2,63}`. */
const APP_ID = /^[a-z0-9-]{2,63}$/;

/** The id of window label `app-<id>`; null for `main`, `login` and anything else. */
export function appIdOfLabel(label: string | null | undefined): string | null {
  if (!label?.startsWith("app-")) return null;
  const id = label.slice(4);
  return APP_ID.test(id) ? id : null;
}

/** The daemon the OS runs for this app: its http url as the WebSocket. Throws a readable reason while it is not running. */
export function endpointOf(st: OsAppStatus): HostEndpoint {
  if (!st.url) throw new Error(st.lastError ? `The app is not running: ${st.lastError}` : `The app is not running (state: ${st.state}).`);
  return { url: `${st.url.replace(/^http/, "ws").replace(/\/+$/, "")}/ws`, token: null, stress: false, mode: "daemon" };
}

/** The number on the app's icon (`os_badge {count}`): every agent that is working or waits for the owner; 0 clears it. */
export const badgeCount = (working: number, needsInput: number): number => Math.max(0, Math.trunc(working) || 0) + Math.max(0, Math.trunc(needsInput) || 0);
