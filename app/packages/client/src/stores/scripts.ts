// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The managed script library: the scripts of the current project plus the global ones (title, description, tags, run stats,
// the hooks that use each), one open script with its body and version history, and the result of a run or a file
// transfer. The daemon owns the data (`scripts_*` frames); every write is answered with the stored row and announced to
// all clients with `scripts_changed`, which refreshes the list.
import { createStore, type StoreApi } from "zustand/vanilla";
import { Frame, json } from "../wire";
import { requester, type FrameLink } from "./link";

export interface ScriptStats {
  runs: number;
  failures: number;
  avgMs: number | null;
  lastRunAt: number | null;
  lastOk: boolean | null;
  lastError: string | null;
}

export interface ScriptHookRef {
  id: string;
  on: string;
  enabled: boolean;
  scope: "project" | "global" | "plugin";
  everyProject?: boolean;
}

export type ScriptStatus = "ok" | "failing" | "never-run" | "disabled";

export interface ScriptRow {
  id: string;
  scope: "project" | "global" | "plugin";
  projectId: string;
  name: string;
  ext: string;
  title: string;
  description: string;
  tags: string[];
  author: string;
  createdAt: number;
  updatedAt: number;
  enabled: boolean;
  version: number;
  /** A note for the owner: a likely duplicate or versioned copy. */
  flag: string | null;
  size: number;
  stats: ScriptStats;
  status: ScriptStatus;
  hooks: ScriptHookRef[];
}

export interface ScriptVersion {
  version: number;
  title: string;
  description: string;
  author: string;
  savedAt: number;
  size: number;
  current: boolean;
}

export interface ScriptDetail extends ScriptRow {
  body: string;
  versions: ScriptVersion[];
}

export interface ScriptSave {
  /** Set to edit a script; a new one names `name` and `scope`. */
  id?: string;
  name?: string;
  scope?: "project" | "global";
  title: string;
  description: string;
  body: string;
  tags: string[];
}

export interface ScriptResult {
  ok: boolean;
  text: string;
  request: string;
}

export type ScriptFilter = "all" | "failing" | "unused" | "disabled" | "flagged" | "hooked";
export type ScriptSort = "name" | "used" | "recent" | "failing" | "unused";

export interface ScriptsState {
  projectId: string | null;
  scripts: ScriptRow[];
  loaded: boolean;
  detail: ScriptDetail | null;
  result: ScriptResult | null;
  refusal: string | null;
}

export interface ScriptsActions {
  setProject(projectId: string | null): void;
  ensureLoaded(): void;
  refresh(): void;
  open(id: string): void;
  close(): void;
  save(input: ScriptSave): void;
  remove(id: string): void;
  setEnabled(id: string, enabled: boolean): void;
  restore(id: string, version: number): void;
  run(name: string, args: string, dryRun: boolean): void;
  addHook(scriptId: string, on: string, everyProject?: boolean): void;
  removeHook(hookId: string, everyProject?: boolean): void;
  setHookEnabled(hookId: string, enabled: boolean, everyProject?: boolean): void;
  files(action: "export" | "import", scope: "project" | "global", dir?: string): void;
  clearResult(): void;
  clearRefusal(): void;
  dispose(): void;
}

export type ScriptsStore = StoreApi<ScriptsState & ScriptsActions>;

export function isUnused(r: ScriptRow): boolean {
  return r.stats.runs === 0 && r.hooks.length === 0;
}

