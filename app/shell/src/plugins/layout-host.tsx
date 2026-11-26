// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// DESK-1: the shell's layout host. It subscribes to the layout store, so swapping the layout re-renders live. A layout with a
// `root` owns the whole screen: the host draws that root instead of the shell, handing it today's shell as `Shell` (the
// "orchestrator app"). A layout without one gets the shell as it always was.
// DESK-2: a plugin contributes a whole-window layout in the `layout` slot; the `layout` preference (`<Plugin>/<id>`, default
// `default` = today's window) picks which one is drawn. The host turns the picked contribution into a layout with a root and
// swaps it in; a layout that is gone (plugin removed or disabled) or has failed falls back to today's window, never a dead end.
import { useEffect, useLayoutEffect, useMemo, useRef, useState, type ComponentType, type ReactNode } from "react";
import { createPortal } from "react-dom";
import type { LayoutRoot } from "@infantry/ui-plugin-api/layout";
import type { ShellProps } from "@infantry/ui-plugin-api/contract";
import { createLayoutProvider } from "@infantry/ui-plugin-api/layout";
import { AppShell } from "@/components/layout/app-shell";
import { host } from "@/host";
import { DEFAULT_LAYOUT, setLayoutProvider, useLayoutProvider } from "./default-layout";
import { usePreference } from "./preferences";
import { usePlugins, usePluginsLoaded } from "./use-plugins";
import { PluginView, useSlotFills, useUiPlugins } from "./web-plugins";

/** The preference value that means today's window. */
export const DEFAULT_LAYOUT_CHOICE = "default";
/** How long a never-chosen device waits for the plugin list (which may hold the start layout) before drawing today's window. */
const START_WAIT_MS = 3000;

function View({ plugin, id, route }: { plugin: string; id: string; route?: string }) {
  return <PluginView plugin={plugin} id={id} frame="page" props={route === undefined ? {} : { route }} />;
}

/**
 * The shell as a plugin layout draws it. A plugin's view sits inside its style scope, whose utility classes would also match
 * the shell's elements (a scoped `.hidden` beating the shell's own `sm:contents`) and change the app. So the shell is NOT drawn
 * inside the scope: it is rendered, through a portal (same React tree: its state and contexts are the app's own), into a
 * host element outside every scope, and that host is laid over the placeholder the layout drew. The placeholder takes the
 * layout's room; hiding it (a minimized window) hides the shell, which stays mounted; unmounting it closes the shell.
 */
function useShellOutlet(Shell: ComponentType<ShellProps>): ComponentType<ShellProps> {
  return useMemo(
    () =>
      function ShellOutlet({ className, headerActions, statusLeading }: ShellProps) {
        const place = useRef<HTMLDivElement>(null);
        const [host] = useState(() => {
          const el = document.createElement("div");
          el.style.position = "fixed";
          el.style.display = "none";
          return el;
        });
        useLayoutEffect(() => {
          document.body.appendChild(host);
          const sync = () => {
            const at = place.current;
            // display:none on the placeholder or an ancestor (a minimized window) gives it no layout box.
            if (!at || at.getClientRects().length === 0) return void (host.style.display = "none");
            const r = at.getBoundingClientRect();
            host.style.display = "block";
            host.style.left = `${r.left}px`;
            host.style.top = `${r.top}px`;
            host.style.width = `${r.width}px`;
            host.style.height = `${r.height}px`;
          };
          sync();
          const watch = new ResizeObserver(sync);
          if (place.current) watch.observe(place.current);
          watch.observe(document.documentElement);
          window.addEventListener("resize", sync);
          const tick = window.setInterval(sync, 250); // a move without a resize (a sibling above grew, a window shown)
          return () => {
            watch.disconnect();
            window.removeEventListener("resize", sync);
            window.clearInterval(tick);
            host.remove();
          };
        }, [host]);
        return (
          <>
            <div ref={place} className={className} data-shell-outlet />
            {createPortal(<Shell headerActions={headerActions} statusLeading={statusLeading} />, host)}
          </>
        );
      },
    [Shell],
  );
}

/**
 * One root component per layout key, made once. The root is a component TYPE: a new one for the same layout would make React
 * remount the whole layout (and lose its state, e.g. the desktop's open windows) each time the plugin list changes, as it does
 * when any plugin is installed or uninstalled.
 */
