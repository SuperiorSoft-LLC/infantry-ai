// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The running app's version for About and the status bar: the Tauri app's own version, or "web client" in a browser.
import { useSyncExternalStore } from "react";
import { isTauri } from "@/daemon/daemon";

let version: string | null = null;
const listeners = new Set<() => void>();

if (isTauri) {
  void import("@tauri-apps/api/app")
    .then(({ getVersion }) => getVersion())
    .then((v) => {
      version = v;
      listeners.forEach((l) => l());
    })
    .catch(() => {});
}

/** "Infantry AI 0.1.0 (desktop)" once known; the web client says so. */
export function appVersionLine(): string {
  return isTauri ? (version ? `version ${version} · desktop` : "desktop") : "web client";
}

/** Re-renders when the version arrives. */
export function useAppVersionLine(): string {
  return useSyncExternalStore(
    (l) => (listeners.add(l), () => listeners.delete(l)),
    appVersionLine,
  );
}
