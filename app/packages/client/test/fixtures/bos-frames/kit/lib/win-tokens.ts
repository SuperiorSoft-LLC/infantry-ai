// SPDX-License-Identifier: Apache-2.0
// VENDORED with frames.ts (BattalionOS babe8ec, ui/kit/lib/win-tokens.ts). Do not edit.
// Copyright (c) SuperiorSoft LLC
// OS-WINDOW-1 §3 (WM-4): the window chrome's theme tokens. The OS owns them: it pushes the current set to every app frame
// as `{bos:1, event:"os_theme", tokens}` at start and on a light/dark change. The defaults derive from the OS bar
// (TitleBar.tsx: background, a thin border, accent on hover, danger on close), scaled to a 32 px title.
// No DOM imports and no shell imports: this file ships as part of `@battalion/app-sdk/chrome`.

export const WIN_TOKENS = [
  "--bos-win-bg",
  "--bos-win-fg",
  "--bos-win-border",
  "--bos-win-radius",
  "--bos-win-shadow",
  "--bos-win-title-h",
  "--bos-win-title-bg",
  "--bos-win-title-bg-inactive",
  "--bos-win-btn-hover",
  "--bos-win-btn-danger",
  "--bos-win-focus-ring",
  "--bos-win-snap-preview",
] as const;
export type WinToken = (typeof WIN_TOKENS)[number];
export type WinTokens = Record<WinToken, string>;

/** Light defaults: the kit's light palette (styles.css :root). */
export const WIN_TOKENS_LIGHT: WinTokens = {
  "--bos-win-bg": "#f4f5f7",
  "--bos-win-fg": "#1a1c20",
  "--bos-win-border": "#d3d7de",
  "--bos-win-radius": "8px",
  "--bos-win-shadow": "0 8px 24px rgba(0, 0, 0, 0.18)",
  "--bos-win-title-h": "32px",
  "--bos-win-title-bg": "#f4f5f7",
  "--bos-win-title-bg-inactive": "#e9ebef",
  "--bos-win-btn-hover": "#e9ebef",
  "--bos-win-btn-danger": "#dc2626",
  "--bos-win-focus-ring": "#b45309",
  "--bos-win-snap-preview": "rgba(180, 83, 9, 0.18)",
};

/** Dark defaults: the kit's dark palette (styles.css .dark), the OS default. */
export const WIN_TOKENS_DARK: WinTokens = {
  "--bos-win-bg": "#0e0f12",
  "--bos-win-fg": "#e6e7ea",
  "--bos-win-border": "#262a31",
  "--bos-win-radius": "8px",
  "--bos-win-shadow": "0 8px 24px rgba(0, 0, 0, 0.5)",
  "--bos-win-title-h": "32px",
  "--bos-win-title-bg": "#0e0f12",
  "--bos-win-title-bg-inactive": "#16181c",
  "--bos-win-btn-hover": "#262a31",
  "--bos-win-btn-danger": "#ef4444",
  "--bos-win-focus-ring": "#f59e0b",
  "--bos-win-snap-preview": "rgba(245, 158, 11, 0.18)",
};

export const winTokens = (dark: boolean): WinTokens => ({ ...(dark ? WIN_TOKENS_DARK : WIN_TOKENS_LIGHT) });

export interface OsThemeEvent {
  bos: 1;
  event: "os_theme";
  tokens: WinTokens;
}
export const osThemeEvent = (tokens: WinTokens): OsThemeEvent => ({ bos: 1, event: "os_theme", tokens: { ...tokens } });

/** Read an `os_theme` message (anything else, or a token set with unknown or missing names, is null). */
export function parseOsTheme(data: unknown): WinTokens | null {
  const d = data as Partial<OsThemeEvent> | null;
  if (!d || d.bos !== 1 || d.event !== "os_theme" || typeof d.tokens !== "object" || d.tokens == null) return null;
  const out: Partial<WinTokens> = {};
  for (const k of WIN_TOKENS) {
    const v = (d.tokens as Record<string, unknown>)[k];
    if (typeof v !== "string") return null;
    out[k] = v;
  }
  return out as WinTokens;
}

/** Put a token set on an element (an app's root, usually document.documentElement). */
export function applyWinTokens(el: { style: { setProperty(name: string, value: string): void } }, tokens: WinTokens): void {
  for (const k of WIN_TOKENS) el.style.setProperty(k, tokens[k]);
}
