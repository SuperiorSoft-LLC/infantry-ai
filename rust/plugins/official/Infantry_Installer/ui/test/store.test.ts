// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The Installer store (INSTALLER-1): the list it reads, the installs it sends, what a refusal does, and the rules a row's
// buttons follow.
import { describe, expect, it } from "vitest";
import type { Envelope } from "@infantry/ui-plugin-api";
import { createInstallerStore, findApp, groupRows, routeApp, membersOf, rowActions, sortRows, type PluginRow } from "../src/store";

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

const row = (id: string, over: Partial<PluginRow> = {}): PluginRow => ({
  id, displayName: id, description: "", version: "1.0.0", tier: "official", requires: [], bootstrap: false, daemon: false, ui: true,
  source: "/bundle", installed: false, enabled: false, status: "available", ...over,
});

const answer = (l: ReturnType<typeof link>, i: number, a: { code: number; out?: string; err?: string }) =>
  l.push({ type: "plugin_admin", requestId: l.sent[i].requestId, data: JSON.stringify({ code: a.code, out: a.out ?? "", err: a.err ?? "" }) });

describe("the Installer store", () => {
  it("reads the list, installs, and reads the list again", () => {
    const l = link();
    const s = createInstallerStore(l);
    s.getState().refresh();
    expect(JSON.parse(l.sent[0].data)).toEqual({ args: ["available"] });
    answer(l, 0, { code: 0, out: JSON.stringify([row("B_Plugin"), row("A_Installer", { installed: true, enabled: true, bootstrap: true, status: "loaded" })]) });
    expect(s.getState().rows.map((r) => r.id)).toEqual(["A_Installer", "B_Plugin"]);
    expect(s.getState().loaded).toBe(true);

    s.getState().install(["B_Plugin"]);
    expect(JSON.parse(l.sent[1].data)).toEqual({ args: ["install", "B_Plugin"] });
    expect(s.getState().busy).toBe("Installing B_Plugin");
    answer(l, 1, { code: 0, out: "installed B_Plugin" });
    expect(s.getState()).toMatchObject({ busy: null, notice: "installed B_Plugin", refusal: null });
    expect(JSON.parse(l.sent[2].data)).toEqual({ args: ["available"] });
  });

  it("keeps the daemon's refusal and clears it on dismiss", () => {
    const l = link();
    const s = createInstallerStore(l);
    s.getState().uninstall("X");
    answer(l, 0, { code: 1, err: "X is required by Y; uninstall those first" });
    expect(s.getState().refusal).toBe("X is required by Y; uninstall those first");
    expect(s.getState().busy).toBeNull();
    s.getState().clearRefusal();
    expect(s.getState().refusal).toBeNull();
    s.getState().install(["Z"]);
    l.push({ type: "error", requestId: l.sent[l.sent.length - 1].requestId, data: "plugin_admin refused: owner only" });
    expect(s.getState().refusal).toBe("plugin_admin refused: owner only");
  });

  it("says so when the link is down, and ignores answers it did not ask for", () => {
    const down = createInstallerStore(link(false));
    down.getState().install(["Z"]);
    expect(down.getState().refusal).toMatch(/link is down/);
    const l = link();
    const s = createInstallerStore(l);
    l.push({ type: "plugin_admin", requestId: "someone-else", data: JSON.stringify({ code: 1, out: "", err: "no" }) });
    expect(s.getState().refusal).toBeNull();
  });

  it("re-reads the list when a plugin changes elsewhere, once it has been read", () => {
    const l = link();
    const s = createInstallerStore(l);
    l.push({ type: "plugins_changed", data: "{}" });
    expect(l.sent).toHaveLength(0);
    s.getState().refresh();
    answer(l, 0, { code: 0, out: "[]" });
    l.push({ type: "plugins_changed", data: JSON.stringify({ id: "Q", enabled: true }) });
    expect(l.sent).toHaveLength(2);
  });
});

describe("the rows", () => {
  it("offers Install only for what a source has, and never removes the installer", () => {
    expect(rowActions(row("A"))).toEqual({ install: true, uninstall: false, toggle: false });
    expect(rowActions(row("A", { source: null }))).toEqual({ install: false, uninstall: false, toggle: false });
    expect(rowActions(row("A", { installed: true }))).toEqual({ install: false, uninstall: true, toggle: true });
    expect(rowActions(row("A", { installed: true, bootstrap: true }))).toEqual({ install: false, uninstall: false, toggle: false });
  });

  it("lists installed rows first by name", () => {
    const rows = [row("Z_Last"), row("B_Done", { installed: true }), row("A_Next"), row("Gone", { source: null })];
    expect(sortRows(rows).map((r) => r.id)).toEqual(["B_Done", "A_Next", "Gone", "Z_Last"]);
  });
});

describe("the apps screen groups (APPS-ROOT-1)", () => {
  const rows = [
    row("Sys_Installer", { bootstrap: true, installed: true }),
    row("Big_App", { app: { core: ["Core_One", "Gone_Core"], optional: ["Opt_One"] } }),
    row("Core_One", { memberOf: [{ app: "Big_App", kind: "core" }] }),
    row("Opt_One", { memberOf: [{ app: "Big_App", kind: "optional" }] }),
    row("Stray_Plugin"),
  ];

  it("shows apps, the system installer, and the plugins that belong to no app; members stay inside their app", () => {
    const g = groupRows(rows);
    expect(g.system.map((r) => r.id)).toEqual(["Sys_Installer"]);
    expect(g.apps.map((r) => r.id)).toEqual(["Big_App"]);
    expect(g.other.map((r) => r.id)).toEqual(["Stray_Plugin"]);
  });

  it("resolves an app's core and optional members from the list", () => {
    const m = membersOf(rows[1], rows);
    expect(m.core.map((r) => r.id)).toEqual(["Core_One"]);
    expect(m.optional.map((r) => r.id)).toEqual(["Opt_One"]);
  });
});

describe("routes (ARMORY-2)", () => {
  it("names the app a route opens, and nothing for the list", () => {
    expect(routeApp("app/tower")).toBe("tower");
    expect(routeApp("app/")).toBeNull();
    expect(routeApp("elsewhere")).toBeNull();
    expect(routeApp(undefined)).toBeNull();
    const rows = [row("tower"), row("other")];
    expect(findApp(rows, "tower")?.id).toBe("tower");
    expect(findApp(rows, "gone")).toBeNull();
  });
});
