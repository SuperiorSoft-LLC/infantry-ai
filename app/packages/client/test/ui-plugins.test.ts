// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import { describe, expect, it } from "vitest";
import { createPluginsStore, createUiPluginsStore, orderOf, parsePlugin, parseWebHalf, slotFills, type Envelope, type PluginRow, type UiActivateContext, type UiLoaderDeps } from "../src";

function link() {
  const sent: Envelope[] = [];
  const taps = new Set<(e: Envelope) => void>();
  return {
    sent,
    taps,
    sendFrame: (e: Envelope) => (sent.push(e), true),
    onFrame: (l: (e: Envelope) => void) => (taps.add(l), () => void taps.delete(l)),
    push: (e: Envelope) => taps.forEach((t) => t(e)),
  };
}

const web = (id: string, over: object = {}) => ({
  apiVersion: 1,
  entry: `/ui-plugins/${id}/ui/dist/index.js`,
  styles: [`/ui-plugins/${id}/ui/dist/index.css`],
  contributes: [{ slot: "tab", id: `${id.toLowerCase()}.tab`, label: "Tab" }],
  rev: "r1",
  ...over,
});

const row = (id: string, enabled: boolean, w: object | null = web(id)): PluginRow =>
  parsePlugin({ id, tier: "official", enabled, status: "ui only", ui: { tabs: [], panels: [], fills: [], slots: [], config: false, web: w } })!;

/** A module the fake host "imports": what the test wants activate to do. */
type Activate = (ctx: UiActivateContext) => unknown;

function host(modules: Record<string, { apiVersion?: number; activate?: Activate } | Error>) {
  const l = link();
  const reads: string[] = [];
  const styles = new Map<string, string[]>();
  let gate: Promise<void> = Promise.resolve();
  const deps: UiLoaderDeps = {
    apiVersion: 1,
    link: l,
    async read(path) {
      reads.push(path);
      await gate;
      if (path.includes("/Missing/")) throw new Error(`GET ${path}: 404`);
      return path.endsWith(".css") ? `/* ${path} */` : path.split("/")[2];
    },
    async importModule(source) {
      const m = modules[source];
      if (m instanceof Error) throw m;
      return { default: m };
    },
    addStyle(id, css) {
      styles.set(id, [...(styles.get(id) ?? []), css]);
      return () => styles.set(id, (styles.get(id) ?? []).filter((c) => c !== css));
    },
    now: () => 1000,
  };
  return { l, reads, styles, deps, hold: () => { let open!: () => void; gate = new Promise((r) => (open = r)); return () => open(); } };
}

const settle = () => new Promise((r) => setTimeout(r, 0));

describe("the web half of a plugin row", () => {
  it("is read from ui.web, and refused outside /ui-plugins/", () => {
    expect(parseWebHalf(web("Acme"))).toMatchObject({ apiVersion: 1, entry: "/ui-plugins/Acme/ui/dist/index.js", rev: "r1" });
    expect(parseWebHalf(web("Acme"))!.contributes[0]).toMatchObject({ slot: "tab", id: "acme.tab" });
    expect(parseWebHalf({ ...web("Acme"), entry: "https://elsewhere.example/x.js" })).toBeUndefined();
    expect(parseWebHalf({ ...web("Acme"), styles: ["/etc/passwd", "/ui-plugins/Acme/a.css"] })!.styles).toEqual(["/ui-plugins/Acme/a.css"]);
    expect(row("Acme", true, null).ui?.web).toBeUndefined();
  });

  it("re-reads the plugin list when a plugin is enabled or disabled anywhere", () => {
    const l = link();
    const s = createPluginsStore(l);
    s.getState().refresh();
    l.push({ type: "plugins", requestId: l.sent[0].requestId, data: "[]" });
    l.push({ type: "plugins_changed", data: JSON.stringify({ id: "Acme", enabled: false }) });
    expect(l.sent.map((e) => e.type)).toEqual(["plugins_op", "plugins_op"]);
  });
});

