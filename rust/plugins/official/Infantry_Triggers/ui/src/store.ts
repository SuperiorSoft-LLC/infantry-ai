// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The Triggers store (RUST-UI-5, contract 1.14.0+; moved out of the app into this plugin's web UI half by WEBUI-1):
// `trigger_list` -> `triggers`; a change (`trigger_save`, `_arm`, `_disable`, `_fire`) is pushed to every client as
// `trigger`, a delete as `triggers`; `trigger_events` "<id>" answers `trigger_events` with that trigger's event rows. A
// trigger with an `owner` belongs to a module and is read-only here.
import { createStore, type StoreApi } from "zustand/vanilla";
import { useStore } from "zustand";
import { json, requester, type Envelope, type FrameLink } from "@infantry/ui-plugin-api";

export interface TriggerAction {
  kind: string;
  agentId?: string | null;
  prompt?: string | null;
}

export interface TriggerView {
  id: string;
  name: string;
  kind: string;
  action: TriggerAction;
  enabled: boolean;
  /** `armed`, `running`, `tripped` or `disabled`. */
  state: string;
  coalesceMs: number;
  ceilingRuns: number;
  ceilingWindowMs: number;
  createdAt: number;
  trippedAt?: number | null;
  trippedReason?: string | null;
  intervalMs?: number | null;
  projectId?: string | null;
  lastFiredAt?: number | null;
  nextFireAt?: number | null;
  fireAt?: number | null;
  owner?: string | null;
}

export interface TriggerEventView {
  at?: number;
  disposition: string;
  source?: string;
  detail?: string | null;
  [k: string]: unknown;
}

/** What the New trigger form sends; `kind` defaults to manual and the action to `start_next`. */
export interface TriggerDraft {
  id?: string;
  name: string;
  kind?: "manual" | "schedule";
  intervalMs?: number;
  action?: TriggerAction;
  enabled?: boolean;
  /** Kept on an edit so saving a trigger back does not reset them to the defaults. */
  coalesceMs?: number;
  ceilingRuns?: number;
  ceilingWindowMs?: number;
  projectId?: string | null;
}

export interface TriggersState {
  triggers: TriggerView[];
  loaded: boolean;
  events: Record<string, TriggerEventView[]>;
  refusal: string | null;
}

export interface TriggersActions {
  refresh(): void;
  save(d: TriggerDraft): void;
  fire(id: string): void;
  arm(id: string): void;
  disable(id: string): void;
  remove(id: string): void;
  loadEvents(id: string): void;
  clearRefusal(): void;
  dispose(): void;
}

export type TriggersStore = StoreApi<TriggersState & TriggersActions>;

/** Folds a pushed `trigger` into the list: replace by id, else append; the list stays oldest first. */
export function foldTrigger(list: TriggerView[], t: TriggerView): TriggerView[] {
  const i = list.findIndex((x) => x.id === t.id);
  if (i < 0) return [...list, t].sort((a, b) => a.createdAt - b.createdAt);
  const next = [...list];
  next[i] = t;
  return next;
}

const OWN = ["trigger"];

export function createTriggersStore(link: FrameLink): TriggersStore {
  return createStore<TriggersState & TriggersActions>()((set, get) => {
    const req = requester(link, "trig", (why) => set({ refusal: why }));
    let eventsFor: string | null = null;
    const off = link.onFrame((env: Envelope) => {
      const mine = req.settle(env);
      switch (env.type) {
        case "ready":
          if (get().loaded) get().refresh();
          return;
        case "triggers": {
          const list = json<TriggerView[]>(env.data);
          if (Array.isArray(list)) set({ triggers: list, loaded: true });
          return;
        }
        case "trigger": {
          const t = json<TriggerView>(env.data);
          if (t?.id) set({ triggers: foldTrigger(get().triggers, t) });
          return;
        }
        case "trigger_events": {
          const rows = json<TriggerEventView[]>(env.data);
          if (Array.isArray(rows) && eventsFor) set({ events: { ...get().events, [eventsFor]: rows } });
          return;
        }
        case "error":
          if (mine || OWN.some((p) => env.data.startsWith(p))) set({ refusal: env.data, loaded: true });
      }
    });
    return {
      triggers: [],
      loaded: false,
      events: {},
      refusal: null,
      refresh: () => void req.send("trigger_list", ""),
      save(d) {
        const body: Record<string, unknown> = { name: d.name.trim(), kind: d.kind ?? "manual", enabled: d.enabled ?? true, action: d.action ?? { kind: "start_next" } };
        if (d.id) body.id = d.id;
        if (d.coalesceMs != null) body.coalesceMs = d.coalesceMs;
        if (d.ceilingRuns != null) body.ceilingRuns = d.ceilingRuns;
        if (d.ceilingWindowMs != null) body.ceilingWindowMs = d.ceilingWindowMs;
        if (d.projectId) body.projectId = d.projectId;
        if (d.kind === "schedule" && d.intervalMs) body.intervalMs = d.intervalMs;
        req.send("trigger_save", JSON.stringify(body));
      },
      fire: (id) => void req.send("trigger_fire", id),
      arm: (id) => void req.send("trigger_arm", id),
      disable: (id) => void req.send("trigger_disable", id),
      remove: (id) => void req.send("trigger_delete", id),
      loadEvents(id) {
        eventsFor = id;
        req.send("trigger_events", id);
      },
      clearRefusal: () => set({ refusal: null }),
      dispose: off,
    };
  });
}

// The plugin's one store, made when the app activates it (index.tsx) and dropped when the plugin unloads.
let current: TriggersStore | null = null;

export function bindTriggersStore(s: TriggersStore | null): void {
  current = s;
}

export function triggersStore(): TriggersStore {
  if (!current) throw new Error("Infantry_Triggers: the store is used before the plugin is activated");
  return current;
}

export const useTriggers = <T,>(select: (s: TriggersState & TriggersActions) => T): T => useStore(triggersStore(), select);
