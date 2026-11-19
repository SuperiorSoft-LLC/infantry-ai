// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import { describe, expect, it } from "vitest";
import { createHealthStore, createPluginsStore, createTriggersStore, healthSummary, isFault, parsePlugin, slotRegistry, type Envelope } from "../src";

function link(up = true) {
  const sent: Envelope[] = [];
  const taps = new Set<(e: Envelope) => void>();
  return {
    sent,
    sendFrame: (e: Envelope) => (up ? (sent.push(e), true) : false),
    onFrame: (l: (e: Envelope) => void) => (taps.add(l), () => void taps.delete(l)),
    push: (e: Envelope) => taps.forEach((t) => t(e)),
  };
}

const row = (id: string, enabled: boolean, ui: object, status = "loaded") => ({ id, tier: "official", enabled, status, ui: { tabs: [], panels: [], fills: [], slots: [], config: false, ...ui } });

describe("slot registry", () => {
  it("collects tabs and fills of enabled plugins, first id wins, fills find their slot by prefix", () => {
    const plugins = [
      row("Chat", true, { slots: [{ id: "chat.composer.actions" }], fills: [{ id: "infantry.shell.center" }] }),
      row("Triggers", true, { tabs: [{ id: "infantry.triggers", container: "center" }] }),
      row("Dup", true, { tabs: [{ id: "infantry.triggers" }], fills: [{ id: "chat.composer.actions.voice" }] }),
      row("Off", false, { tabs: [{ id: "infantry.off" }] }, "not loaded"),
    ].map((r) => parsePlugin(r)!);
    const reg = slotRegistry(plugins);
    expect(reg.tabs.map((t) => [t.plugin, t.decl.id])).toEqual([["Triggers", "infantry.triggers"]]);
    expect(reg.slots["chat.composer.actions"]).toBe("Chat");
    expect(reg.fills["chat.composer.actions"].map((f) => f.plugin)).toEqual(["Dup"]);
    expect(reg.fills["infantry.shell.center"].map((f) => f.plugin)).toEqual(["Chat"]);
  });

  it("accepts string declarations and drops junk rows", () => {
    expect(parsePlugin({ id: "A", ui: { tabs: ["a.tab", 3, { nope: 1 }] } })!.ui!.tabs).toEqual([{ id: "a.tab" }]);
    expect(parsePlugin({ nope: 1 })).toBeNull();
  });

  it("calls a not-loaded daemon half a fault only while enabled", () => {
    expect(isFault({ status: "loaded", enabled: true })).toBe(false);
    expect(isFault({ status: "not loaded", enabled: false })).toBe(false);
    expect(isFault({ status: "failed: boom", enabled: true })).toBe(true);
  });
});

describe("plugins store", () => {
  it("lists, then enables through plugin_admin and re-lists", () => {
    const l = link();
    const s = createPluginsStore(l);
    s.getState().refresh();
    expect(JSON.parse(l.sent[0].data)).toEqual({ op: "list" });
    l.push({ type: "plugins", requestId: l.sent[0].requestId, data: JSON.stringify([row("A", false, {}, "not loaded")]) });
    expect(s.getState().plugins[0].id).toBe("A");
    s.getState().setEnabled("A", true);
    const admin = l.sent[1];
    expect(admin.type).toBe("plugin_admin");
    expect(JSON.parse(admin.data)).toEqual({ args: ["enable", "A"] });
    expect(s.getState().pending).toEqual(["A"]);
    l.push({ type: "plugin_admin", requestId: admin.requestId, data: JSON.stringify({ code: 1, out: "", err: "error: no such plugin" }) });
    expect(s.getState().pending).toEqual([]);
    expect(s.getState().refusal).toBe("error: no such plugin");
    expect(l.sent[2].type).toBe("plugins_op");
  });

  it("ignores another client's plugins answer", () => {
    const l = link();
    const s = createPluginsStore(l);
    l.push({ type: "plugins", requestId: "x", data: JSON.stringify([row("A", true, {})]) });
    expect(s.getState().loaded).toBe(false);
  });
});

describe("health and triggers stores", () => {
  it("summarises a health answer", () => {
    const l = link();
    const s = createHealthStore(l);
    s.getState().refresh();
    l.push({ type: "health", requestId: l.sent[0].requestId, data: JSON.stringify({ at: 1, daemon: { up: true }, plugins: [{ id: "A", status: "loaded" }, { id: "B", status: "failed" }], events: [] }) });
    const sum = healthSummary(s.getState().report);
    expect(sum.tone).toBe("amber");
    expect(sum.faults).toEqual(["B: failed"]);
  });

  it("folds trigger pushes and keeps a refusal", () => {
    const l = link();
    const s = createTriggersStore(l);
    s.getState().refresh();
    const t = { id: "t1", name: "Nightly", kind: "manual", action: { kind: "start_next" }, enabled: true, state: "armed", coalesceMs: 0, ceilingRuns: 6, ceilingWindowMs: 1, createdAt: 1 };
    l.push({ type: "triggers", requestId: l.sent[0].requestId, data: JSON.stringify([t]) });
    l.push({ type: "trigger", data: JSON.stringify({ ...t, state: "tripped" }) });
    expect(s.getState().triggers.map((x) => x.state)).toEqual(["tripped"]);
    s.getState().fire("t1");
    expect(l.sent[1]).toMatchObject({ type: "trigger_fire", data: "t1" });
    l.push({ type: "error", requestId: l.sent[1].requestId, data: "trigger_fire refused: tripped" });
    expect(s.getState().refusal).toBe("trigger_fire refused: tripped");
  });
});