describe("the web UI plugin loader", () => {
  it("reads, imports and activates an enabled plugin, with its stylesheet and views", async () => {
    let seen: UiActivateContext | null = null;
    const h = host({ Acme: { apiVersion: 1, activate: (ctx) => ((seen = ctx), ctx.register("acme.tab", { render: () => "hi" })) } });
    const s = createUiPluginsStore(h.deps);
    s.getState().sync([row("Acme", true)]);
    expect(s.getState().entries.Acme.state).toBe("loading");
    await settle();
    const e = s.getState().entries.Acme;
    expect(e.state).toBe("ready");
    expect(e.loadMs).toBe(0);
    expect(h.reads).toEqual(["/ui-plugins/Acme/ui/dist/index.js", "/ui-plugins/Acme/ui/dist/index.css"]);
    expect(h.styles.get("Acme")).toEqual(["/* /ui-plugins/Acme/ui/dist/index.css */"]);
    expect(seen!.id).toBe("Acme");
    expect(seen!.apiVersion).toBe(1);
    const fills = slotFills(s.getState().entries, "tab");
    expect(fills.map((f) => [f.plugin, f.contribution.id, f.entry.state])).toEqual([["Acme", "acme.tab", "ready"]]);
    expect(fills[0].entry.views["acme.tab"]).toBeDefined();
    expect(slotFills(s.getState().entries, "tab", "Other")).toEqual([]);
  });

  it("refuses a plugin built for another UI API before reading it, naming both versions", async () => {
    const h = host({});
    const s = createUiPluginsStore(h.deps);
    s.getState().sync([row("Acme", true, web("Acme", { apiVersion: 2 }))]);
    await settle();
    expect(h.reads).toEqual([]);
    expect(s.getState().entries.Acme).toMatchObject({ state: "failed", fault: { phase: "version" } });
    expect(s.getState().entries.Acme.fault!.message).toBe("Acme needs UI API 2; this app provides UI API 1. Update the app to use it.");
  });

  it("refuses a module whose own build says another UI API", async () => {
    const h = host({ Acme: { apiVersion: 0, activate: () => {} } });
    const s = createUiPluginsStore(h.deps);
    s.getState().sync([row("Acme", true)]);
    await settle();
    expect(s.getState().entries.Acme.fault).toMatchObject({ phase: "version", message: "Acme needs UI API 0; this app provides UI API 1. Update the plugin to use it." });
  });

  it("keeps a fetch, import or activate failure as the plugin's fault and loads the others", async () => {
    const h = host({
      Bad: new Error("SyntaxError: Unexpected token"),
      Boom: { apiVersion: 1, activate: () => { throw new Error("activate blew up"); } },
      Stray: { apiVersion: 1, activate: (ctx) => ctx.register("not.declared", { render: () => null }) },
      Good: { apiVersion: 1, activate: (ctx) => ctx.register("good.tab", { render: () => null }) },
    });
    const s = createUiPluginsStore(h.deps);
    s.getState().sync([row("Missing", true), row("Bad", true), row("Boom", true), row("Stray", true), row("Good", true)]);
    await settle();
    const f = (id: string) => s.getState().entries[id].fault;
    expect(f("Missing")).toMatchObject({ phase: "fetch", message: "GET /ui-plugins/Missing/ui/dist/index.js: 404" });
    expect(f("Bad")).toMatchObject({ phase: "import", message: "SyntaxError: Unexpected token" });
    expect(f("Boom")).toMatchObject({ phase: "activate", message: "activate blew up" });
    expect(f("Stray")!.message).toContain('registered "not.declared"');
    expect(s.getState().entries.Good.state).toBe("ready");
    // A failed activate leaves nothing behind: its stylesheet is gone again.
    expect(h.styles.get("Boom")).toEqual([]);
  });

  it("unloads a disabled plugin at once: disposers run, listeners and styles go, sends stop", async () => {
    const calls: string[] = [];
    let ctxLink: UiActivateContext["link"] | null = null;
    const h = host({
      Acme: {
        apiVersion: 1,
        activate: (ctx) => {
          ctxLink = ctx.link;
          ctx.link.onFrame((e) => calls.push(e.type));
          ctx.onDispose(() => calls.push("disposed"));
          ctx.register("acme.tab", { render: () => null });
          return () => calls.push("returned disposer");
        },
      },
    });
    const s = createUiPluginsStore(h.deps);
    const before = h.l.taps.size;
    s.getState().sync([row("Acme", true)]);
    await settle();
    h.l.push({ type: "ping", data: "" });
    s.getState().sync([row("Acme", false)]);
    h.l.push({ type: "after", data: "" });
    expect(calls).toEqual(["ping", "returned disposer", "disposed"]);
    expect(s.getState().entries.Acme).toBeUndefined();
    expect(h.l.taps.size).toBe(before);
    expect(h.styles.get("Acme")).toEqual([]);
    expect(ctxLink!.sendFrame({ type: "x", data: "" })).toBe(false);
    expect(slotFills(s.getState().entries, "tab")).toEqual([]);
  });

  it("drops a load that a disable overtook, and reloads a new build in place of the old", async () => {
    const activated: string[] = [];
    const h = host({ Acme: { apiVersion: 1, activate: (ctx) => (activated.push("v"), ctx.register("acme.tab", { render: () => null }), () => activated.push("old gone")) } });
    const s = createUiPluginsStore(h.deps);
    const open = h.hold();
    s.getState().sync([row("Acme", true)]);
    s.getState().sync([]);
    open();
    await settle();
    expect(activated).toEqual([]);
    expect(s.getState().entries).toEqual({});

    s.getState().sync([row("Acme", true)]);
    await settle();
    s.getState().sync([row("Acme", true)]);
    await settle();
    expect(activated).toEqual(["v"]);
    s.getState().sync([row("Acme", true, web("Acme", { rev: "r2" }))]);
    await settle();
    expect(activated).toEqual(["v", "old gone", "v"]);
    expect(s.getState().entries.Acme.web.rev).toBe("r2");
  });

  it("keeps a render fault and loads the plugin again on retry", async () => {
    let n = 0;
    const h = host({ Acme: { apiVersion: 1, activate: (ctx) => ((n += 1), ctx.register("acme.tab", { render: () => null })) } });
    const s = createUiPluginsStore(h.deps);
    s.getState().sync([row("Acme", true)]);
    await settle();
    s.getState().fault("Acme", "render", new Error("render threw"));
    expect(s.getState().entries.Acme.fault).toMatchObject({ phase: "render", message: "render threw" });
    s.getState().retry("Acme");
    await settle();
    expect(n).toBe(2);
    expect(s.getState().entries.Acme).toMatchObject({ state: "ready", fault: null });
  });

  it("lists a slot's fills by id (a plugin names no position), with plugins still loading or failed, so the app can draw their state", async () => {
    const card = (id: string) => ({ slot: "settings.card", id });
    const h = host({
      Late: { apiVersion: 1, activate: (ctx) => ctx.register("late.card", { render: () => "late" }) },
      Early: { apiVersion: 1, activate: (ctx) => ctx.register("early.card", { render: () => "early" }) },
      Broken: new Error("no module"),
    });
    const s = createUiPluginsStore(h.deps);
    s.getState().sync([
      row("Late", true, web("Late", { contributes: [card("late.card")] })),
      row("Early", true, web("Early", { contributes: [card("early.card"), { slot: "tab", id: "early.tab" }] })),
      row("Broken", true, web("Broken", { contributes: [card("broken.card")] })),
    ]);
    expect(slotFills(s.getState().entries, "settings.card").map((f) => f.entry.state)).toEqual(["loading", "loading", "loading"]);
    await settle();
    const fills = slotFills(s.getState().entries, "settings.card");
    expect(fills.map((f) => [f.contribution.id, f.entry.state])).toEqual([
      ["broken.card", "failed"],
      ["early.card", "ready"],
      ["late.card", "ready"],
    ]);
    expect(slotFills(s.getState().entries, "settings.card", "Early").map((f) => f.contribution.id)).toEqual(["early.card"]);
    expect(orderOf({})).toBeGreaterThan(orderOf({ order: 1e9 }));
    expect(orderOf({ order: Number.NaN })).toBe(orderOf({}));
    expect(parseWebHalf(web("Acme"))!.contributes[0]).not.toHaveProperty("order");
  });
});