/** Search (name, title, description, tags), filter and sort. Pure, so the view and the tests share it. */
export function filterScripts(rows: ScriptRow[], opts: { query?: string; filter?: ScriptFilter; sort?: ScriptSort; tag?: string | null }): ScriptRow[] {
  const q = (opts.query ?? "").trim().toLowerCase();
  const out = rows.filter((r) => {
    if (q && ![r.name, r.title, r.description, ...r.tags].some((t) => t.toLowerCase().includes(q))) return false;
    if (opts.tag && !r.tags.includes(opts.tag)) return false;
    switch (opts.filter ?? "all") {
      case "failing":
        return r.status === "failing";
      case "unused":
        return isUnused(r);
      case "disabled":
        return !r.enabled;
      case "flagged":
        return !!r.flag;
      case "hooked":
        return r.hooks.length > 0;
      default:
        return true;
    }
  });
  const by = opts.sort ?? "name";
  const name = (a: ScriptRow, b: ScriptRow): number => a.title.localeCompare(b.title);
  return out.sort((a, b) => {
    switch (by) {
      case "used":
        return b.stats.runs - a.stats.runs || name(a, b);
      case "recent":
        return (b.stats.lastRunAt ?? 0) - (a.stats.lastRunAt ?? 0) || name(a, b);
      case "failing":
        return b.stats.failures - a.stats.failures || name(a, b);
      case "unused":
        return Number(isUnused(b)) - Number(isUnused(a)) || a.stats.runs - b.stats.runs || name(a, b);
      default:
        return name(a, b);
    }
  });
}

export function allTags(rows: ScriptRow[]): string[] {
  return [...new Set(rows.flatMap((r) => r.tags))].sort();
}

export function createScriptsStore(link: FrameLink): ScriptsStore {
  return createStore<ScriptsState & ScriptsActions>()((set, get) => {
    const req = requester(link, "scr", (why) => set({ refusal: why }));
    const project = (): string => get().projectId ?? "";
    const send = (type: string, body: object = {}): void => void req.send(type, JSON.stringify({ projectId: project(), ...body }));

    const off = link.onFrame((env) => {
      const mine = req.settle(env);
      switch (env.type) {
        case "scripts": {
          const l = json<{ projectId: string; scripts: ScriptRow[] }>(env.data);
          if (l && (l.projectId ?? "") === project()) set({ scripts: l.scripts ?? [], loaded: true });
          return;
        }
        case "scripts_item": {
          const d = json<ScriptDetail>(env.data);
          if (!d?.id) return;
          // An answer to this window's own request opens the script; one that arrives alone only updates an open one.
          if (mine || get().detail?.id === d.id) set({ detail: d });
          return;
        }
        case "scripts_result": {
          const r = json<ScriptResult>(env.data);
          if (r && mine) set({ result: r });
          return;
        }
        case "scripts_changed": {
          // Another project's change can still touch a global script, so every loaded list refreshes.
          if (get().loaded) send("scripts_list");
          const open = get().detail;
          if (open) send("scripts_get", { id: open.id });
          return;
        }
        case Frame.ERROR: {
          if (mine) set({ refusal: env.data });
          return;
        }
      }
    });

    return {
      projectId: null,
      scripts: [],
      loaded: false,
      detail: null,
      result: null,
      refusal: null,

      setProject(projectId) {
        if (get().projectId === projectId && get().loaded) return;
        set({ projectId, scripts: [], loaded: false, detail: null });
        send("scripts_list");
      },
      ensureLoaded() {
        if (!get().loaded) send("scripts_list");
      },
      refresh() {
        send("scripts_list");
      },
      open(id) {
        send("scripts_get", { id });
      },
      close() {
        set({ detail: null, result: null });
      },
      save(input) {
        send("scripts_save", input);
      },
      remove(id) {
        set({ detail: null });
        send("scripts_delete", { id });
      },
      setEnabled(id, enabled) {
        send("scripts_enable", { id, enabled });
      },
      restore(id, version) {
        send("scripts_restore", { id, version });
      },
      run(name, args, dryRun) {
        set({ result: null });
        send("scripts_run", { name, args: args.trim() || "{}", dryRun });
      },
      addHook(scriptId, on, everyProject = false) {
        send("scripts_hook", { op: "add", scriptId, on, everyProject });
      },
      removeHook(hookId, everyProject = false) {
        send("scripts_hook", { op: "remove", hookId, everyProject });
      },
      setHookEnabled(hookId, enabled, everyProject = false) {
        send("scripts_hook", { op: "enable", hookId, enabled, everyProject });
      },
      files(action, scope, dir) {
        send("scripts_files", { action, scope, ...(dir ? { dir } : {}) });
      },
      clearResult() {
        set({ result: null });
      },
      clearRefusal() {
        set({ refusal: null });
      },
      dispose() {
        off();
      },
    };
  });
}
