// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The bootstrap store (INSTALLER-1): what the daemon's `boot_progress` report says about its bootstrap plugin. The daemon
// names it (a setting, never the app) and says `active` while nothing but that plugin is installed; the shell then draws
// that plugin's screen instead of the app, and goes back to the app by itself when the report turns inactive (an install
// ended bootstrap mode). A daemon that reports no bootstrap, or none at all, leaves the app as it always was.
import { createStore, type StoreApi } from "zustand/vanilla";
import { json, type Envelope } from "../wire";
import type { FrameLink } from "./link";

export interface BootstrapInfo {
  /** The bootstrap plugin's module id, when the daemon has one. */
  plugin: string | null;
  /** True while nothing but that plugin is installed. */
  active: boolean;
  /** Why it could not start, while that is the case. */
  error: string | null;
}

export interface BootstrapState {
  /** Null until the daemon has answered (or when it does not report one). */
  info: BootstrapInfo | null;
}

export interface BootstrapActions {
  refresh(): void;
  dispose(): void;
}

export type BootstrapStore = StoreApi<BootstrapState & BootstrapActions>;

/** The bootstrap part of a `boot_progress` report, or null when the report has none. */
export function bootstrapOf(data: string): BootstrapInfo | null {
  const b = json<{ bootstrap?: { plugin?: unknown; active?: unknown; error?: unknown } }>(data)?.bootstrap;
  if (!b || typeof b !== "object") return null;
  return { plugin: typeof b.plugin === "string" ? b.plugin : null, active: b.active === true, error: typeof b.error === "string" && b.error ? b.error : null };
}

export function createBootstrapStore(link: FrameLink): BootstrapStore {
  return createStore<BootstrapState & BootstrapActions>()((set) => {
    const off = link.onFrame((env: Envelope) => {
      if (env.type !== "boot_progress") return;
      const info = bootstrapOf(env.data);
      if (info) set({ info });
    });
    return {
      info: null,
      refresh: () => void link.sendFrame({ type: "boot_progress", data: "{}", requestId: "bootstrap:1" }),
      dispose: off,
    };
  });
}
