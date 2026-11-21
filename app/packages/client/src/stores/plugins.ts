// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The plugin store (RUST-UI-5): the daemon's plugin list (`plugins_op` {op:list} -> `plugins`), each row carrying the
// module's `ui` declarations (tabs, panels, fills, slots), and the owner's plugin manager (`plugin_admin`
// {args:[enable|disable, id]} -> `plugin_admin` {code,out,err}). `slotRegistry` is the pure rule the shell renders from:
// the way Kotlin's PluginTree + UiRegistrar turn module.json declarations into tabs, panels and slot fills. A row's
// `ui.web` is the module's web UI half (WEBUI-1), which the ui-plugins loader imports; `plugins_changed` (a plugin
// enabled or disabled, by any client) re-reads the list, so every window follows it live.
import { createStore, type StoreApi } from "zustand/vanilla";
import { json, type Envelope } from "../wire";
import { requester, type FrameLink } from "./link";

/** One tab / panel / fill as module.json declares it; only `id` is required, the rest is passed through. */
export interface UiDecl {
  id: string;
  container?: string;
  slot?: string;
  label?: string;
  title?: string;
  icon?: string;
  order?: number;
  /** The key hint a tab shows (`^N` also binds Ctrl+N). */
  hint?: string;
  [k: string]: unknown;
}

/** One `ui.contributes` entry of a web UI half: the slot it fills and its id there. */
export interface WebContribution extends UiDecl {
  slot: string;
}

/** A module's web UI half as the daemon lists it: daemon paths of its module and stylesheets, and a build fingerprint. */
export interface WebHalf {
  apiVersion: number;
  /** The ES module, a `/ui-plugins/<id>/...` daemon path. */
  entry: string;
  styles: string[];
  contributes: WebContribution[];
  /** Changes with every new build of its files. */
  rev: string;
}

export interface PluginUi {
  displayName?: string;
  description?: string;
  tabs: UiDecl[];
  panels: UiDecl[];
  fills: UiDecl[];
  slots: UiDecl[];
  config: boolean;
  /** The settings nodes the module declares (its cards in Settings). */
  configNodes: string[];
  /** The module's web UI half, when it has one. */
  web?: WebHalf;
}

export interface PluginRow {
  id: string;
  version?: string;
  class?: string;
  tier: string;
  bundled?: boolean;
  enabled: boolean;
  /** `loaded`, `not loaded`, `ui only`, or a fault text from the host. */
  status: string;
  grants?: string[];
  requested?: string[];
  license?: string;
  updateTo?: string | null;
  ui?: PluginUi;
}

export interface AdminResult {
  id: string;
  op: string;
  code: number;
  out: string;
  err: string;
}

/** APPS-ROOT-1: an optional member of an installed app that is not installed yet (offered by a source). */
export interface OptionalPlugin {
  id: string;
  displayName: string;
  description: string;
  version: string;
  /** The app it belongs to (its manifest's `app.optional` lists it). */
  app: string;
}

export interface PluginsState {
  plugins: PluginRow[];
  /** Optional members of installed apps that can be installed (from `plugin_admin available`; empty when none or not the owner). */
  optional: OptionalPlugin[];
  /** Installed plugins that belong to an app: id -> core or optional. A core member stays while its app does. */
  memberKind: Record<string, "core" | "optional">;
  loaded: boolean;
  /** Plugin ids with an enable/disable in flight. */
  pending: string[];
  last: AdminResult | null;
  refusal: string | null;
}

export interface PluginsActions {
  refresh(): void;
  /** Ask the daemon's plugin manager to enable or disable [id] (owner only); the list is re-read after the answer. */
  setEnabled(id: string, enabled: boolean): void;
  /** Install an optional member / remove one (owner only; `plugin_admin install|uninstall`). */
  /** Read which optional members of installed apps can be installed (owner only; an answer of "not the owner" is silent). */
  readOptional(): void;
  install(id: string): void;
  uninstall(id: string): void;
  clearRefusal(): void;
  dispose(): void;
}

export type PluginsStore = StoreApi<PluginsState & PluginsActions>;

interface AvailableRow {
  id: string;
  displayName?: string;
  description?: string;
  version?: string;
  source?: string | null;
  installed?: boolean;
  memberOf?: { app: string; kind: string }[];
}

/** Reads a `plugin_admin available` answer into the optional members to offer and the kind of each installed member. */
export function parseAvailable(out: string): Pick<PluginsState, "optional" | "memberKind"> {
  const rows = json<AvailableRow[]>(out);
  const optional: OptionalPlugin[] = [];
  const memberKind: Record<string, "core" | "optional"> = {};
  if (!Array.isArray(rows)) return { optional, memberKind };
  const apps = new Set(rows.filter((r) => r.installed).map((r) => r.id));
  for (const r of rows) {
    const of = (r.memberOf ?? []).filter((m) => apps.has(m.app));
    if (of.length === 0) continue;
    if (r.installed) memberKind[r.id] = of.some((m) => m.kind === "core") ? "core" : "optional";
    else if (r.source && of.some((m) => m.kind === "optional")) optional.push({ id: r.id, displayName: r.displayName || r.id, description: r.description ?? "", version: r.version ?? "", app: of[0].app });
  }
  return { optional, memberKind };
}

