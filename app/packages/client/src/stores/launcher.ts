// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// What the New agent dialog needs besides roles: the harnesses (`providers` frame) and their model groups (`models`
// frame), plus the dialog's pure draft logic (NewAgentModalModel.kt): ranked role search, the executor seeded from a
// role's binding, and why Add is blocked.
import { createStore, type StoreApi } from "zustand/vanilla";
import type { Envelope } from "../wire";
import type { FrameLink } from "./link";
import type { Role, RoleBinding } from "./roles";

export interface HarnessInfo {
  id: string;
  default?: boolean;
  defaultModel?: string | null;
  models?: string[] | null;
  available?: boolean;
  unavailableReason?: string | null;
  /** Why the Usage tab has no plan-limit meter for this harness, and what is still counted (the harness states it). */
  usageNote?: string | null;
  /** The effort levels this harness takes. */
  efforts?: string[] | null;
}

export interface ModelGroup {
  provider: string;
  group: string;
  label: string;
  models: string[];
  unavailable?: boolean;
}

export interface LauncherState {
  harnesses: HarnessInfo[];
  catalog: ModelGroup[];
  loaded: boolean;
}
export interface LauncherActions {
  ensureLoaded(): void;
  dispose(): void;
}
export type LauncherStore = StoreApi<LauncherState & LauncherActions>;

function list<T>(data: string): T[] {
  try {
    const v = JSON.parse(data);
    return Array.isArray(v) ? (v as T[]) : [];
  } catch {
    return [];
  }
}

export function createLauncherStore(link: FrameLink): LauncherStore {
  let asked = false;
  let off: (() => void) | null = null;
  return createStore<LauncherState & LauncherActions>((set) => ({
    harnesses: [],
    catalog: [],
    loaded: false,
    ensureLoaded() {
      off ??= link.onFrame((e: Envelope) => {
        if (e.type === "providers") set({ harnesses: list<HarnessInfo>(e.data), loaded: true });
        else if (e.type === "models") set({ catalog: list<ModelGroup>(e.data) });
      });
      if (asked) return;
      asked = link.sendFrame({ type: "providers", data: "" }) && link.sendFrame({ type: "models", data: "" });
    },
    dispose() {
      off?.();
      off = null;
      asked = false;
    },
  }));
}

/** The dialog's draft. */
export interface NewAgentDraft {
  roleId: string | null;
  harness: string | null;
  group: string | null;
  model: string | null;
  effort?: string | null;
  task: string;
}
export const EMPTY_DRAFT: NewAgentDraft = { roleId: null, harness: null, group: null, model: null, effort: null, task: "" };

/** Roles narrowed by id and name, best match first (exact, prefix, then substring). */
export function rankRoles(roles: Role[], query: string): Role[] {
  const q = query.trim().toLowerCase();
  if (!q) return roles;
  const score = (r: Role): number => {
    const id = r.id.toLowerCase();
    const name = r.name.toLowerCase();
    if (id === q || name === q) return 0;
    if (id.startsWith(q) || name.startsWith(q)) return 1;
    if (id.includes(q) || name.includes(q)) return 2;
    return 9;
  };
  return roles
    .map((r) => [score(r), r] as const)
    .filter(([s]) => s < 9)
    .sort((a, b) => a[0] - b[0])
    .map(([, r]) => r);
}

/** Models a harness can run: its model groups, else the list on the harness itself. */
export function modelsFor(harness: string | null, harnesses: HarnessInfo[], catalog: ModelGroup[]): ModelGroup[] {
  if (!harness) return [];
  const groups = catalog.filter((g) => g.provider === harness && !g.unavailable && g.models.length > 0);
  if (groups.length > 0) return groups;
  const h = harnesses.find((x) => x.id === harness);
  const own = h?.models?.length ? h.models : h?.defaultModel ? [h.defaultModel] : [];
  return own.length ? [{ provider: harness, group: harness, label: harness, models: own }] : [];
}

/**
 * Why the harnesses the picker greys out cannot run here: "pi: pi is not installed (not on PATH) · codex: codex is not
 * signed in: run `codex login`". Null when every harness can run. The daemon states each reason; a greyed entry with
 * none still says it cannot run.
 */
export function unavailableLine(harnesses: HarnessInfo[]): string | null {
  const down = harnesses.filter((h) => h.available === false);
  if (down.length === 0) return null;
  return down.map((h) => `${h.id}: ${h.unavailableReason?.trim() || "cannot run on this machine"}`).join(" · ");
}

/** What the role dialog's model pin suggests (a role binds to any harness the daemon runs, metered or not). */
export function pinOptions(harnesses: HarnessInfo[], catalog: ModelGroup[], bindings: RoleBinding[], provider: string): { providers: string[]; models: string[] } {
  const wanted = provider.trim();
  const providers = new Set([...harnesses.map((h) => h.id), ...bindings.map((b) => b.provider ?? "")].filter(Boolean));
  const bound = bindings.filter((b) => !wanted || b.provider === wanted).map((b) => b.model ?? "");
  const own = wanted ? modelsFor(wanted, harnesses, catalog).flatMap((g) => g.models) : [];
  return { providers: [...providers].sort(), models: [...new Set([...own, ...bound].filter(Boolean))].sort() };
}

function seed(d: NewAgentDraft, harness: string | null, wanted: string | null, harnesses: HarnessInfo[], catalog: ModelGroup[]): NewAgentDraft {
  const groups = modelsFor(harness, harnesses, catalog);
  const all = groups.flatMap((g) => g.models);
  let model = wanted && (all.length === 0 || all.includes(wanted)) ? wanted : null;
  if (!model && all.length === 1) model = all[0];
  return { ...d, harness, group: groups.find((g) => model && g.models.includes(model))?.group ?? null, model };
}

/** Choose a role and seed the executor from its binding (else the default harness and its default model). */
export function withRole(d: NewAgentDraft, roleId: string, binding: RoleBinding | undefined, harnesses: HarnessInfo[], catalog: ModelGroup[]): NewAgentDraft {
  const fallback = harnesses.find((h) => h.default && h.available !== false) ?? harnesses.find((h) => h.available !== false);
  const harness = binding?.provider || fallback?.id || null;
  const wanted = binding?.model || harnesses.find((h) => h.id === harness)?.defaultModel || null;
  return seed({ ...d, roleId }, harness, wanted, harnesses, catalog);
}

/** No role picked: seed the executor from the default harness and its default model, so all defaults can start an agent. */
export function withDefaults(d: NewAgentDraft, harnesses: HarnessInfo[], catalog: ModelGroup[]): NewAgentDraft {
  const fallback = harnesses.find((h) => h.default && h.available !== false) ?? harnesses.find((h) => h.available !== false);
  if (!fallback) return d;
  return seed(d, fallback.id, fallback.defaultModel || null, harnesses, catalog);
}

export function withHarness(d: NewAgentDraft, harness: string, harnesses: HarnessInfo[], catalog: ModelGroup[]): NewAgentDraft {
  return seed(d, harness, harnesses.find((h) => h.id === harness)?.defaultModel ?? null, harnesses, catalog);
}

/** Why Add is disabled (shown beside the button, never silent), or null when it can go. */
export function addBlockedReason(d: NewAgentDraft): string | null {
  if (!d.harness) return "Pick a harness";
  if (!d.task.trim()) return "Type the agent's first task";
  return null;
}
