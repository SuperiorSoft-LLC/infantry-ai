// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import { describe, expect, it } from "vitest";
import { createHealthStore, createPluginsStore, healthSummary, isFault, parseCatalog, parsePlugin, searchCatalog, slotRegistry, type Envelope } from "../src";

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
});

describe("plugin manager: sources, catalog, install, update", () => {
  const answer = (l: ReturnType<typeof link>, i: number, out: unknown, code = 0) => {
    const sent = l.sent[i];
    l.push({ type: "plugin_admin", requestId: sent.requestId, data: JSON.stringify({ code, out: typeof out === "string" ? out : JSON.stringify(out), err: "" }) } as Envelope);
  };
  const argsOf = (e: Envelope) => (JSON.parse(e.data) as { args: string[] }).args;

  it("readManager asks for the catalog, the sources and the updates and keeps each answer", () => {
    const l = link();
    const s = createPluginsStore(l);
    s.getState().readManager();
    expect(l.sent.map((e) => argsOf(e))).toEqual([["available"], ["sources"], ["updates"]]);
    answer(l, 0, [{ id: "Acme", displayName: "Acme", version: "1.0", signed: "untrusted", requires: ["http"], source: "/src/a", installed: false }]);
    answer(l, 1, [{ id: "src-a", kind: "git", location: "https://x/y.git", offers: 2 }, { nope: 1 }]);
    answer(l, 2, [{ id: "Acme", installed: "1.0", available: "1.1", source: "/src/a" }]);
    const st = s.getState();
    expect(st.catalog[0]).toMatchObject({ id: "Acme", signed: "untrusted", requires: ["http"], installed: false });
    expect(st.sources).toEqual([{ id: "src-a", kind: "git", location: "https://x/y.git", offers: 2 }]);
    expect(st.updates).toEqual([{ id: "Acme", installed: "1.0", available: "1.1", source: "/src/a" }]);
  });

  it("sends add/remove/refresh source, install, update (with --approve) and uninstall as plugin_admin args", () => {
    const l = link();
    const s = createPluginsStore(l);
    s.getState().addSource(" /plugins ");
    s.getState().removeSource("src-a");
    s.getState().refreshSource("src-a");
    s.getState().install("Acme");
    s.getState().install("Acme", true);
    s.getState().update("Acme", true);
    s.getState().uninstall("Acme");
    expect(l.sent.map(argsOf)).toEqual([
      ["source-add", "/plugins"],
      ["source-remove", "src-a"],
      ["source-refresh", "src-a"],
      ["install", "Acme"],
      ["install", "Acme", "--approve"],
      ["update", "Acme", "--approve"],
      ["uninstall", "Acme"],
    ]);
    expect(s.getState().pending).toContain("Acme");
  });

  it("shows the daemon's refusal and frees the card", () => {
    const l = link();
    const s = createPluginsStore(l);
    s.getState().install("Acme");
    l.push({ type: "plugin_admin", requestId: l.sent[0].requestId, data: JSON.stringify({ code: 1, out: "", err: "Acme is signed by a key that is not a trusted signer" }) } as Envelope);
    expect(s.getState().pending).toEqual([]);
    expect(s.getState().refusal).toContain("not a trusted signer");
    expect(l.sent.map(argsOf).slice(1)).toEqual(expect.arrayContaining([["available"], ["sources"], ["updates"]]));
  });

  it("searches the catalog by id, name and description", () => {
    const rows = parseCatalog(JSON.stringify([{ id: "Kanban", displayName: "Board", description: "Tickets" }, { id: "Mail", displayName: "Inbox", description: "Letters" }]));
    expect(searchCatalog(rows, "tick").map((r) => r.id)).toEqual(["Kanban"]);
    expect(searchCatalog(rows, "  ").length).toBe(2);
    expect(searchCatalog(rows, "zzz")).toEqual([]);
  });
});
