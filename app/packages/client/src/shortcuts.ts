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
  | { kind: "chatSearch" } // Ctrl+F: search this chat
  | { kind: "switchAgent" } // Ctrl+Shift+N: switch the selected session's provider / model
  | { kind: "endSession" } // Ctrl+Alt+K: end the selected session (graceful kill; it can be resumed)
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
  const k = e.key.length === 1 ? e.key.toLowerCase() : e.key;
  if (mod && e.shiftKey && !e.altKey && k === "n") return { kind: "switchAgent" };
  if (mod && e.altKey && !e.shiftKey && k === "k") return { kind: "endSession" };
  if (!mod || e.altKey || e.shiftKey) return null;
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
    case "f":
      return { kind: "chatSearch" };
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

/** The window event Ctrl+F raises; the open transcript shows its search bar. */
export const CHAT_SEARCH_EVENT = "infantry:chat-search";

/** What a key does on a focused prompt card of [count] choices with [at] highlighted. */
export type PromptKey = { kind: "move"; at: number } | { kind: "pick"; at: number } | { kind: "toggle"; at: number } | { kind: "cancel" } | { kind: "submit" };

/**
 * Prompt card keys (after the Kotlin approval / question cards): arrows move the highlight (wrapping), a digit 1..9
 * picks that choice, Enter picks the highlighted one (in a multi-select it sends), Space toggles it (multi-select
 * only), Esc cancels (an approval denies).
 */
export function promptKey(key: string, count: number, at: number, multi = false): PromptKey | null {
  if (count <= 0) return key === "Escape" ? { kind: "cancel" } : null;
  if (key === "ArrowRight" || key === "ArrowDown") return { kind: "move", at: (at + 1) % count };
  if (key === "ArrowLeft" || key === "ArrowUp") return { kind: "move", at: (at - 1 + count) % count };
  if (key === "Escape") return { kind: "cancel" };
  if (/^[1-9]$/.test(key) && Number(key) <= count) return multi ? { kind: "toggle", at: Number(key) - 1 } : { kind: "pick", at: Number(key) - 1 };
  if (key === " " && multi) return { kind: "toggle", at };
  if (key === "Enter") return multi ? { kind: "submit" } : { kind: "pick", at };
  return null;
}
