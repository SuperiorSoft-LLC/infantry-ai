// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The one plugins, health and triggers store of this window, bound to the daemon store's link (RUST-UI-5).
// `useRegistry` is the slot registry the shell renders from: built from the daemon's plugin list, re-read on every link open.
import { useEffect, useMemo } from "react";
import { useStore } from "zustand";
import {
  createHealthStore,
  createPluginsStore,
  createTriggersStore,
  slotRegistry,
  type HealthActions,
  type HealthState,
  type HealthStore,
  type PluginsActions,
  type PluginsState,
  type PluginsStore,
  type SlotRegistry,
  type TriggersActions,
  type TriggersState,
  type TriggersStore,
} from "@infantry/client";
import { daemonStore, useDaemon } from "@/daemon/daemon";

let plugins: PluginsStore | null = null;
let health: HealthStore | null = null;
let triggers: TriggersStore | null = null;

export const pluginsStore = (): PluginsStore => (plugins ??= createPluginsStore(daemonStore().getState()));
export const healthStore = (): HealthStore => (health ??= createHealthStore(daemonStore().getState()));
export const triggersStore = (): TriggersStore => (triggers ??= createTriggersStore(daemonStore().getState()));

export const usePlugins = <T,>(select: (s: PluginsState & PluginsActions) => T): T => useStore(pluginsStore(), select);
export const useHealth = <T,>(select: (s: HealthState & HealthActions) => T): T => useStore(healthStore(), select);
export const useTriggers = <T,>(select: (s: TriggersState & TriggersActions) => T): T => useStore(triggersStore(), select);

/** Reads the plugin list whenever the link (re)opens. Mounted once, by the shell. */
export function usePluginsLoaded(): void {
  const link = useDaemon((s) => s.connection.state);
  useEffect(() => {
    if (link === "open") pluginsStore().getState().refresh();
  }, [link]);
}

/** The registry, or null until the daemon's list has arrived (the shell then shows its built-in tabs). */
export function useRegistry(): SlotRegistry | null {
  const list = usePlugins((s) => s.plugins);
  const loaded = usePlugins((s) => s.loaded);
  return useMemo(() => (loaded && list.length > 0 ? slotRegistry(list) : null), [list, loaded]);
}
