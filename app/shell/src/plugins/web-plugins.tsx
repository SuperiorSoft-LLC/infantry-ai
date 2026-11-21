// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// WEBUI-1: the web UI plugins of this window. The loader (@infantry/client `createUiPluginsStore`) knows no host; this
// module gives it this window's: the daemon link, `host.daemonGet` for a plugin's files, a blob: URL import and <style>
// elements. Before the first plugin loads it installs the UiHost global (the app's React, React DOM and UI kit, which
// every plugin uses instead of its own copy), and it keeps the loaded set in line with the daemon's plugin list, so
// enabling a plugin shows it and disabling it removes it, live, with no reload.
//
// A plugin's view is drawn inside its style scope: an element carrying SCOPE_ATTRIBUTE, and a portal root of its own for
// the kit's dialogs and menus. An error boundary sits around the scope: a view that throws is replaced by a fault card,
// drawn by the app outside the scope (only plugin elements and kit components ever render inside one), with Retry.
import * as React from "react";
import { Component, useMemo, type ComponentType, type ReactNode } from "react";
import * as JsxRuntime from "react/jsx-runtime";
import * as ReactDom from "react-dom";
import { AlertTriangle, Loader2, Puzzle, RotateCcw } from "lucide-react";
import { useStore } from "zustand";
import * as Kit from "@infantry/ui";
import { Button, Chip, EmptyState, PanelCard, PortalContainer, TabPage } from "@infantry/ui";
import {
  createUiPluginsStore,
  faultText,
  slotFills,
  type SlotFill,
  type UiFault,
  type UiPhase,
  type UiPluginsActions,
  type UiPluginsState,
  type UiPluginsStore,
  type UiView,
} from "@infantry/client";
import { HOST_GLOBAL, SCOPE_ATTRIBUTE, UI_API_VERSION, type UiHost } from "@infantry/ui-plugin-api/contract";
import { host } from "@/host";
import { daemonLink, daemonStore, useDaemon } from "@/daemon/daemon";
import { pluginsStore, usePlugins } from "./use-plugins";
import { usePreference } from "./preferences";
import { shellUpdater } from "./shell-updater";
import { HostAgentAvatar, useAgentActivity } from "./agent-activity";

/** Lends the plugins this window loads its own React, React DOM and UI kit, and the app state they may read. */
function installHost(): void {
  const lent: UiHost = {
    apiVersion: UI_API_VERSION,
    modules: { react: React, "react/jsx-runtime": JsxRuntime, "react-dom": ReactDom, "@infantry/ui": Kit },
    api: { useLinkState: () => useDaemon((s) => s.connection.state), usePreference, useAgentActivity, AgentAvatar: HostAgentAvatar, windowFrame: () => host.window, shellUpdater: () => shellUpdater(host) },
  };
  (globalThis as Record<string, unknown>)[HOST_GLOBAL] = Object.freeze(lent);
}

async function read(path: string): Promise<string> {
  const at = daemonLink();
  // The embedded runner has no HTTP side: web UI plugins come from a daemon (docs/wiki/Web-UI-plugins.md).
  if (!at) throw new Error(`${path}: this window runs without a daemon, and web UI plugins are served by one`);
  return new TextDecoder().decode(await host.daemonGet(path, at));
}

async function importModule(source: string): Promise<unknown> {
  const url = URL.createObjectURL(new Blob([source], { type: "text/javascript" }));
  try {
    return await import(/* @vite-ignore */ url);
  } finally {
    URL.revokeObjectURL(url);
  }
}

/** After the app's own stylesheet, which a plugin's scoped rules are written to follow. */
function addStyle(id: string, css: string): () => void {
  const el = document.createElement("style");
  el.dataset.uiPluginStyle = id;
  el.textContent = css;
  document.head.appendChild(el);
  return () => el.remove();
}

let store: UiPluginsStore | null = null;
const portalRoots = new Map<string, HTMLElement>();

