// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The settings store: the daemon's declared config (CONFIG-8). `config_get` is answered by `config_schema` (the cards
// and their fields) then `config_values` (the overrides); `config_set` writes one key and every write is pushed back as
// `config_changed`. A write shows at once and is rolled back when the daemon refuses it.
import { createStore, type StoreApi } from "zustand/vanilla";
import { json, type Envelope } from "../wire";

export interface ConfigField {
  key: string;
  type: string;
  label?: string;
  default?: unknown;
  options?: string[];
  min?: number | null;
  max?: number | null;
  step?: number | null;
  optionLabels?: Record<string, string>;
  description?: string | null;
  order?: number;
  scope?: string | null;
  required?: boolean;
  pattern?: string | null;
  patternMessage?: string | null;
}

export interface ConfigGroup {
  node: string;
  group?: string;
  fields?: ConfigField[];
  tab?: string | null;
  order?: number;
  title?: string | null;
  applies?: string | null;
  keywords?: string[];
  block?: string | null;
  locked?: boolean;
  readOnly?: boolean;
}

interface ConfigRow {
  nodeId: string;
  key: string;
  value: unknown;
  setBy?: string;
  updatedAt?: number;
}

export interface SettingsLink {
  sendFrame(env: Envelope): boolean;
  onFrame(listener: (env: Envelope) => void): () => void;
}

export interface SettingsState {
  groups: ConfigGroup[];
  /** Overrides by `settingKey(node, key)`; a key never set is absent (resolve the default). */
  values: Record<string, unknown>;
  rev: number;
  loaded: boolean;
  refusal: string | null;
  inflight: Record<string, { cell: string; before: unknown; had: boolean }>;
}

export interface SettingsActions {
  ensureLoaded(): void;
  /** Write one key; `null` resets it to the declared default. */
  set(node: string, key: string, value: unknown): void;
  clearRefusal(): void;
  dispose(): void;
}

export type SettingsStore = StoreApi<SettingsState & SettingsActions>;

export const settingKey = (node: string, key: string): string => `${node}\u0000${key}`;

/** The value in effect: the override, else the field's declared default. */
export function resolved(values: Record<string, unknown>, node: string, f: ConfigField): unknown {
  const k = settingKey(node, f.key);
  return k in values ? values[k] : f.default;
}

/** Groups matching [query] on title, node, keywords, field labels and keys; all of them for a blank query. */
export function searchGroups(groups: ConfigGroup[], query: string): ConfigGroup[] {
  const q = query.trim().toLowerCase();
  if (!q) return groups;
  return groups.filter((g) => {
    const hay = [g.title, g.group, g.node, g.applies, ...(g.keywords ?? []), ...(g.fields ?? []).flatMap((f) => [f.label, f.key, f.description])];
    return hay.some((h) => h && h.toLowerCase().includes(q));
  });
}

/** Group by tab (null = "General"), tabs and cards in declared order. */
export function byTab(groups: ConfigGroup[]): { tab: string; groups: ConfigGroup[] }[] {
  const tabs = new Map<string, ConfigGroup[]>();
  groups.forEach((g) => {
    const t = g.tab ?? "General";
    tabs.set(t, [...(tabs.get(t) ?? []), g]);
  });
  return [...tabs].map(([tab, gs]) => ({ tab, groups: gs.sort((a, b) => (a.order ?? 0) - (b.order ?? 0)) }));
}

export function createSettingsStore(link: SettingsLink): SettingsStore {
  let counter = 0;
  return createStore<SettingsState & SettingsActions>()((set, get) => {
    const write = (cell: string, value: unknown, absent: boolean): void => {
      const values = { ...get().values };
      if (absent) delete values[cell];
      else values[cell] = value;
      set({ values });
    };
    const rollback = (requestId: string, why: string): void => {
      const f = get().inflight[requestId];
      const rest = { ...get().inflight };
      delete rest[requestId];
      set({ inflight: rest, refusal: why });
      if (f) write(f.cell, f.before, !f.had);
    };
    const settle = (requestId: string): void => {
      const rest = { ...get().inflight };
      delete rest[requestId];
      set({ inflight: rest });
    };

    const off = link.onFrame((env) => {
      switch (env.type) {
        case "ready":
          // A new connection (or a restarted daemon): ask again, the schema may have changed.
          link.sendFrame({ type: "config_get", data: "" });
          return;
        case "config_schema": {
          const p = json<{ rev?: number; declarations?: ConfigGroup[] }>(env.data);
          if (p) set({ groups: p.declarations ?? [], rev: p.rev ?? get().rev, loaded: true });
          return;
        }
        case "config_values": {
          const p = json<{ rev?: number; rows?: ConfigRow[] }>(env.data);
          if (!p) return;
          set({ values: Object.fromEntries((p.rows ?? []).map((r) => [settingKey(r.nodeId, r.key), r.value])), rev: p.rev ?? get().rev });
          if (env.requestId && env.requestId in get().inflight) settle(env.requestId);
          return;
        }
        case "config_changed": {
          const p = json<{ node: string; key: string; value: unknown; rev?: number }>(env.data);
          if (!p) return;
          write(settingKey(p.node, p.key), p.value, p.value === null || p.value === undefined);
          set({ rev: p.rev ?? get().rev });
          for (const [id, f] of Object.entries(get().inflight)) if (f.cell === settingKey(p.node, p.key)) settle(id);
          return;
        }
        case "error": {
          if (env.requestId && env.requestId in get().inflight) return rollback(env.requestId, env.data);
          // The daemon may not echo the id on a config refusal: it belongs to the oldest write in flight.
          if (/^config_set (refused|failed)/.test(env.data)) {
            const first = Object.keys(get().inflight)[0];
            if (first) rollback(first, env.data);
          }
        }
      }
    });

    return {
      groups: [],
      values: {},
      rev: 0,
      loaded: false,
      refusal: null,
      inflight: {},

      ensureLoaded() {
        if (!get().loaded) link.sendFrame({ type: "config_get", data: "" });
      },
      set(node, key, value) {
        const cell = settingKey(node, key);
        const had = cell in get().values;
        counter += 1;
        const requestId = `config:${Date.now().toString(36)}-${counter.toString(36)}`;
        set({ inflight: { ...get().inflight, [requestId]: { cell, before: get().values[cell], had } } });
        write(cell, value, value === null);
        const data = JSON.stringify({ node, key, value });
        if (!link.sendFrame({ type: "config_set", data, requestId })) rollback(requestId, "config_set not sent: the daemon link is down");
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
