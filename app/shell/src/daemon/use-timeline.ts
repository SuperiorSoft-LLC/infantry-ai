// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The one automation-count store of this window (@infantry/client createTimelineStore), bound to the daemon link.
import { useEffect } from "react";
import { useStore } from "zustand";
import { createTimelineStore, type AutomationStats, type TimelineStore } from "@infantry/client";
import { daemonStore, useDaemon } from "./daemon";

let store: TimelineStore | null = null;

export function timelineStore(): TimelineStore {
  return (store ??= createTimelineStore(daemonStore().getState()));
}

/** [sessionId]'s automation count; asks `timeline_list` once the link is open. */
export function useAutomationStats(sessionId: string): AutomationStats | undefined {
  const state = useDaemon((s) => s.connection.state);
  useEffect(() => {
    if (state === "open") timelineStore().getState().ask(sessionId);
  }, [state, sessionId]);
  return useStore(timelineStore(), (s) => s.stats[sessionId]);
}