/** The window's web UI plugins; the first call installs the host global and starts following the plugin list. */
export function uiPluginsStore(): UiPluginsStore {
  if (store) return store;
  installHost();
  const s = createUiPluginsStore({ apiVersion: UI_API_VERSION, link: daemonStore().getState(), read, importModule, addStyle });
  store = s;
  const plugins = pluginsStore();
  s.getState().sync(plugins.getState().plugins);
  plugins.subscribe((now, before) => {
    if (now.plugins !== before.plugins) s.getState().sync(now.plugins);
  });
  // A plugin that unloads takes its portal root (and whatever it left open in it) along. Each load's time is a User
  // Timing measure, "ui-plugin <id>" (from the first read to the end of activate), shown in DevTools' Performance panel.
  const measured = new Set<string>();
  s.subscribe((now) => {
    for (const [id, root] of portalRoots) if (!now.entries[id]) (root.remove(), portalRoots.delete(id));
    for (const e of Object.values(now.entries)) {
      const load = `${e.id}#${e.generation}`;
      if (e.state !== "ready" || e.loadMs === null || measured.has(load)) continue;
      measured.add(load);
      performance.measure(`ui-plugin ${e.id}`, { start: Math.max(0, performance.now() - e.loadMs), duration: e.loadMs });
    }
  });
  return s;
}

export const useUiPlugins = <T,>(select: (s: UiPluginsState & UiPluginsActions) => T): T => useStore(uiPluginsStore(), select);

/** Where a plugin's dialogs, selects and menus render: inside its style scope, at the end of the page. */
function portalRoot(plugin: string): HTMLElement {
  let root = portalRoots.get(plugin);
  if (!root) {
    root = document.createElement("div");
    root.setAttribute(SCOPE_ATTRIBUTE, plugin);
    document.body.appendChild(root);
    portalRoots.set(plugin, root);
  }
  return root;
}

/** The contributions to [slot] of the loaded plugins (only [plugin]'s when given), loading and failed ones included. */
export function useSlotFills(slot: string, plugin?: string): SlotFill[] {
  const entries = useUiPlugins((s) => s.entries);
  return useMemo(() => slotFills(entries, slot, plugin), [entries, slot, plugin]);
}

/** The fault a plugin's web half has (failed to load, or a view threw), or null. */
export function usePluginUiFault(plugin: string): UiFault | null {
  return useUiPlugins((s) => s.entries[plugin]?.fault ?? null);
}

function useDisplayName(plugin: string): string {
  return usePlugins((s) => s.plugins.find((p) => p.id === plugin)?.ui?.displayName) || plugin;
}

/** How a view sits where it is drawn: a whole centre tab, a card (right panel, settings), or a badge on a ticket card. */
export type ViewFrame = "page" | "card" | "badge";

const WHAT: Record<UiPhase, string> = {
  version: "is built for another UI API",
  fetch: "could not be read from the daemon",
  import: "could not be loaded",
  activate: "failed to start",
  render: "failed while drawing",
};

function FaultCard({ plugin, fault, frame }: { plugin: string; fault: UiFault; frame: ViewFrame }) {
  const name = useDisplayName(plugin);
  const line = `${name} ${WHAT[fault.phase]}`;
  if (frame === "badge")
    return (
      <span title={`${line}: ${fault.message}`} role="status">
        <Chip tone="red">{name}: fault</Chip>
      </span>
    );
  // A rebuilt plugin (a new rev) loads by itself; Retry reads the files again (a daemon that was away, a render fault).
  const retry =
    fault.phase === "version" ? undefined : (
      <Button variant="outline" size="sm" onClick={() => uiPluginsStore().getState().retry(plugin)}>
        <RotateCcw /> Retry
      </Button>
    );
  const card = (
    <PanelCard title={frame === "page" ? "Plugin view" : name} action={<Chip tone="red">{fault.phase} fault</Chip>}>
      <div role="alert">
        <EmptyState icon={<AlertTriangle className="size-6" />} title={line} hint={fault.message} action={retry} />
      </div>
    </PanelCard>
  );
  return frame === "page" ? (
    <TabPage title={name} description={plugin}>
      {card}
    </TabPage>
  ) : (
    card
  );
}

/** Keeps a view's throw to its own slot: the fault card replaces it (and the scope with it), the store records it. */
class Guard extends Component<{ plugin: string; fallback: (fault: UiFault) => ReactNode; children: ReactNode }, { fault: UiFault | null }> {
  state: { fault: UiFault | null } = { fault: null };

