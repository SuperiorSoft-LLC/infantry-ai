// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The automation count of each session (SCRIPT-VIS-1): the daemon pushes a `timeline_item` with source
// "automation-stats" per session (turns saved, wakes, wakes by reason) and answers `timeline_list` "<sessionId>" with
// `timeline_items`. Only the stats items are kept here; the automation run items stay with the transcript.
import { createStore, type StoreApi } from "zustand/vanilla";
import { json, type Envelope } from "../wire";
import type { FrameLink } from "./link";

export const AUTOMATION_STATS_SOURCE = "automation-stats";

export interface AutomationStats {
  sessionId: string;
  turnsSaved: number;
  wakes: number;
  /** Wakes by reason, e.g. {mail: 3, owner: 1}. */
  byReason: Record<string, number>;
  lastWake?: string;
}

export interface TimelineState {
  stats: Record<string, AutomationStats>;
}

export interface TimelineActions {
  /** Asks `timeline_list` for [sessionId]'s items. */
  ask(sessionId: string): void;
  dispose(): void;
}

export type TimelineStore = StoreApi<TimelineState & TimelineActions>;

/** The automation count a `timeline_item` [raw] carries, or null when it is not a stats item. */
export function automationStatsOf(raw: unknown): AutomationStats | null {
  const o = raw as { source?: string; sessionId?: string; meta?: Record<string, string> } | null;
  if (!o || o.source !== AUTOMATION_STATS_SOURCE || !o.sessionId) return null;
  const m = o.meta ?? {};
  const n = (v?: string) => (Number.isFinite(Number(v)) ? Number(v) : 0);
  const byReason: Record<string, number> = {};
  for (const part of (m.wakesByReason ?? "").split(",")) {
    const [k, v] = part.split(":");
    if (k?.trim()) byReason[k.trim()] = n(v);
  }
  return { sessionId: o.sessionId, turnsSaved: n(m.turnsSaved), wakes: n(m.wakes), byReason, lastWake: m.lastWake || undefined };
}

export function createTimelineStore(link: FrameLink): TimelineStore {
  let counter = 0;
  return createStore<TimelineState & TimelineActions>()((set, get) => {
    const take = (raw: unknown) => {
      const s = automationStatsOf(raw);
      if (s) set({ stats: { ...get().stats, [s.sessionId]: s } });
    };
    const off = link.onFrame((env: Envelope) => {
      if (env.type === "timeline_item") take(json<unknown>(env.data));
      else if (env.type === "timeline_items") {
        const list = json<unknown[]>(env.data);
        if (Array.isArray(list)) list.forEach(take);
      }
    });
    return {
      stats: {},
      ask(sessionId) {
        counter += 1;
        link.sendFrame({ type: "timeline_list", data: sessionId, requestId: `timeline:${counter.toString(36)}` });
      },
      dispose: off,
    };
  });
}
