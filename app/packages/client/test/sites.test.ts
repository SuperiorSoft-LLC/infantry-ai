// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import { describe, expect, it } from "vitest";
import { createStore } from "zustand/vanilla";
import type { QueryStore } from "../src";
import { accountState, manageUrl, serverStatus, shownAnswer, siteDate, startAutoRefresh } from "../src/sites";

describe("server status chips", () => {
  it.each([
    ["active", "online", "green"],
    ["online", "online", "green"],
    ["off", "off", "neutral"],
    ["new", "setting up", "amber"],
    ["setting_up", "setting up", "amber"],
    ["destroying", "destroying", "red"],
  ])("%s", (raw, label, tone) => expect(serverStatus(raw)).toEqual({ label, tone }));
  it("an unknown status shows its raw text", () => expect(serverStatus("rebooting")).toEqual({ label: "rebooting", tone: "neutral" }));
  it("a missing status is ?", () => expect(serverStatus(null).label).toBe("?"));
});

describe("account states", () => {
  it("ok and absent are the normal list", () => {
    expect(accountState({ state: "ok" })).toBeNull();
    expect(accountState({})).toBeNull();
    expect(accountState(null)).toBeNull();
  });
  it.each([
    ["signed_out", "Sign in to Infantry AI Hosting first."],
    ["not_entitled", "Your plan does not include hosting yet."],
    ["limit", "You're at your plan's server limit."],
    ["email_unverified", "Verify your email address to create a server."],
    ["not_configured", "Hosting is not configured."],
    ["unavailable", "Hosting is unavailable right now."],
  ])("%s", (state, text) => expect(accountState({ state })?.text).toBe(text));
  it("not_entitled follows the plan status", () => {
    expect(accountState({ state: "not_entitled", planStatus: "past_due" })?.text).toMatch(/Payment failed/);
    expect(accountState({ state: "not_entitled", planStatus: "canceled" })?.text).toBe("Your subscription ended.");
  });
  it("an unknown state shows its raw text", () => expect(accountState({ state: "weird", message: "hm" })).toMatchObject({ label: "weird", text: "hm" }));
});

describe("dates and link", () => {
  it("reads epoch ms and ISO, never the raw value", () => {
    expect(siteDate("2026-11-21T12:00:00Z")).toMatch(/2026/);
    expect(siteDate(1795262400000)).toMatch(/2026|2027/);
    expect(siteDate("garbage")).toBeNull();
    expect(siteDate("")).toBeNull();
    expect(siteDate(undefined)).toBeNull();
  });
  it("manage link only for http(s)", () => {
    expect(manageUrl({ siteUrl: "https://x.test/sites" })).toBe("https://x.test/sites");
    expect(manageUrl({ siteUrl: "javascript:alert(1)" })).toBeNull();
    expect(manageUrl({})).toBeNull();
  });
});

describe("a failed refresh keeps the last good list", () => {
  const good = { state: "ok", servers: [{ id: "a" }] };
  it("a refusal keeps it, with the error", () => expect(shownAnswer(null, "boom", good)).toEqual({ data: good, error: "boom", stale: true }));
  it("an unavailable answer keeps it", () => {
    const r = shownAnswer({ state: "unavailable", message: "down" }, null, good);
    expect(r).toMatchObject({ data: good, error: "down", stale: true });
  });
  it("with nothing earlier the failure shows as is", () => expect(shownAnswer({ state: "unavailable" }, null, null).stale).toBe(false));
  it("a good answer shows as is", () => expect(shownAnswer(good, null, null)).toEqual({ data: good, error: null, stale: false }));
});

describe("auto refresh", () => {
  const harness = (seconds: number, visible = () => true) => {
    let fn: (() => void) | null = null;
    let cleared = false;
    let t = 0;
    const store = createStore(() => ({ data: null, loaded: false, loading: false, refusal: null as string | null, refresh: () => store.setState({ loading: true }), send: () => true, clearRefusal: () => {}, dispose: () => {} }));
    let sent = 0;
    store.setState({ refresh: () => { sent++; store.setState({ loading: true }); } });
    const r = startAutoRefresh(store as unknown as QueryStore<null>, { seconds, visible, setTimer: (f) => { fn = f; return 1; }, clearTimer: () => { cleared = true; }, now: () => t });
    return { store, r, fire: () => fn?.(), armed: () => fn !== null, sent: () => sent, cleared: () => cleared, at: (ms: number) => { t = ms; } };
  };
  it("0 is off: no timer", () => expect(harness(0).armed()).toBe(false));
  it("refreshes on each tick once answered", () => {
    const h = harness(30);
    h.fire();
    h.store.setState({ loading: false });
    h.fire();
    expect(h.sent()).toBe(2);
  });
  it("never overlaps: a slow answer is not stacked", () => {
    const h = harness(30);
    h.fire();
    h.at(30000);
    h.fire();
    h.fire();
    expect(h.sent()).toBe(1);
  });
  it("gives up on a request unanswered for four periods", () => {
    const h = harness(30);
    h.fire();
    h.at(121000);
    h.fire();
    expect(h.sent()).toBe(2);
  });
  it("hidden: no request", () => {
    let vis = false;
    const h = harness(30, () => vis);
    h.fire();
    expect(h.sent()).toBe(0);
    vis = true;
    h.r.poke();
    expect(h.sent()).toBe(1);
  });
  it("0 is off: becoming visible does not refresh", () => {
    const h = harness(0);
    h.r.poke();
    expect(h.sent()).toBe(0);
  });
  it("stop clears the timer", () => {
    const h = harness(30);
    h.r.stop();
    expect(h.cleared()).toBe(true);
  });
});

describe("the outage text has one full stop", () => {
  const good = { state: "ok", servers: [] };
  it("trailing full stops of the refusal are dropped", () => {
    expect(shownAnswer(null, "plugin down, retry in a moment.", good).error).toBe("plugin down, retry in a moment");
    expect(shownAnswer({ state: "unavailable", message: "down.." }, null, good).error).toBe("down");
  });
});
it("a refusal ending in s keeps its s", () => expect(shownAnswer(null, "no access", { state: "ok" }).error).toBe("no access"));
