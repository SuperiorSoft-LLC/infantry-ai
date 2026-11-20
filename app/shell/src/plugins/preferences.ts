// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// DESK-2: per-device preferences (a string or a boolean) in this window's localStorage, with a change event so every reader
// re-renders at once, the shell and a layout plugin alike. They are the UI's own choices on THIS device (which layout, whether
// to open the orchestrator at start); a phone and a desktop may choose differently. Nothing here reaches the daemon.
import { useCallback, useSyncExternalStore } from "react";

const PREFIX = "infantry.pref.";
const EVENT = "infantry:pref";

type Pref = string | boolean;

export function readPref<T extends Pref>(key: string, fallback: T): T {
  try {
    const raw = globalThis.localStorage?.getItem(PREFIX + key);
    if (raw === null || raw === undefined) return fallback;
    const v = JSON.parse(raw) as unknown;
    return typeof v === typeof fallback ? (v as T) : fallback;
  } catch {
    return fallback;
  }
}

export function writePref(key: string, value: Pref): void {
  try {
    globalThis.localStorage?.setItem(PREFIX + key, JSON.stringify(value));
  } catch {
    /* storage refused (private mode, full): the choice lasts until reload through the event below */
  }
  globalThis.dispatchEvent?.(new Event(EVENT));
}

function subscribe(listener: () => void): () => void {
  globalThis.addEventListener?.(EVENT, listener);
  globalThis.addEventListener?.("storage", listener);
  return () => {
    globalThis.removeEventListener?.(EVENT, listener);
    globalThis.removeEventListener?.("storage", listener);
  };
}

/** `[value, set]` for preference [key]; re-renders when it changes in this window or another one. */
export function usePreference<T extends Pref>(key: string, fallback: T): readonly [T, (next: T) => void] {
  const value = useSyncExternalStore(subscribe, () => readPref(key, fallback));
  const set = useCallback((next: T) => writePref(key, next), [key]);
  return [value, set] as const;
}
