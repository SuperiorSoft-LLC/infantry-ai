// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The wire stores of the RUST-UI-7 panels, each a query store (query.ts):
//   epics      `epic_list` "<projectId>" -> `epics` [Epic]; `epic_save` {projectId,name,goal} -> pushed `epic`
//   registry   `agent_registry` {op:list} -> `agent_registry` [{definition, contributor, source, enabled, ...}];
//              `{op: enable|disable, id}` answers the new list
//   mcp        `mcp_list` {} -> `mcp_servers` McpListAnswer; `mcp_changed` asks for a re-read
//   pods       `pod_query` "<podId>" -> `pod`; the review queue is the converged pods' nodes waiting for review
//   scoped     `settings_get` {key, project?, role?, session?} -> `settings` {key, value, scope, layers}
import type { FrameLink } from "./link";
import { createQueryStore, type QueryStore } from "./query";

const arr = <T>(raw: unknown): T[] | null => (Array.isArray(raw) ? (raw as T[]) : null);

// ---- epics ----------------------------------------------------------------------------------------------------------

export interface Epic {
  id: string;
  projectId: string;
  name: string;
  goal?: string;
  storyIds?: string[];
  baseBranch?: string;
}

export function foldById<T extends { id: string }>(list: T[], item: T): T[] {
  const i = list.findIndex((x) => x.id === item.id);
  if (i < 0) return [...list, item];
  const next = [...list];
  next[i] = item;
  return next;
}

export function createEpicsStore(link: FrameLink): QueryStore<Epic[]> {
  return createQueryStore<Epic[]>(link, {
    prefix: "epics",
    request: "epic_list",
    answers: ["epics"],
    empty: [],
    parse: (raw) => arr<Epic>(raw),
    pushes: { epic: (raw, prev) => (raw && typeof raw === "object" && typeof (raw as Epic).id === "string" ? foldById(prev, raw as Epic) : null) },
    own: ["epic_"],
  });
}

// ---- agent registry -------------------------------------------------------------------------------------------------

export interface RegistryAgent {
  definition: { id: string; name?: string; role?: string; description?: string; [k: string]: unknown };
  contributor: string;
  source: string;
  enabled: boolean;
  effectiveInstructions?: string;
}

export function parseRegistry(raw: unknown): RegistryAgent[] | null {
  const list = arr<RegistryAgent>(raw);
  return list ? list.filter((a) => a && typeof a.definition?.id === "string") : null;
}

/** Groups the registry by who contributed each agent: `roles` first, then plugins by id. */
export function byContributor(list: RegistryAgent[]): { contributor: string; agents: RegistryAgent[] }[] {
  const groups = new Map<string, RegistryAgent[]>();
  for (const a of list) (groups.get(a.contributor) ?? groups.set(a.contributor, []).get(a.contributor)!).push(a);
  return [...groups.entries()].sort(([a], [b]) => (a === "roles" ? -1 : b === "roles" ? 1 : a.localeCompare(b))).map(([contributor, agents]) => ({ contributor, agents }));
}

export function createRegistryStore(link: FrameLink): QueryStore<RegistryAgent[]> {
  return createQueryStore<RegistryAgent[]>(link, {
    prefix: "registry",
    request: "agent_registry",
    body: () => JSON.stringify({ op: "list" }),
    answers: ["agent_registry"],
    empty: [],
    // An enable/disable answers the list; a `get` answers one row, which folds in.
    parse: (raw, prev) => parseRegistry(raw) ?? (raw && typeof raw === "object" && (raw as RegistryAgent).definition ? prev.map((a) => (a.definition.id === (raw as RegistryAgent).definition.id ? (raw as RegistryAgent) : a)) : null),
    own: ["agent_registry"],
  });
}

// ---- MCP ------------------------------------------------------------------------------------------------------------

export interface McpServerView {
  name: string;
  source: string;
  target: string;
  scope?: string | null;
  transport: string;
  command?: string[];
  url?: string | null;
  envKeys?: string[];
  provides?: string[];
  localOnly?: boolean;
  enabled: boolean;
  state: string;
  detail?: string | null;
  toolCount?: number | null;
  shadowedBy?: string | null;
  projectId?: string | null;
}

export interface McpGrantView {
  id: string;
  server: string;
  scope: string;
  target: string;
  projectId?: string | null;
  purpose: string;
  approvedBy: string;
  approvedAt: number;
}

export interface McpList {
  servers: McpServerView[];
  targets: { id: string; canAdd?: boolean; scopes?: string[] }[];
  grants: McpGrantView[];
}

export function parseMcpList(raw: unknown): McpList | null {
  if (!raw || typeof raw !== "object") return null;
  const r = raw as Partial<McpList>;
  return { servers: arr<McpServerView>(r.servers) ?? [], targets: arr(r.targets) ?? [], grants: arr<McpGrantView>(r.grants) ?? [] };
}

export function createMcpStore(link: FrameLink): QueryStore<McpList> {
  return createQueryStore<McpList>(link, {
    prefix: "mcp",
    request: "mcp_list",
    body: () => "{}",
    answers: ["mcp_servers"],
    empty: { servers: [], targets: [], grants: [] },
    parse: (raw) => parseMcpList(raw),
    pushes: { mcp_grants: (raw, prev) => (Array.isArray(raw) ? { ...prev, grants: raw as McpGrantView[] } : null) },
    rereads: ["mcp_changed"],
    own: ["mcp_"],
  });
}

// ---- pods and the review queue ---------------------------------------------------------------------------------------

export interface PodNode {
  id: string;
  name?: string;
  state: string;
  branch?: string | null;
  workingDir?: string | null;
  sessionId?: string | null;
}

export interface PodView {
  id: string;
  goal?: string;
  state?: string;
  createdAt?: number;
  nodes: PodNode[];
}

