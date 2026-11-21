// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The Installer's store (INSTALLER-1). Every operation is the daemon's owner-only `plugin_admin` frame, `{"args": [op, ...]}`,
// answered by a `plugin_admin` frame `{code, out, err}` for the same request id: `available` (what the sources offer and
// what is installed, as JSON in `out`), `install <id>...` (each module and what it requires, in dependency order),
// `uninstall <id>`, `enable <id>` and `disable <id>`. An install or uninstall is live: the daemon loads or unloads the
// plugin and tells every window (`plugins_changed`), so nothing restarts.
import { createStore, type StoreApi } from "zustand/vanilla";
import { useStore } from "zustand";
import { json, type Envelope, type FrameLink } from "@infantry/ui-plugin-api";

/** One module as `plugin_admin available` reports it. */
export interface PluginRow {
  id: string;
  displayName: string;
  description: string;
  version: string;
  tier: string;
  requires: string[];
  /** The plugin the daemon starts with when nothing else is installed (this one). */
  bootstrap: boolean;
  daemon: boolean;
  ui: boolean;
  /** The source folder that offers it, or null when only an installed copy exists. */
  source: string | null;
  installed: boolean;
  enabled: boolean;
  /** APPS-ROOT-1: set when this module is an app: the plugins that come with it (`core`) and the ones it offers (`optional`). */
  app?: { core: string[]; optional: string[] } | null;
  /** The apps this module belongs to, and whether it is a core or an optional member of each. */
  memberOf?: { app: string; kind: "core" | "optional" }[];
  /** `available`, `loaded`, `ui only`, `disabled`, `refused: ...`, `failed: ...`. */
  status: string;
}

export interface InstallerState {
  rows: PluginRow[];
  loaded: boolean;
  /** What is running now, e.g. `install Infantry_Board`; null when idle. */
  busy: string | null;
  refusal: string | null;
  /** The last answer that worked, e.g. `installed Infantry_Board`. */
  notice: string | null;
}

export interface InstallerActions {
  refresh(): void;
  install(ids: string[]): void;
  uninstall(id: string): void;
  setEnabled(id: string, on: boolean): void;
  clearRefusal(): void;
  dispose(): void;
}

export type InstallerStore = StoreApi<InstallerState & InstallerActions>;

interface Answer {
  code: number;
  out: string;
  err: string;
}

/** What a row can be asked to do, from its state alone. */
export function rowActions(r: PluginRow): { install: boolean; uninstall: boolean; toggle: boolean } {
  return { install: !r.installed && r.source !== null, uninstall: r.installed && !r.bootstrap, toggle: r.installed && !r.bootstrap };
}

/** APPS-ROOT-1: what the Apps screen lists. Apps and the system installer are shown; a member shows inside its app;
 * a plugin that belongs to no app lands in `other`, so nothing becomes unreachable. */
export interface AppGroups {
  system: PluginRow[];
  apps: PluginRow[];
  other: PluginRow[];
}

export function groupRows(rows: PluginRow[]): AppGroups {
  const isApp = (r: PluginRow) => !!r.app;
  return {
    system: rows.filter((r) => r.bootstrap && !isApp(r)),
    apps: rows.filter(isApp),
    other: rows.filter((r) => !r.bootstrap && !isApp(r) && (r.memberOf ?? []).length === 0),
  };
}

/** The members of an app row, resolved against the list: core ones and optional ones (an id no source offers is left out). */
export function membersOf(app: PluginRow, rows: PluginRow[]): { core: PluginRow[]; optional: PluginRow[] } {
  const find = (ids: string[]) => ids.map((id) => rows.find((r) => r.id === id)).filter((r): r is PluginRow => !!r);
  return { core: find(app.app?.core ?? []), optional: find(app.app?.optional ?? []) };
}

/** ARMORY-2: the app id a route names (`app/<id>`, how a layout opens this view on one app's page), or null for the list. */
export function routeApp(route: string | undefined): string | null {
  const m = /^app\/(.+)$/.exec(route ?? "");
  return m ? m[1]!.trim() || null : null;
}

/** The app row a route names: an app, the system installer or a loose plugin, by module id. */
export function findApp(rows: PluginRow[], id: string): PluginRow | null {
  return rows.find((r) => r.id === id) ?? null;
}

/** Installed first, then by name; the list reads the same after every refresh. */
export function sortRows(rows: PluginRow[]): PluginRow[] {
  return [...rows].sort((a, b) => Number(b.installed) - Number(a.installed) || (a.displayName || a.id).localeCompare(b.displayName || b.id));
}

export function createInstallerStore(link: FrameLink): InstallerStore {
  return createStore<InstallerState & InstallerActions>()((set, get) => {
    let counter = 0;
    const pending = new Map<string, string>();
    const send = (op: string, args: string[], label: string | null): void => {
      counter += 1;
      const requestId = `inst:${Date.now().toString(36)}-${counter.toString(36)}`;
      pending.set(requestId, op);
      if (!link.sendFrame({ type: "plugin_admin", data: JSON.stringify({ args: [op, ...args] }), requestId })) {
        pending.delete(requestId);
        set({ refusal: "plugin_admin not sent: the daemon link is down", busy: null });
        return;
      }
      if (label) set({ busy: label, refusal: null, notice: null });
    };
    const off = link.onFrame((env: Envelope) => {
      switch (env.type) {
        case "ready":
          if (get().loaded) get().refresh();
          return;
        case "plugins_changed":
          // Another window (or a script) installed or removed something: read the list again.
          if (get().loaded && get().busy === null) get().refresh();
          return;
        case "plugin_admin": {
          const op = env.requestId ? pending.get(env.requestId) : undefined;
          if (!op || !env.requestId) return;
          pending.delete(env.requestId);
          const a = json<Answer>(env.data);
          if (!a) return set({ busy: null, refusal: "plugin_admin answered something that is not JSON" });
          if (op === "available") {
            const rows = a.code === 0 ? json<PluginRow[]>(a.out) : null;
            return set(Array.isArray(rows) ? { rows: sortRows(rows), loaded: true } : { loaded: true, refusal: a.err || "the plugin list could not be read" });
          }
          if (a.code !== 0) set({ busy: null, refusal: a.err || `${op} failed` });
          else set({ busy: null, notice: a.out });
          get().refresh();
          return;
        }
        case "error": {
          const op = env.requestId ? pending.get(env.requestId) : undefined;
          if (!op || !env.requestId) return;
          pending.delete(env.requestId);
          set({ busy: null, loaded: true, refusal: env.data });
        }
      }
    });
    return {
      rows: [],
      loaded: false,
      busy: null,
      refusal: null,
      notice: null,
      refresh: () => send("available", [], null),
      install: (ids) => ids.length > 0 && send("install", ids, `Installing ${ids.length === 1 ? ids[0] : `${ids.length} plugins`}`),
      uninstall: (id) => send("uninstall", [id], `Removing ${id}`),
      setEnabled: (id, on) => send(on ? "enable" : "disable", [id], `${on ? "Enabling" : "Disabling"} ${id}`),
      clearRefusal: () => set({ refusal: null }),
      dispose: off,
    };
  });
}

// The plugin's one store, made when the app activates it (index.tsx) and dropped when the plugin unloads.
let current: InstallerStore | null = null;

export function bindInstallerStore(s: InstallerStore | null): void {
  current = s;
}

export function installerStore(): InstallerStore {
  if (!current) throw new Error("Infantry_Installer: the store is used before the plugin is activated");
  return current;
}

export const useInstaller = <T,>(select: (s: InstallerState & InstallerActions) => T): T => useStore(installerStore(), select);
