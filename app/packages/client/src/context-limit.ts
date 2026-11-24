// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// A context limit in tokens, as people type it: `250k`, `500000`, `1m`, `off`. Generic: tokens, no harness wording.

/** The presets the field offers (tokens). */
export const CONTEXT_PRESETS: { label: string; tokens: number }[] = [
  { label: "250k", tokens: 250_000 },
  { label: "500k", tokens: 500_000 },
  { label: "1M", tokens: 1_000_000 },
];

/** Text to tokens: a number is tokens, `k` / `m` suffixes scale it, `off` is 0. null = blank or not a limit. */
export function parseContextLimit(text: string | null | undefined): number | null {
  const t = (text ?? "").trim().toLowerCase();
  if (!t) return null;
  if (t === "off" || t === "none") return 0;
  const m = /^(\d+(?:\.\d+)?)\s*([km]?)$/.exec(t);
  if (!m) return null;
  const n = Math.round(parseFloat(m[1]) * (m[2] === "k" ? 1_000 : m[2] === "m" ? 1_000_000 : 1));
  return n >= 0 && n <= 2_147_483_647 ? n : null;
}

/** Tokens to the short text people read: `500k`, `1M`, `off`. */
export function formatContextLimit(n: number | null | undefined): string {
  if (n == null) return "";
  if (n === 0) return "off";
  if (n >= 1_000_000 && n % 100_000 === 0) return `${n / 1_000_000}M`;
  if (n >= 1_000) return `${Math.round(n / 1_000)}k`;
  return String(n);
}