export interface ReviewEntry {
  podId: string;
  podGoal: string;
  nodeName: string;
  branch?: string | null;
  workingDir?: string | null;
  sessionId?: string | null;
}

/** Pods by id; a `pod` frame folds in (the daemon has no pod-list frame, so only pods this client asked for are known). */
export function foldPod(prev: Record<string, PodView>, raw: unknown): Record<string, PodView> | null {
  if (!raw || typeof raw !== "object") return null;
  const r = raw as Record<string, unknown>;
  const pod = (r.pod && typeof r.pod === "object" ? r.pod : r) as Record<string, unknown>;
  if (typeof pod.id !== "string") return null;
  const nodes = arr<PodNode>(r.nodes ?? pod.nodes) ?? [];
  return { ...prev, [pod.id]: { id: pod.id, goal: pod.goal as string | undefined, state: pod.state as string | undefined, createdAt: pod.createdAt as number | undefined, nodes } };
}

/** The nodes waiting for review, from every known pod. */
export function reviewQueue(pods: Record<string, PodView>): ReviewEntry[] {
  return Object.values(pods).flatMap((p) =>
    p.nodes.filter((n) => /review/i.test(n.state)).map((n) => ({ podId: p.id, podGoal: p.goal ?? "", nodeName: n.name ?? n.id, branch: n.branch, workingDir: n.workingDir, sessionId: n.sessionId })),
  );
}

export function createPodsStore(link: FrameLink): QueryStore<Record<string, PodView>> {
  return createQueryStore<Record<string, PodView>>(link, {
    prefix: "pods",
    request: "pod_query",
    answers: [],
    empty: {},
    parse: (raw, prev) => foldPod(prev, raw),
    pushes: { pod: (raw, prev) => foldPod(prev, raw) },
    own: ["pod_"],
  });
}

// ---- scoped settings ------------------------------------------------------------------------------------------------

export type SettingScope = "session" | "role" | "project" | "app" | "default";

export interface ScopedLayer {
  scope: SettingScope;
  target: string;
  value: unknown;
}

export interface ScopedValue {
  key: string;
  value: unknown;
  scope: SettingScope;
  layers: ScopedLayer[];
}

export interface ScopeTarget {
  project?: string;
  role?: string;
  session?: string;
}

/** Scoped values by key, as the daemon last resolved them for the target in view. */
export function createScopedSettingsStore(link: FrameLink): QueryStore<Record<string, ScopedValue>> {
  return createQueryStore<Record<string, ScopedValue>>(link, {
    prefix: "scoped",
    request: "settings_get",
    answers: ["settings"],
    empty: {},
    parse: (raw, prev) => {
      if (!raw || typeof raw !== "object" || typeof (raw as ScopedValue).key !== "string") return null;
      const v = raw as ScopedValue;
      return { ...prev, [v.key]: { ...v, layers: arr<ScopedLayer>(v.layers) ?? [] } };
    },
    own: ["settings_get", "settings_set"],
  });
}

/** The `settings_get` / `settings_set` body for [key] at [target]. */
export function scopedBody(key: string, target: ScopeTarget, write?: { scope: Exclude<SettingScope, "default">; value: unknown }): string {
  const t = Object.fromEntries(Object.entries(target).filter(([, v]) => typeof v === "string" && v.trim()));
  return JSON.stringify(write ? { key, ...t, scope: write.scope, value: write.value } : { key, ...t });
}

// ---- notice kinds (the Notifications card) ----------------------------------------------------------------------------

/** One notice kind a sender declared: whether it wakes a session at the target in view, and its counters since start. */
export interface NoticeKind {
  kind: string;
  description: string;
  source: string;
  wake: boolean;
  checks: number;
  wakes: number;
  inbox: number;
  duplicates: number;
}

export interface NoticeKinds {
  setting: string;
  value: string | null;
  effective: string;
  from: SettingScope;
  layers: ScopedLayer[];
  kinds: NoticeKind[];
}

export const NO_NOTICE_KINDS: NoticeKinds = { setting: "notify.wake.kinds", value: null, effective: "", from: "default", layers: [], kinds: [] };

export function parseNoticeKinds(raw: unknown): NoticeKinds | null {
  if (!raw || typeof raw !== "object" || !Array.isArray((raw as NoticeKinds).kinds)) return null;
  const r = raw as NoticeKinds;
  return { ...r, layers: arr<ScopedLayer>(r.layers) ?? [] };
}

/** `notify_kinds` answers `notify_kinds_list` (the daemon's registry: the UI keeps no list of kinds). */
export function createNoticeKindsStore(link: FrameLink): QueryStore<NoticeKinds> {
  return createQueryStore<NoticeKinds>(link, {
    prefix: "notice-kinds",
    request: "notify_kinds",
    answers: ["notify_kinds_list"],
    empty: NO_NOTICE_KINDS,
    parse: (raw) => parseNoticeKinds(raw),
    own: ["notify_kinds", "notify_kind_set"],
  });
}

/** The body of `notify_kinds` / `notify_kind_set` at [target]; with [write] it turns [kind] on (wake) or off (inbox) at [scope]. */
export function noticeBody(target: ScopeTarget, write?: { kind: string; wake: boolean; scope: Exclude<SettingScope, "default"> }): string {
  const t = Object.fromEntries(Object.entries(target).filter(([, v]) => typeof v === "string" && v.trim()));
  return JSON.stringify(write ? { ...t, kind: write.kind, wake: write.wake, scope: write.scope } : t);
}

/** Turns saved: notices that cost no turn (held in the inbox, or dropped as a repeat). */
export const turnsSaved = (k: Pick<NoticeKind, "inbox" | "duplicates">): number => k.inbox + k.duplicates;
