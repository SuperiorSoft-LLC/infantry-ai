// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The query stores of the RUST-UI-7 panels, one each per window, bound to the daemon store's link. `useQueryOnOpen` sends
// a panel's request whenever the link opens (and when its body changes), so a panel never shows stale or invented data.
import { useEffect } from "react";
import { useStore } from "zustand";
import {
  createEpicsStore,
  createMcpStore,
  createNoticeKindsStore,
  createPodsStore,
  createQueryStore,
  createRegistryStore,
  createScopedSettingsStore,
  type QueryActions,
  type QueryState,
  type QueryStore,
} from "@infantry/client";
import { daemonStore, useDaemon } from "@/daemon/daemon";

const cache = new Map<string, QueryStore<unknown>>();
function once<T>(key: string, make: () => QueryStore<T>): QueryStore<T> {
  if (!cache.has(key)) cache.set(key, make() as QueryStore<unknown>);
  return cache.get(key) as QueryStore<T>;
}
const link = () => daemonStore().getState();

export const epicsStore = () => once("epics", () => createEpicsStore(link()));
export const registryStore = () => once("registry", () => createRegistryStore(link()));
export const mcpStore = () => once("mcp", () => createMcpStore(link()));
export const podsStore = () => once("pods", () => createPodsStore(link()));
export const noticeKindsStore = () => once("notice-kinds", () => createNoticeKindsStore(link()));
export const scopedStore = () => once("scoped", () => createScopedSettingsStore(link()));

/**
 * A plugin's state frame the Rust daemon may not serve yet (account, sites). The answer is kept as raw JSON for the panel
 * to read; a refusal is shown as is. Request and answer share the frame name.
 */
export const probeStore = (frame: string) =>
  once<Record<string, unknown> | null>(`probe:${frame}`, () =>
    createQueryStore<Record<string, unknown> | null>(link(), {
      prefix: `probe-${frame}`,
      request: frame,
      body: () => "{}",
      answers: [frame],
      empty: null,
      parse: (raw) => (raw && typeof raw === "object" ? (raw as Record<string, unknown>) : null),
      own: [frame],
    }),
  );

export function useQuery<T, R>(store: QueryStore<T>, select: (s: QueryState<T> & QueryActions) => R): R {
  return useStore(store, select);
}

/** Sends [store]'s request when the link opens and whenever [body] changes; [skip] holds it (no project picked yet). */
export function useQueryOnOpen<T>(store: QueryStore<T>, body?: string, skip = false): void {
  const state = useDaemon((s) => s.connection.state);
  useEffect(() => {
    if (state === "open" && !skip) store.getState().refresh(body);
  }, [state, store, body, skip]);
}