  static getDerivedStateFromError(error: unknown): { fault: UiFault } {
    return { fault: { phase: "render", message: faultText(error), at: Date.now() } };
  }

  componentDidCatch(error: unknown): void {
    uiPluginsStore().getState().fault(this.props.plugin, "render", error);
  }

  render(): ReactNode {
    return this.state.fault ? this.props.fallback(this.state.fault) : this.props.children;
  }
}

/** Calls the plugin's render under the Guard, so a render that throws is caught like one of its components. */
function Draw({ view, props }: { view: UiView; props: object }) {
  return <>{(view.render as (p: object) => ReactNode)(props)}</>;
}

/** Contribution [id] of [plugin], drawn with [props]: loading, its fault card, or its view inside the plugin's scope. */
export function PluginView({ plugin, id, props = {}, frame }: { plugin: string; id: string; props?: object; frame: ViewFrame }) {
  const entry = useUiPlugins((s) => s.entries[plugin]);
  const name = useDisplayName(plugin);
  if (!entry || entry.state === "loading")
    return frame === "page" ? (
      <div className="grid flex-1 place-items-center p-6">
        <EmptyState icon={<Loader2 className="size-6 animate-spin" />} title={`Loading ${name}…`} />
      </div>
    ) : null;
  if (entry.state === "failed") return <FaultCard plugin={plugin} fault={entry.fault ?? { phase: "import", message: "unknown failure", at: 0 }} frame={frame} />;
  const view = entry.views[id];
  if (!view) {
    if (frame === "badge") return null;
    return <FaultCard plugin={plugin} fault={{ phase: "activate", message: `${plugin} declares "${id}" in ui.contributes but registered no view for it`, at: 0 }} frame={frame} />;
  }
  const drawn = (
    <Guard key={entry.generation} plugin={plugin} fallback={(fault) => <FaultCard plugin={plugin} fault={fault} frame={frame} />}>
      <div {...{ [SCOPE_ATTRIBUTE]: plugin }} style={{ display: "contents" }}>
        <PortalContainer container={portalRoot(plugin)}>
          <Draw view={view} props={props} />
        </PortalContainer>
      </div>
    </Guard>
  );
  // The scope element draws no box of its own; a card gets one, so a list's spacing (space-y-*) reaches it.
  return frame === "card" ? <div>{drawn}</div> : drawn;
}

/** Every fill of a slot, in order (see [useSlotFills]), each drawn with [props]. */
export function PluginSlot({ fills, props, frame }: { fills: SlotFill[]; props?: object; frame: Exclude<ViewFrame, "page"> }) {
  return (
    <>
      {fills.map((f) => (
        <PluginView key={`${f.plugin}/${f.contribution.id}`} plugin={f.plugin} id={f.contribution.id} props={props} frame={frame} />
      ))}
    </>
  );
}

type Icon = ComponentType<{ className?: string }>;
const tabIcons = new Map<string, Icon>();

/**
 * The tab icon of a plugin's tab or panel: the one its view registered, a blank of the same size while it loads (no
 * layout shift), the generic plugin icon when it failed or registered none. One component per tab, kept, so the tab
 * bar never remounts it.
 */
export function webTabIcon(plugin: string, id: string): Icon {
  const key = `${plugin}\u0000${id}`;
  let icon = tabIcons.get(key);
  if (!icon) {
    const WebTabIcon = ({ className }: { className?: string }) => {
      const entry = useUiPlugins((s) => s.entries[plugin]);
      if (!entry || entry.state === "loading") return <span className={className} aria-hidden />;
      const Registered = entry.state === "ready" ? (entry.views[id]?.icon as Icon | undefined) : undefined;
      if (!Registered) return <Puzzle className={className} aria-hidden />;
      return (
        <Guard key={entry.generation} plugin={plugin} fallback={() => <Puzzle className={className} aria-hidden />}>
          <Registered className={className} />
        </Guard>
      );
    };
    icon = WebTabIcon;
    tabIcons.set(key, icon);
  }
  return icon;
}
