// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Pairing with a remote daemon (RUST-MOBILE-1), the same link the Kotlin clients read (PAIR-1, PairingLink.kt):
//   infantry://connect?url=<ws(s)://host:port/ws>&token=<bearer token>&name=<display name>
// A QR code carries that text; a pasted link is the same. The token is a credential: an error never repeats the input.

export interface Pairing {
  url: string;
  token: string | null;
  name: string | null;
}

export type PairingParse = { ok: true; pairing: Pairing } | { ok: false; reason: string };

const PREFIX = "infantry://connect?";

/** A daemon address as its socket URL (the same rule as the Tauri shell's `ws_url`). */
export function socketUrl(raw: string): string {
  let u = raw.trim().replace(/\/+$/, "");
  if (u.startsWith("http://")) u = `ws://${u.slice(7)}`;
  else if (u.startsWith("https://")) u = `wss://${u.slice(8)}`;
  else if (!u.startsWith("ws://") && !u.startsWith("wss://")) u = `ws://${u}`;
  const rest = u.slice(u.indexOf("://") + 3);
  return rest.includes("/") ? u : `${u}/ws`;
}

export function parsePairingLink(text: string): PairingParse {
  const t = text.trim();
  if (!t.toLowerCase().startsWith(PREFIX)) return { ok: false, reason: "not an infantry://connect link" };
  let q: URLSearchParams;
  try {
    q = new URLSearchParams(t.slice(PREFIX.length));
  } catch {
    return { ok: false, reason: "the link could not be read" };
  }
  const url = q.get("url")?.trim();
  if (!url) return { ok: false, reason: "the link carries no daemon address" };
  if (!/^(wss?|https?):\/\/[^\s/]+/i.test(url)) return { ok: false, reason: "the daemon address is not a ws://, wss:// or http(s):// URL" };
  const token = q.get("token")?.trim() || null;
  const name = q.get("name")?.trim() || null;
  return { ok: true, pairing: { url: socketUrl(url), token, name } };
}

export function pairingLink(p: Pairing): string {
  const parts = [`url=${encodeURIComponent(p.url)}`];
  if (p.token) parts.push(`token=${encodeURIComponent(p.token)}`);
  if (p.name) parts.push(`name=${encodeURIComponent(p.name)}`);
  return PREFIX + parts.join("&");
}
