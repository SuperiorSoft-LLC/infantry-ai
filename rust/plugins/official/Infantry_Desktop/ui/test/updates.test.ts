// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// ARMORY-2: the updates the taskbar lists: what the daemon's `armory_updates` answer becomes, the store that follows it, and the
// route an entry opens.
import { describe, expect, it } from "vitest";
import type { Envelope } from "@infantry/ui-plugin-api";
import { appRoute, createUpdatesStore, parseUpdates, versionLine } from "../src/updates";

function link(up = true) {
  const sent: Envelope[] = [];
  const taps = new Set<(e: Envelope) => void>();
  return {
    sent,
    taps,
    sendFrame: (e: Envelope) => (up ? (sent.push(e), true) : false),
    onFrame: (l: (e: Envelope) => void) => (taps.add(l), () => void taps.delete(l)),
    push: (e: Envelope) => taps.forEach((t) => t(e)),
  };
}

const answer = (updates: unknown[]) => JSON.stringify({ checked: true, checkedAt: 1, updates, warnings: [] });
const tower = { id: "tower", name: "Tower", kind: "app", installed: "1.0.0", available: "2.0.0", notes: "new", restart: "daemon" };

describe("parseUpdates", () => {
  it("reads name, installed and available, falling back to the id for a missing name", () => {
    expect(parseUpdates(answer([tower, { id: "x", available: "1.1.0", installed: "1.0.0" }]))).toEqual([
      { id: "tower", name: "Tower", kind: "app", installed: "1.0.0", available: "2.0.0" },
      { id: "x", name: "x", kind: "app", installed: "1.0.0", available: "1.1.0" },
    ]);
  });

  it("skips what is malformed or repeated, and an answer that is not JSON is an empty list", () => {
    expect(parseUpdates(answer([null, 3, { id: "", available: "1" }, { id: "a" }, tower, tower]))).toHaveLength(1);
    expect(parseUpdates("not json")).toEqual([]);
    expect(parseUpdates(JSON.stringify({ updates: "no" }))).toEqual([]);
    expect(parseUpdates(JSON.stringify({ checked: false, updates: [] }))).toEqual([]);
  });

  it("shows installed → available, or only the new version when the installed one is unknown", () => {
    const [u] = parseUpdates(answer([tower]));
    expect(versionLine(u!)).toBe("1.0.0 → 2.0.0");
    expect(versionLine({ ...u!, installed: "" })).toBe("→ 2.0.0");
    expect(appRoute(u!)).toBe("app/tower");
  });
});

describe("the updates store", () => {
  it("asks at start, again when the link reopens, and follows every pushed answer", () => {
    const l = link();
    const s = createUpdatesStore(l);
    expect(l.sent.map((f) => f.type)).toEqual(["armory_updates"]);
    expect(s.getState().updates).toEqual([]);
    l.push({ type: "armory_updates", data: answer([tower]), requestId: l.sent[0]!.requestId });
    expect(s.getState().updates.map((u) => u.id)).toEqual(["tower"]);
    l.push({ type: "ready", data: "{}" });
    expect(l.sent).toHaveLength(2);
    l.push({ type: "armory_updates", data: answer([]) });
    expect(s.getState().updates).toEqual([]);
  });

  it("stays empty when the link is down, ignores other frames, and stops listening when disposed", () => {
    const down = link(false);
    const s = createUpdatesStore(down);
    expect(s.getState().updates).toEqual([]);
    down.push({ type: "plugins_changed", data: "{}" });
    expect(s.getState().updates).toEqual([]);
    s.getState().dispose();
    expect(down.taps.size).toBe(0);
  });
});
