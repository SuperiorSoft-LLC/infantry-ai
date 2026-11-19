// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import { describe, expect, it } from "vitest";
import { createTimelineStore, type Envelope } from "../src";

describe("automation count", () => {
  it("keeps each session's stats from timeline_item and timeline_items", () => {
    const sent: Envelope[] = [];
    const taps = new Set<(e: Envelope) => void>();
    const s = createTimelineStore({ sendFrame: (e) => (sent.push(e), true), onFrame: (l) => (taps.add(l), () => void taps.delete(l)) });
    const push = (e: Envelope) => taps.forEach((t) => t(e));
    s.getState().ask("s1");
    expect(sent[0]).toMatchObject({ type: "timeline_list", data: "s1" });
    const item = { id: "stats-s1", sessionId: "s1", source: "automation-stats", meta: { turnsSaved: "4", wakes: "6", wakesByReason: "mail:5,owner:1", lastWake: "mail" } };
    push({ type: "timeline_items", data: JSON.stringify([{ id: "run-p-1", sessionId: "s1", source: "automation" }, item]) });
    expect(s.getState().stats.s1).toEqual({ sessionId: "s1", turnsSaved: 4, wakes: 6, byReason: { mail: 5, owner: 1 }, lastWake: "mail" });
    push({ type: "timeline_item", data: JSON.stringify({ ...item, meta: { ...item.meta, turnsSaved: "5" } }) });
    expect(s.getState().stats.s1.turnsSaved).toBe(5);
  });
});
