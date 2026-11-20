// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The running app's version for About and the status bar: the Tauri app's own version, or "web client" in a browser.
import { useSyncExternalStore } from "react";
import { host } from "@/host";

let version: string | null = null;
const listeners = new Set<() => void>();

void host
  .version()
  .then((v) => {
    version = v;
    listeners.forEach((l) => l());
  })
  .catch(() => {});

/** "version 0.2.0 · desktop" once known; the web client says so. */
export function appVersionLine(): string {
  return host.kind === "browser" ? "web client" : version ? `version ${version} · desktop` : "desktop";
}

/** Re-renders when the version arrives. */
export function useAppVersionLine(): string {
  return useSyncExternalStore(
    (l) => (listeners.add(l), () => listeners.delete(l)),
    appVersionLine,
  );
}