const roots = new Map<string, LayoutRoot<ComponentType<ShellProps>, ReactNode>>();

/** The root of plugin layout [plugin]/[id]: its `layout` view, or today's shell when that plugin's UI has failed. */
export function pluginRoot(plugin: string, id: string): LayoutRoot<ComponentType<ShellProps>, ReactNode> {
  const key = `${plugin}/${id}`;
  let root = roots.get(key);
  if (!root) roots.set(key, (root = makePluginRoot(plugin, id)));
  return root;
}

function makePluginRoot(plugin: string, id: string): LayoutRoot<ComponentType<ShellProps>, ReactNode> {
  return function PluginLayoutRoot({ Shell }) {
    const failed = useUiPlugins((s) => s.entries[plugin]?.state === "failed" || !!s.entries[plugin]?.fault);
    const Outlet = useShellOutlet(Shell);
    if (failed) return <Shell />;
    return <PluginView plugin={plugin} id={id} props={{ Shell: Outlet, View }} frame="page" />;
  };
}

/**
 * The layout the `layout` preference picks: its provider (also set in the layout store, for anything that reads it) and its
 * root. `ready` is false while a plugin layout is chosen but the daemon's plugin list has not arrived: hold the window
 * rather than flash today's shell.
 */
function useChosenLayout(): { ready: boolean; Root: LayoutRoot<ComponentType<ShellProps>, ReactNode> | undefined } {
  // "" = this device has never chosen. Then a layout whose manifest declares `defaultLayout: true` is the start layout (the shell
  // names no plugin); "default" is an explicit choice of today's window and always wins.
  const [choice] = usePreference<string>("layout", "");
  const loaded = usePlugins((s) => s.loaded);
  const fills = useSlotFills("layout");
  const unset = choice === "";
  // With no daemon the plugin list never arrives: after a short wait show today's window rather than a blank screen.
  const [gaveUp, setGaveUp] = useState(false);
  useEffect(() => {
    if (!unset || loaded) return;
    const t = window.setTimeout(() => setGaveUp(true), START_WAIT_MS);
    return () => window.clearTimeout(t);
  }, [unset, loaded]);
  // Keyed by the picked layout's name, not the fill object: the fills are rebuilt whenever the plugin list changes.
  const wanted = useMemo(() => {
    if (choice === DEFAULT_LAYOUT_CHOICE) return undefined;
    // BOS-8: under the OS the OS is the desktop, so no inner desktop starts here (an explicit choice still wins).
    const f = unset
      ? (host.os ? undefined : fills.find((f) => f.contribution.defaultLayout === true))
      : fills.find((f) => `${f.plugin}/${f.contribution.id}` === choice);
    return f ? `${f.plugin}/${f.contribution.id}` : undefined;
  }, [choice, unset, fills]);
  const provider = useMemo(() => {
    if (!wanted) return createLayoutProvider(DEFAULT_LAYOUT, {});
    const at = wanted.indexOf("/");
    return createLayoutProvider(DEFAULT_LAYOUT, { root: pluginRoot(wanted.slice(0, at), wanted.slice(at + 1)) as LayoutRoot });
  }, [wanted]);
  useEffect(() => setLayoutProvider(provider), [provider]);
  return { ready: choice === DEFAULT_LAYOUT_CHOICE || loaded || gaveUp, Root: provider.root as LayoutRoot<ComponentType<ShellProps>, ReactNode> | undefined };
}

/** The window: the active layout's own root when it owns the screen, else the shell. */
export function LayoutHost({ Shell = AppShell }: { Shell?: ComponentType<ShellProps> } = {}) {
  usePluginsLoaded(); // the plugin list is what finds a layout plugin; do not wait for a shell that a layout may not draw
  const { ready, Root: Chosen } = useChosenLayout();
  // A layout set straight in the store (setLayoutProvider, DESK-1) is honoured when no plugin layout is picked.
  const stored = useLayoutProvider().root as LayoutRoot<ComponentType<ShellProps>, ReactNode> | undefined;
  const Root = Chosen ?? stored;
  if (!ready) return null;
  // Drawn as a component, not called: a root may use hooks.
  const Layout = Root as ComponentType<{ Shell: ComponentType<ShellProps> }> | undefined;
  return Layout ? <Layout Shell={Shell} /> : <Shell />;
}
