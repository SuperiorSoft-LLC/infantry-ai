// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// ARMORY-2: the updates the taskbar lists. The daemon's `armory_updates` frame (ARMORY-1) answers `{checked, checkedAt, updates,
// warnings}`; an update is an installed APP (or the shell) with a newer version in the apps catalog, never a plugin. Pure, so it
// is tested without a screen: parsing, the store that follows the frame, and the route an entry opens.
import { createStore, type StoreApi } from "zustand/vanilla";
import { json, type Envelope, type FrameLink } from "@infantry/ui-plugin-api";

/** One app with an update, as the daemon reports it. */
export interface AppUpdate {
  id: string;
  name: string;
  /** `app` or `shell`. */
  kind: string;
  installed: string;
  available: string;
}

export const UPDATES_FRAME = "armory_updates";

/** The updates of an `armory_updates` answer; anything malformed is skipped, a plugin id never listed twice. */
export function parseUpdates(data: string): AppUpdate[] {
  const doc = json<{ updates?: unknown }>(data);
  if (!doc || !Array.isArray(doc.updates)) return [];
  const seen = new Set<string>();
  const out: AppUpdate[] = [];
  for (const raw of doc.updates) {
    if (!raw || typeof raw !== "object") continue;
    const u = raw as Record<string, unknown>;
    const id = typeof u.id === "string" ? u.id.trim() : "";
    const available = typeof u.available === "string" ? u.available.trim() : "";
    if (!id || !available || seen.has(id)) continue;
    seen.add(id);
    out.push({
      id,
      name: typeof u.name === "string" && u.name.trim() ? u.name.trim() : id,
      kind: typeof u.kind === "string" ? u.kind : "app",
      installed: typeof u.installed === "string" ? u.installed.trim() : "",
      available,
    });
  }
  return out;
}

/** The route the Apps app is opened at to show [u]'s page (see `LayoutViewProps.View`'s `route`). */
export const appRoute = (u: Pick<AppUpdate, "id">): string => `app/${u.id}`;

/** "1.0.0 → 1.1.0", or just the new version when the installed one is unknown. */
export const versionLine = (u: AppUpdate): string => (u.installed ? `${u.installed} → ${u.available}` : `→ ${u.available}`);

export interface UpdatesState {
  updates: AppUpdate[];
}

export type UpdatesStore = StoreApi<UpdatesState & { dispose(): void }>;

/**
 * Follows the daemon's updates: asks once now and again whenever the link (re)opens (`ready`), and takes every
 * `armory_updates` frame the daemon pushes after a check. A daemon that has no armory unit refuses the frame; the list stays empty.
 */
export function createUpdatesStore(link: FrameLink): UpdatesStore {
  const ask = (): void => void link.sendFrame({ type: UPDATES_FRAME, data: "{}", requestId: `desk-upd:${Date.now().toString(36)}` });
  const off = link.onFrame((env: Envelope) => {
    if (env.type === "ready") ask();
    else if (env.type === UPDATES_FRAME) store.setState({ updates: parseUpdates(env.data) });
  });
  const store: UpdatesStore = createStore<UpdatesState & { dispose(): void }>()(() => ({ updates: [], dispose: off }));
  ask();
  return store;
}