/** A status that is neither loaded nor a plain "not a daemon plugin" is a fault the manager shows. */
export function isFault(p: Pick<PluginRow, "status" | "enabled">): boolean {
  const s = p.status.toLowerCase();
  return !(s === "loaded" || s === "started" || s === "ui only" || (!p.enabled && (s === "not loaded" || s === "disabled")));
}

const decls = (v: unknown): UiDecl[] =>
  Array.isArray(v) ? v.map((d) => (typeof d === "string" ? { id: d } : (d as UiDecl))).filter((d) => d && typeof d.id === "string" && d.id.length > 0) : [];

const UI_PATH = "/ui-plugins/";

/** The `ui.web` block of a row, or undefined when it is absent or not one (a path outside `/ui-plugins/` included). */
export function parseWebHalf(v: unknown): WebHalf | undefined {
  if (!v || typeof v !== "object") return undefined;
  const w = v as Record<string, unknown>;
  if (typeof w.entry !== "string" || !w.entry.startsWith(UI_PATH) || typeof w.apiVersion !== "number") return undefined;
  const styles = Array.isArray(w.styles) ? w.styles.filter((s): s is string => typeof s === "string" && s.startsWith(UI_PATH)) : [];
  const contributes = decls(w.contributes).filter((c): c is WebContribution => typeof c.slot === "string" && c.slot.length > 0);
  return { apiVersion: w.apiVersion, entry: w.entry, styles, contributes, rev: typeof w.rev === "string" ? w.rev : "" };
}

/** Normalises one row of the `plugins` answer; null when it is not a plugin row. */
export function parsePlugin(v: unknown): PluginRow | null {
  if (!v || typeof v !== "object") return null;
  const r = v as Record<string, unknown>;
  if (typeof r.id !== "string" || !r.id) return null;
  const u = (r.ui ?? {}) as Record<string, unknown>;
  return {
    id: r.id,
    version: typeof r.version === "string" ? r.version : undefined,
    class: typeof r.class === "string" ? r.class : undefined,
    tier: typeof r.tier === "string" ? r.tier : "community",
    bundled: r.bundled === true,
    enabled: r.enabled === true,
    status: typeof r.status === "string" ? r.status : "unknown",
    grants: Array.isArray(r.grants) ? (r.grants as string[]) : [],
    requested: Array.isArray(r.requested) ? (r.requested as string[]) : [],
    license: typeof r.license === "string" ? r.license : undefined,
    updateTo: typeof r.updateTo === "string" ? r.updateTo : null,
    ui: {
      displayName: typeof u.displayName === "string" && u.displayName ? u.displayName : undefined,
      description: typeof u.description === "string" && u.description ? u.description : undefined,
      tabs: decls(u.tabs),
      panels: decls(u.panels),
      fills: decls(u.fills),
      slots: decls(u.slots),
      config: u.config === true,
      configNodes: Array.isArray(u.configNodes) ? (u.configNodes as unknown[]).filter((n): n is string => typeof n === "string") : [],
      web: parseWebHalf(u.web),
    },
  };
}

/** One contribution in the registry: the declaration and the plugin that made it. */
export interface Contribution {
  plugin: string;
  decl: UiDecl;
}

export interface SlotRegistry {
  /** Centre tabs, by container (`center` when the declaration names none), in plugin-tier then declaration order. */
  tabs: Contribution[];
  panels: Contribution[];
  /** Slot id -> the fills for it. */
  fills: Record<string, Contribution[]>;
  /** Slot id -> the plugin that declares (owns) it. */
  slots: Record<string, string>;
  /** Plugin id -> its web UI half: which of its declared tabs and slots a web UI plugin draws (WEBUI-1). */
  web: Record<string, WebHalf>;
}

/**
 * The registry the shell renders: only ENABLED plugins contribute; a tab or panel id seen first wins (official before
 * community, as the daemon orders the list); a fill names its slot by its id (`<slot>` or `<slot>.<part>` both match the
 * slot whose id is its longest prefix), and a fill for a slot no enabled plugin owns is still kept under its own id, so
 * core slots (`infantry.shell.*`) work with no declaring plugin.
 */
