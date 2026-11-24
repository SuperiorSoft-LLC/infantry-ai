// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The desktop's window rules: open puts an app in front, minimize keeps it running, close ends it.
import { describe, expect, it } from "vitest";
import { initialState, reduce, toggle } from "../src/state";

describe("desktop window state", () => {
  it("starts on the bare desktop, or with the orchestrator open when the setting says so", () => {
    expect(initialState(false)).toEqual({ windows: { orchestrator: "closed", apps: "closed" }, front: null, routes: {} });
    expect(initialState(true).front).toBe("orchestrator");
  });

  it("opens, minimizes (still running) and reopens, then closes", () => {
    let s = reduce(initialState(false), { type: "open", app: "orchestrator" });
    expect(s).toEqual({ windows: { orchestrator: "open", apps: "closed" }, front: "orchestrator", routes: {} });
    s = reduce(s, { type: "minimize", app: "orchestrator" });
    expect(s).toEqual({ windows: { orchestrator: "minimized", apps: "closed" }, front: null, routes: {} });
    s = reduce(s, { type: "open", app: "orchestrator" });
    expect(s.front).toBe("orchestrator");
    s = reduce(s, { type: "close", app: "orchestrator" });
    expect(s).toEqual(initialState(false));
  });

  it("sends the app in front to minimized when another opens, and closing a hidden app keeps the front", () => {
    let s = reduce(initialState(true), { type: "open", app: "apps" });
    expect(s).toEqual({ windows: { orchestrator: "minimized", apps: "open" }, front: "apps", routes: {} });
    s = reduce(s, { type: "close", app: "orchestrator" });
    expect(s.front).toBe("apps");
    expect(s.windows.orchestrator).toBe("closed");
  });

  it("ignores minimizing an app that is not open", () => {
    const s = initialState(false);
    expect(reduce(s, { type: "minimize", app: "apps" })).toBe(s);
  });

  it("a taskbar click restores a minimized app and minimizes the one in front", () => {
    let s = reduce(initialState(true), toggle(initialState(true), "orchestrator"));
    expect(s.windows.orchestrator).toBe("minimized");
    s = reduce(s, toggle(s, "orchestrator"));
    expect(s).toEqual({ windows: { orchestrator: "open", apps: "closed" }, front: "orchestrator", routes: {} });
  });

  it("an open with a route moves the app to that page; a plain open keeps it; closing forgets it", () => {
    let s = reduce(initialState(false), { type: "open", app: "apps", route: "app/tower" });
    expect(s).toMatchObject({ front: "apps", routes: { apps: "app/tower" } });
    s = reduce(s, { type: "minimize", app: "apps" });
    s = reduce(s, { type: "open", app: "apps" });
    expect(s.routes).toEqual({ apps: "app/tower" });
    s = reduce(s, { type: "open", app: "apps", route: "app/other" });
    expect(s.routes).toEqual({ apps: "app/other" });
    s = reduce(s, { type: "close", app: "apps" });
    expect(s.routes).toEqual({});
  });
});

describe("BOS-24: under BattalionOS the desktop lists no Apps app and shows no updates", () => {
  const all = [{ id: "orchestrator" }, { id: "apps" }];
  it("keeps both apps standalone and drops Apps under the OS", async () => {
    const { appsListed } = await import("../src/state");
    expect(appsListed(all, false).map((a) => a.id)).toEqual(["orchestrator", "apps"]);
    expect(appsListed(all, true).map((a) => a.id)).toEqual(["orchestrator"]);
  });
  it("shows the updates standalone and none under the OS", async () => {
    const { updatesShown } = await import("../src/state");
    expect(updatesShown([1, 2], false)).toEqual([1, 2]);
    expect(updatesShown([1, 2], true)).toEqual([]);
  });
});
