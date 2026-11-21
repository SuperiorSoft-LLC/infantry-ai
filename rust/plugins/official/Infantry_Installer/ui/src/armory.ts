// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// ARMORY-3: the Update button of an Armory app page. The daemon's `armory_updates` frame lists the installed apps (and the
// shell) that have a newer version; `armory_update {id}` installs one and `armory_progress` reports it. What happens after
// the install depends on the restart class the catalog gives the app:
//   none      -> the app's window closes and reopens on the new version (`reopenKey` changes; nothing restarts);
//   daemon    -> the daemon restarts alone: the page says "reconnecting" until the link is back, then reads the list again;
//   relaunch  -> the shell: its own updater downloads in the background, then a "Relaunch to update" click relaunches. It
//                never relaunches without that click.
// A failure keeps the old version and shows as an error (the daemon also posts it on the notification bus).
import { createStore, type StoreApi } from "zustand/vanilla";
import { json, type Envelope, type FrameLink, type ShellUpdater } from "@infantry/ui-plugin-api";

export interface AppUpdate {
  id: string;
  name: string;
  kind: string;
  installed: string;
  available: string;
  notes: string;
  /** `none`, `daemon` or `relaunch`. */
  restart: string;
}

/** `idle`, `updating` (installing, with a percent), `downloading` (the shell), `ready` (shell downloaded: waits for a click),
 * `reconnecting` (daemon restart), `done`, `failed`. */
export type Phase = "idle" | "updating" | "downloading" | "ready" | "reconnecting" | "done" | "failed";

export interface AppProgress {
  phase: Phase;
  percent: number;
  error: string | null;
  /** The version the app is at after `done`. */
  version: string | null;
}

export interface ArmoryState {
  updates: AppUpdate[];
  progress: Record<string, AppProgress>;
  /** Changes whenever an app is to be reopened: the page keys the app's window on it, so it closes and opens again. */
  reopenKey: number;
}

export interface ArmoryActions {
  refresh(): void;
  update(id: string): void;
  /** The shell only, after `ready`: installs the downloaded update and restarts the app. */
  relaunch(): void;
  dispose(): void;
}

export type ArmoryStore = StoreApi<ArmoryState & ArmoryActions>;

export const UPDATES = "armory_updates";
export const PROGRESS = "armory_progress";
const IDLE: AppProgress = { phase: "idle", percent: 0, error: null, version: null };

/** The updates in an `armory_updates` answer; malformed entries are skipped. */
export function parseUpdates(data: string): AppUpdate[] {
  const doc = json<{ updates?: unknown }>(data);
  if (!doc || !Array.isArray(doc.updates)) return [];
  const str = (v: unknown) => (typeof v === "string" ? v.trim() : "");
  const out: AppUpdate[] = [];
  for (const raw of doc.updates) {
    if (!raw || typeof raw !== "object") continue;
    const u = raw as Record<string, unknown>;
    const id = str(u.id);
    const available = str(u.available);
    if (!id || !available || out.some((o) => o.id === id)) continue;
    out.push({ id, name: str(u.name) || id, kind: str(u.kind) || "app", installed: str(u.installed), available, notes: str(u.notes), restart: str(u.restart) || "none" });
  }
  return out;
}

export const progressOf = (s: Pick<ArmoryState, "progress">, id: string): AppProgress => s.progress[id] ?? IDLE;

/** Busy while an update runs or waits on a click: another update must not start. */
export const isBusy = (p: AppProgress): boolean => p.phase === "updating" || p.phase === "downloading" || p.phase === "reconnecting";

export function createArmoryStore(link: FrameLink, shell: () => ShellUpdater | null, reload: () => void): ArmoryStore {
  return createStore<ArmoryState & ArmoryActions>()((set, get) => {
    const put = (id: string, p: Partial<AppProgress>) => set((s) => ({ progress: { ...s.progress, [id]: { ...progressOf(s, id), ...p } } }));
    const ask = () => link.sendFrame({ type: "armory_check", data: "" });
    const off = link.onFrame((env: Envelope) => {
      if (env.type === "ready") {
        // The link is back (first open, or after a daemon restart): a waiting daemon-class update is finished.
        const waiting = Object.entries(get().progress).filter(([, p]) => p.phase === "reconnecting");
        for (const [id] of waiting) put(id, { phase: "done", percent: 100 });
        if (waiting.length > 0) reload();
        ask();
        return;
      }
      if (env.type === UPDATES) return set({ updates: parseUpdates(env.data) });
      if (env.type !== PROGRESS) return;
      const d = json<{ id?: string; stage?: string; percent?: number; action?: string; version?: string; error?: string }>(env.data);
      if (!d?.id) return;
      if (d.stage === "failed") return put(d.id, { phase: "failed", percent: 0, error: d.error ?? "the update failed" });
      if (d.stage === "done") {
        if (d.action === "reconnect") return put(d.id, { phase: "reconnecting", percent: 100, version: d.version ?? null });
        put(d.id, { phase: "done", percent: 100, version: d.version ?? null });
        reload();
        return set((s) => ({ reopenKey: s.reopenKey + 1 }));
      }
      put(d.id, { phase: "updating", percent: Math.max(0, Math.min(100, d.percent ?? 0)), error: null });
    });
    return {
      updates: [],
      progress: {},
      reopenKey: 0,
      refresh: () => void ask(),
      update(id) {
        const u = get().updates.find((x) => x.id === id);
        if (!u || isBusy(progressOf(get(), id))) return;
        if (u.kind === "shell" || u.restart === "relaunch") {
          const updater = shell();
          if (!updater) return put(id, { phase: "failed", error: "this window cannot update the app itself" });
          put(id, { phase: "downloading", percent: 0, error: null });
          updater
            .check()
            .then((c) => (c.available ? updater.download() : Promise.reject(new Error("the update feed has no newer version"))))
            .then((v) => put(id, { phase: "ready", percent: 100, version: v }))
            .catch((e: unknown) => put(id, { phase: "failed", percent: 0, error: e instanceof Error ? e.message : String(e) }));
          return;
        }
        put(id, { phase: "updating", percent: 0, error: null });
        if (!link.sendFrame({ type: "armory_update", data: JSON.stringify({ id }), requestId: `armory:${id}` })) {
          put(id, { phase: "failed", error: "armory_update not sent: the daemon link is down" });
        }
      },
      relaunch() {
        const id = Object.entries(get().progress).find(([, p]) => p.phase === "ready")?.[0];
        const updater = shell();
        if (!id || !updater) return;
        updater.relaunch().catch((e: unknown) => put(id, { phase: "failed", error: e instanceof Error ? e.message : String(e) }));
      },
      dispose: () => off(),
    };
  });
}
