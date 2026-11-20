// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The web UI plugin loader (WEBUI-1): the app half of a module's `ui.web` block. Each ENABLED module with a web half is
// read from the daemon (`/ui-plugins/<id>/...`, the same files for every host), imported, and activated with a context
// that registers its views; a disabled or removed module is unloaded at once (its disposers run, its frame listeners and
// stylesheets go), and a new build (`rev`) is loaded in place of the old one. Nothing here knows a host: reading a file,
// importing a module from text and adding a stylesheet are the [UiLoaderDeps] each app host passes in.
//
// A plugin built for another UI API is refused before its code is fetched, with both versions in the message; every
// other failure (fetch, import, activate, a view that throws while rendering) is kept as the plugin's fault, which the
// app shows as a fault card while the rest of the window keeps working.
import { createStore, type StoreApi } from "zustand/vanilla";
import type { Envelope } from "../wire";
import type { FrameLink } from "./link";
import type { PluginRow, WebHalf, WebContribution } from "./plugins";

/** What a plugin registered for one of its contributions. The app's slots know the props each one is drawn with. */
export interface UiView {
  render: (props: never) => unknown;
  icon?: unknown;
}

/** Where a plugin failed: the UI API check, reading its files, importing its module, its `activate`, or a view's render. */
export type UiPhase = "version" | "fetch" | "import" | "activate" | "render";

export interface UiFault {
  phase: UiPhase;
  message: string;
  at: number;
}

export interface UiEntry {
  id: string;
  web: WebHalf;
  state: "loading" | "ready" | "failed";
  /** Contribution id -> the view the plugin registered for it. */
  views: Record<string, UiView>;
  fault: UiFault | null;
  /** Milliseconds from the first read to the end of `activate`; null until it is ready. */
  loadMs: number | null;
  /** Which load this is: a new one (a rebuild, a retry) draws its views afresh. */
  generation: number;
}

export interface UiPluginsState {
  /** Module id -> its web half, loading, ready or failed. Only enabled modules are here. */
  entries: Record<string, UiEntry>;
}

export interface UiPluginsActions {
  /** Brings the loaded set in line with the plugin list: new or rebuilt web halves load, disabled or removed ones unload. */
  sync(rows: PluginRow[]): void;
  /** Keeps a failure of [id] (a view's error boundary reports `render`). */
  fault(id: string, phase: UiPhase, error: unknown): void;
  /** Loads [id] again from the daemon (the fault card's Retry). */
  retry(id: string): void;
  /** Unloads everything (the window is closing). */
  dispose(): void;
}

export type UiPluginsStore = StoreApi<UiPluginsState & UiPluginsActions>;

/** What `activate` receives (the plugin API's `UiPluginContext`). */
export interface UiActivateContext {
  readonly id: string;
  readonly apiVersion: number;
  readonly link: FrameLink;
  register(id: string, view: UiView): void;
  onDispose(fn: () => void): void;
}

export interface UiLoaderDeps {
  /** The UI API this app provides. */
  apiVersion: number;
  /** The window's daemon link; each plugin gets a view of it that is cut when the plugin unloads. */
  link: FrameLink;
  /** Reads one of a plugin's files (a `/ui-plugins/` daemon path) as text. */
  read(path: string): Promise<string>;
  /** Imports an ES module from its source text (in a webview: a blob: URL). */
  importModule(source: string, id: string): Promise<unknown>;
  /** Adds a plugin's stylesheet; returns the function that removes it. */
  addStyle(id: string, css: string): () => void;
  now?: () => number;
}

/** The text of a thrown value. */
export function faultText(e: unknown): string {
  if (e instanceof Error) return e.message || e.name;
  if (typeof e === "string") return e;
  try {
    return JSON.stringify(e) ?? String(e);
  } catch {
    return String(e);
  }
}

/** The message a plugin built for another UI API is refused with. */
export function versionRefusal(id: string, needs: number, provides: number): string {
  return `${id} needs UI API ${needs}; this app provides UI API ${provides}. Update ${needs > provides ? "the app" : "the plugin"} to use it.`;
}

/** Where a tab sorts: by the order the host's default layout gives it (a plugin names none); one with none goes last. */
export function orderOf(c: { order?: unknown }): number {
  return typeof c.order === "number" && Number.isFinite(c.order) ? c.order : Number.MAX_SAFE_INTEGER;
}

/** One contribution to a slot, with its plugin's entry: loading, ready (then `entry.views[contribution.id]`) or failed. */
export interface SlotFill {
  plugin: string;
  contribution: WebContribution;
  entry: UiEntry;
}

/**
 * The contributions to [slot] of every loaded plugin (only [plugin]'s when given), in id order (a plugin declares no position). Loading and
 * failed plugins are in it too, so the app can draw their state (a fault card) where their view would be.
 */
export function slotFills(entries: Record<string, UiEntry>, slot: string, plugin?: string): SlotFill[] {
  const out: SlotFill[] = [];
  for (const e of Object.values(entries)) {
    if (plugin !== undefined && e.id !== plugin) continue;
    for (const c of e.web.contributes) if (c.slot === slot) out.push({ plugin: e.id, contribution: c, entry: e });
  }
  return out.sort((a, b) => a.contribution.id.localeCompare(b.contribution.id));
}

