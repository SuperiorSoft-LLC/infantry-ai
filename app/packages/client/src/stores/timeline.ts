// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The automation count of each session (SCRIPT-VIS-1): the daemon pushes a `timeline_item` with source
// "automation-stats" per session (turns saved, wakes, wakes by reason) and answers `timeline_list` "<sessionId>" with
// `timeline_items`. The stats items are kept per session, and so are the automation cards (source "automation": script
// runs, `infantry_chat_note` lines and `infantry_chat_image` images), which the session chat draws among its rows.
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

export const AUTOMATION_SOURCE = "automation";

/** One automation card of a session's chat: a script run, a chat note or a chat image. */
export interface AutomationItem {
  id: string;
  sessionId: string;
  /** Epoch ms. */
  at: number;
  label: string;
  text: string;
  ok: boolean;
  /** info | ok | warn | fail (notes); runs carry only `ok`. */
  level?: string;
  /** The stored image of an `infantry_chat_image` card. */
  image?: { path: string; id: string };
  details: string[];
}

export interface TimelineState {
  stats: Record<string, AutomationStats>;
  /** Each session's automation cards, oldest first, one per id. */
  items: Record<string, AutomationItem[]>;
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

/** The automation card a `timeline_item` [raw] carries, or null when it is not one. */
export function automationItemOf(raw: unknown): AutomationItem | null {
  const o = raw as { id?: string; sessionId?: string; source?: string; at?: number; label?: string; text?: string; ok?: boolean; meta?: Record<string, string>; details?: unknown } | null;
  if (!o || o.source !== AUTOMATION_SOURCE || !o.sessionId || !o.id) return null;
  const m = o.meta ?? {};
  const path = typeof m.image === "string" && m.image ? m.image : undefined;
  return {
    id: o.id,
    sessionId: o.sessionId,
    at: Number.isFinite(Number(o.at)) ? Number(o.at) : 0,
    label: o.label ?? "AUTOMATION",
    text: o.text ?? "",
    ok: o.ok !== false,
    level: m.level || undefined,
    image: path ? { path, id: path.split("/").pop() ?? path } : undefined,
    details: Array.isArray(o.details) ? o.details.map(String) : [],
  };
}

/** One row of a session chat: a transcript item, or an automation card placed by its time. */
export type ChatRow<T> = { item: T; auto?: undefined } | { item?: undefined; auto: AutomationItem };

/**
 * [items] (transcript order) with [autos] (oldest first) placed among them: each card goes before the first item
 * stamped later than it (items without a stamp keep the last stamp seen); cards newer than every item come last.
 */
export function mergeAutomation<T extends { at?: number }>(items: readonly T[], autos: readonly AutomationItem[]): ChatRow<T>[] {
  if (!autos.length) return items.map((item) => ({ item }));
  const out: ChatRow<T>[] = [];
  let a = 0;
  for (const item of items) {
    const at = item.at;
    if (typeof at === "number" && at > 0) while (a < autos.length && autos[a].at < at) out.push({ auto: autos[a++] });
    out.push({ item });
  }
  while (a < autos.length) out.push({ auto: autos[a++] });
  return out;
}

export function createTimelineStore(link: FrameLink): TimelineStore {
  let counter = 0;
  return createStore<TimelineState & TimelineActions>()((set, get) => {
    const take = (raw: unknown) => {
      const s = automationStatsOf(raw);
      if (s) return set({ stats: { ...get().stats, [s.sessionId]: s } });
      const it = automationItemOf(raw);
      if (!it) return;
      const list = (get().items[it.sessionId] ?? []).filter((x) => x.id !== it.id);
      list.push(it);
      list.sort((a, b) => a.at - b.at);
      set({ items: { ...get().items, [it.sessionId]: list } });
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
      items: {},
      ask(sessionId) {
        counter += 1;
        link.sendFrame({ type: "timeline_list", data: sessionId, requestId: `timeline:${counter.toString(36)}` });
      },
      dispose: off,
    };
  });
}
