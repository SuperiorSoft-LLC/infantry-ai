// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The window-wide keyboard shortcuts (the Kotlin App.kt key handler): which action one key press asks for. Pure; the
// shell runs the action. Ctrl and Cmd are the same key here.

export type Shortcut =
  | { kind: "search" } // Ctrl+K: focus the agent search
  | { kind: "newAgent" } // Ctrl+N
  | { kind: "settings" } // Ctrl+,
  | { kind: "resume" } // Ctrl+R
  | { kind: "composer" } // Ctrl+/
  | { kind: "nextNeedsInput" } // Ctrl+J
  | { kind: "agent"; step: 1 | -1 } // Alt+Down / Alt+Up
  | { kind: "tab"; digit: string }; // Ctrl+0..9: the tab whose hint is ^<digit> (^8 = the Board)

export interface KeyPress {
  key: string;
  ctrlKey: boolean;
  metaKey: boolean;
  altKey: boolean;
  shiftKey: boolean;
}

export function shortcutOf(e: KeyPress): Shortcut | null {
  const mod = e.ctrlKey || e.metaKey;
  if (e.altKey && !mod && !e.shiftKey && (e.key === "ArrowDown" || e.key === "ArrowUp")) return { kind: "agent", step: e.key === "ArrowDown" ? 1 : -1 };
  if (!mod || e.altKey || e.shiftKey) return null;
  const k = e.key.length === 1 ? e.key.toLowerCase() : e.key;
  if (/^[0-9]$/.test(k)) return { kind: "tab", digit: k };
  switch (k) {
    case "k":
      return { kind: "search" };
    case "n":
      return { kind: "newAgent" };
    case ",":
      return { kind: "settings" };
    case "r":
      return { kind: "resume" };
    case "/":
      return { kind: "composer" };
    case "j":
      return { kind: "nextNeedsInput" };
    default:
      return null;
  }
}

/** The id after [current] stepping [step] through [ids], wrapping; the first (or last) when [current] is not there. */
export function stepThrough(ids: string[], current: string, step: 1 | -1): string | null {
  if (!ids.length) return null;
  const at = ids.indexOf(current);
  if (at < 0) return step === 1 ? ids[0] : ids[ids.length - 1];
  return ids[(at + step + ids.length) % ids.length];
}

/** The shortcut hints the status bar shows (only keys that work). */
export const SHORTCUT_HINTS = ["^K search", "Alt+Up/Down agents", "^J needs input", "^N new", "^R resume"];

/** Where the daemon runs, from the link's URL: "This computer" for a loopback address, else its host name. */
export function hostLabel(url: string): string {
  let host: string;
  try {
    host = new URL(url, "http://localhost").hostname;
  } catch {
    return "";
  }
  return /^(localhost|127\.\d+\.\d+\.\d+|\[?::1\]?)$/.test(host) ? "This computer" : host;
}