interface Live {
  generation: number;
  disposers: (() => void)[];
}

export function createUiPluginsStore(deps: UiLoaderDeps): UiPluginsStore {
  const now = deps.now ?? (() => Date.now());
  return createStore<UiPluginsState & UiPluginsActions>()((set, get) => {
    const live = new Map<string, Live>();
    let counter = 0;

    const patch = (id: string, gen: number, p: Partial<UiEntry>) => {
      if (live.get(id)?.generation !== gen) return;
      const e = get().entries[id];
      if (e) set({ entries: { ...get().entries, [id]: { ...e, ...p } } });
    };
    const fail = (id: string, gen: number, phase: UiPhase, error: unknown) => patch(id, gen, { state: "failed", fault: { phase, message: faultText(error), at: now() } });

    const runDisposers = (l: Live) => {
      for (const d of l.disposers.splice(0).reverse()) {
        try {
          d();
        } catch {
          // A disposer that throws must not keep the rest from running.
        }
      }
    };

    const unload = (id: string) => {
      const l = live.get(id);
      if (l) {
        l.generation = -1;
        runDisposers(l);
        live.delete(id);
      }
      if (get().entries[id]) {
        const rest = { ...get().entries };
        delete rest[id];
        set({ entries: rest });
      }
    };

    /** The plugin's view of the daemon link: its listeners go, and it can no longer send, once it unloads. */
    const scopedLink = (l: Live, gen: number): FrameLink => ({
      sendFrame: (env: Envelope) => l.generation === gen && deps.link.sendFrame(env),
      onFrame(listener) {
        const off = deps.link.onFrame((env) => {
          if (l.generation === gen) listener(env);
        });
        l.disposers.push(off);
        return off;
      },
    });

    const load = async (id: string, web: WebHalf) => {
      unload(id);
      counter += 1;
      const gen = counter;
      const l: Live = { generation: gen, disposers: [] };
      live.set(id, l);
      set({ entries: { ...get().entries, [id]: { id, web, state: "loading", views: {}, fault: null, loadMs: null, generation: gen } } });
      if (web.apiVersion !== deps.apiVersion) return fail(id, gen, "version", versionRefusal(id, web.apiVersion, deps.apiVersion));
      const t0 = now();
      let source: string;
      let styles: string[];
      try {
        [source, ...styles] = await Promise.all([deps.read(web.entry), ...web.styles.map((s) => deps.read(s))]);
      } catch (e) {
        return fail(id, gen, "fetch", e);
      }
      if (l.generation !== gen) return;
      let mod: { apiVersion?: unknown; activate?: unknown } | undefined;
      try {
        const ns = (await deps.importModule(source, id)) as { default?: typeof mod } | undefined;
        mod = ns?.default;
      } catch (e) {
        return fail(id, gen, "import", e);
      }
      if (l.generation !== gen) return;
      if (!mod || typeof mod.activate !== "function") return fail(id, gen, "import", `${id}: the module's default export has no activate(ctx)`);
      if (mod.apiVersion !== deps.apiVersion) return fail(id, gen, "version", versionRefusal(id, Number(mod.apiVersion), deps.apiVersion));
      for (const css of styles) l.disposers.push(deps.addStyle(id, css));
      const declared = new Set(web.contributes.map((c) => c.id));
      const views: Record<string, UiView> = {};
      const ctx: UiActivateContext = {
        id,
        apiVersion: deps.apiVersion,
        link: scopedLink(l, gen),
        register(cid, view) {
          if (!declared.has(cid)) throw new Error(`${id} registered "${cid}", which its module.json ui.contributes does not declare`);
          if (!view || typeof view.render !== "function") throw new Error(`${id}: the view for "${cid}" has no render(props)`);
          views[cid] = view;
        },
        onDispose(fn) {
          if (typeof fn === "function") l.disposers.push(fn);
        },
      };
      try {
        const ret = await (mod.activate as (c: UiActivateContext) => unknown)(ctx);
        if (typeof ret === "function") l.disposers.push(ret as () => void);
      } catch (e) {
        runDisposers(l);
        return fail(id, gen, "activate", e);
      }
      if (l.generation !== gen) return runDisposers(l);
      patch(id, gen, { state: "ready", views, loadMs: Math.max(0, now() - t0) });
    };

    return {
      entries: {},
      sync(rows) {
        const want = new Map<string, WebHalf>();
        for (const r of rows) if (r.enabled && r.ui?.web) want.set(r.id, r.ui.web);
        for (const id of Object.keys(get().entries)) if (!want.has(id)) unload(id);
        for (const [id, web] of want) {
          const e = get().entries[id];
          // A failed plugin stays failed until it is rebuilt (rev) or retried: a list refresh does not hammer it.
          if (!e || e.web.rev !== web.rev || e.web.entry !== web.entry) void load(id, web);
        }
      },
      fault(id, phase, error) {
        const e = get().entries[id];
        if (e) set({ entries: { ...get().entries, [id]: { ...e, fault: { phase, message: faultText(error), at: now() } } } });
      },
      retry(id) {
        const e = get().entries[id];
        if (e) void load(id, e.web);
      },
      dispose() {
        for (const id of [...live.keys()]) unload(id);
      },
    };
  });
}
