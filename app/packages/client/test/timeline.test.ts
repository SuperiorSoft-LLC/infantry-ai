// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import { describe, expect, it } from "vitest";
import { automationItemOf, createTimelineStore, mergeAutomation, type Envelope } from "../src";

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

describe("automation cards", () => {
  const make = () => {
    const taps = new Set<(e: Envelope) => void>();
    const s = createTimelineStore({ sendFrame: () => true, onFrame: (l) => (taps.add(l), () => void taps.delete(l)) });
    return { s, push: (e: Envelope) => taps.forEach((t) => t(e)) };
  };

  it("keeps chat notes and images per session, one per id, oldest first", () => {
    const { s, push } = make();
    const note = { id: "note-p-20-1", sessionId: "s1", at: 20, source: "automation", label: "AUTOMATION · note", text: "AUTOMATION · QA NOTE PROBE", ok: true, meta: { level: "info" } };
    const img = { ...note, id: "note-p-10-2", at: 10, text: "AUTOMATION · shot", meta: { level: "ok", image: "C:/p/.infantry/uploads/chat/abc.png" } };
    push({ type: "timeline_item", data: JSON.stringify(note) });
    push({ type: "timeline_items", data: JSON.stringify([img, note]) });
    const list = s.getState().items.s1;
    expect(list.map((i) => i.id)).toEqual(["note-p-10-2", "note-p-20-1"]);
    expect(list[0]).toMatchObject({ level: "ok", image: { path: "C:/p/.infantry/uploads/chat/abc.png", id: "abc.png" } });
    expect(list[1]).toMatchObject({ text: "AUTOMATION · QA NOTE PROBE", level: "info", image: undefined });
    expect(s.getState().stats.s1).toBeUndefined();
  });

  it("places cards among transcript items by time", () => {
    const card = (id: string, at: number) => automationItemOf({ id, sessionId: "s", at, source: "automation" })!;
    const items = [{ id: "u", at: 100 }, { id: "t" }, { id: "u2", at: 300 }];
    const rows = mergeAutomation(items, [card("a", 50), card("b", 200), card("c", 400)]);
    expect(rows.map((r) => r.item?.id ?? r.auto?.id)).toEqual(["a", "u", "t", "b", "u2", "c"]);
    expect(mergeAutomation(items, []).map((r) => r.item?.id)).toEqual(["u", "t", "u2"]);
  });
});