export function slotRegistry(plugins: PluginRow[]): SlotRegistry {
  const reg: SlotRegistry = { tabs: [], panels: [], fills: {}, slots: {}, web: {} };
  const seen = new Set<string>();
  const on = plugins.filter((p) => p.enabled);
  for (const p of on) for (const s of p.ui?.slots ?? []) reg.slots[s.id] ??= p.id;
  const owners = Object.keys(reg.slots).sort((a, b) => b.length - a.length);
  for (const p of on) {
    const ui = p.ui;
    if (!ui) continue;
    if (ui.web) reg.web[p.id] = ui.web;
    for (const t of ui.tabs) if (!seen.has(t.id)) (seen.add(t.id), reg.tabs.push({ plugin: p.id, decl: t }));
    for (const t of ui.panels) if (!seen.has(t.id)) (seen.add(t.id), reg.panels.push({ plugin: p.id, decl: t }));
    for (const f of ui.fills) {
      const slot = f.slot ?? owners.find((o) => f.id === o || f.id.startsWith(`${o}.`)) ?? f.id;
      (reg.fills[slot] ??= []).push({ plugin: p.id, decl: f });
    }
  }
  const byOrder = (a: Contribution, b: Contribution) => (a.decl.order ?? 0) - (b.decl.order ?? 0);
  reg.tabs.sort(byOrder);
  reg.panels.sort(byOrder);
  return reg;
}

/** A readable label for a declared id: `infantry.settings.plugins` -> `Plugins`. */
export function declLabel(d: UiDecl): string {
  if (typeof d.label === "string" && d.label) return d.label;
  if (typeof d.title === "string" && d.title) return d.title;
  const last = d.id.split(".").pop() ?? d.id;
  return last.charAt(0).toUpperCase() + last.slice(1);
}

export function createPluginsStore(link: FrameLink): PluginsStore {
  return createStore<PluginsState & PluginsActions>()((set, get) => {
    const req = requester(link, "plugins", (why) => set({ refusal: why }));
    const ops = new Map<string, { id: string; op: string }>();
    let adminCounter = 0;

    const off = link.onFrame((env: Envelope) => {
      if (env.type === "ready" || env.type === "plugins_changed") {
        if (get().loaded) get().refresh();
        return;
      }
      if (env.type === "plugins") {
        // Only our own `list` answer: another client's `search` / `show` also answers `plugins`, with catalog rows.
        if (!req.settle(env)) return;
        const list = json<unknown>(env.data);
        if (Array.isArray(list)) set({ plugins: list.map(parsePlugin).filter((p): p is PluginRow => p !== null), loaded: true });
        return;
      }
      if (env.type === "plugin_admin" && env.requestId && ops.has(env.requestId)) {
        const o = ops.get(env.requestId)!;
        ops.delete(env.requestId);
        const r = json<{ code?: number | string; out?: string; err?: string }>(env.data) ?? {};
        const code = Number(r.code ?? 1);
        if (o.op === "available") {
          if (code === 0) set(parseAvailable(r.out ?? ""));
          return;
        }
        set({
          pending: get().pending.filter((x) => x !== o.id),
          last: { ...o, code, out: r.out ?? "", err: r.err ?? "" },
          ...(code !== 0 ? { refusal: (r.err || r.out || `plugin ${o.op} ${o.id} failed (exit ${code})`).trim() } : {}),
        });
        get().refresh();
        return;
      }
      if (env.type === "error") {
        const rid = env.requestId;
        if (rid && ops.has(rid)) {
          const o = ops.get(rid)!;
          ops.delete(rid);
          if (o.op === "available") return; // not the owner: no optional list, no banner
          set({ pending: get().pending.filter((x) => x !== o.id), refusal: env.data });
        } else if (req.settle(env) || env.data.startsWith("plugins_op") || env.data.startsWith("plugin_admin")) {
          set({ refusal: env.data, loaded: true });
        }
      }
    });

    const admin = (op: string, id: string, track: boolean): void => {
      adminCounter += 1;
      const requestId = `plugin-admin:${Date.now().toString(36)}-${adminCounter.toString(36)}`;
      ops.set(requestId, { id, op });
      if (track) set({ pending: [...get().pending.filter((x) => x !== id), id] });
      if (!link.sendFrame({ type: "plugin_admin", data: JSON.stringify({ args: op === "available" ? [op] : [op, id] }), requestId })) {
        ops.delete(requestId);
        if (track) set({ pending: get().pending.filter((x) => x !== id), refusal: "plugin_admin not sent: the daemon link is down" });
      }
    };

    return {
      plugins: [],
      optional: [],
      memberKind: {},
      loaded: false,
      pending: [],
      last: null,
      refusal: null,
      refresh() {
        req.send("plugins_op", JSON.stringify({ op: "list" }));
      },
      readOptional: () => admin("available", "", false),
      install: (id) => admin("install", id, true),
      uninstall: (id) => admin("uninstall", id, true),
      setEnabled(id, enabled) {
        const op = enabled ? "enable" : "disable";
        adminCounter += 1;
        const requestId = `plugin-admin:${Date.now().toString(36)}-${adminCounter.toString(36)}`;
        ops.set(requestId, { id, op });
        set({ pending: [...get().pending.filter((x) => x !== id), id] });
        if (!link.sendFrame({ type: "plugin_admin", data: JSON.stringify({ args: [op, id] }), requestId })) {
          ops.delete(requestId);
          set({ pending: get().pending.filter((x) => x !== id), refusal: "plugin_admin not sent: the daemon link is down" });
        }
      },
      clearRefusal: () => set({ refusal: null }),
      dispose: off,
    };
  });
}
