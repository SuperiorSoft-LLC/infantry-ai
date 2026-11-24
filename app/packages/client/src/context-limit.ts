// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// A context limit in tokens, as people type it: `250k`, `500000`, `1m`, `off`. Generic: tokens, no harness wording.

/** The range a limit may have (tokens); `off` (0) is also allowed. The daemon refuses anything else. */
export const CONTEXT_MIN = 10_000;
export const CONTEXT_MAX = 10_000_000;
/** The words shown (and the daemon answers) for a value outside the range. */
export const CONTEXT_RANGE_TEXT = "Use 10k to 10M, or off.";

/** The presets the field offers (tokens). */
export const CONTEXT_PRESETS: { label: string; tokens: number }[] = [
  { label: "250k", tokens: 250_000 },
  { label: "500k", tokens: 500_000 },
  { label: "1M", tokens: 1_000_000 },
];

/** Text to tokens: a number is tokens, `k` / `m` suffixes scale it, `off` is 0. null = blank, not a number, or outside 10k to 10M. */
export function parseContextLimit(text: string | null | undefined): number | null {
  const t = (text ?? "").trim().toLowerCase();
  if (!t) return null;
  if (t === "off" || t === "none" || t === "0") return 0;
  const m = /^(\d+(?:\.\d+)?)\s*([km]?)$/.exec(t);
  if (!m) return null;
  const n = Math.round(parseFloat(m[1]) * (m[2] === "k" ? 1_000 : m[2] === "m" ? 1_000_000 : 1));
  return n >= CONTEXT_MIN && n <= CONTEXT_MAX ? n : null;
}

/** Tokens to the short text people read: `500k`, `1M`, `off`. */
export function formatContextLimit(n: number | null | undefined): string {
  if (n == null) return "";
  if (n === 0) return "off";
  if (n >= 1_000_000 && n % 100_000 === 0) return `${n / 1_000_000}M`;
  if (n >= 1_000) return `${Math.round(n / 1_000)}k`;
  return String(n);
}
