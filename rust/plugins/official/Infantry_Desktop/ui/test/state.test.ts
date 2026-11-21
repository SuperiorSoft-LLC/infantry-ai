// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The desktop's window rules: open puts an app in front, minimize keeps it running, close ends it, maximize toggles the app's
// window between filling the work area and the smaller restored window (DESK-5: all of these are the APP's, not the real window's).
import { describe, expect, it } from "vitest";
import { initialState, reduce, toggle } from "../src/state";

const FULL = { orchestrator: true, apps: true };

describe("desktop window state", () => {
  it("starts on the bare desktop, or with the orchestrator open when the setting says so", () => {
    expect(initialState(false)).toEqual({ windows: { orchestrator: "closed", apps: "closed" }, front: null, maximized: FULL });
    expect(initialState(true).front).toBe("orchestrator");
  });

  it("opens, minimizes (still running) and reopens, then closes", () => {
    let s = reduce(initialState(false), { type: "open", app: "orchestrator" });
    expect(s).toEqual({ windows: { orchestrator: "open", apps: "closed" }, front: "orchestrator", maximized: FULL });
    s = reduce(s, { type: "minimize", app: "orchestrator" });
    expect(s).toEqual({ windows: { orchestrator: "minimized", apps: "closed" }, front: null, maximized: FULL });
    s = reduce(s, { type: "open", app: "orchestrator" });
    expect(s.front).toBe("orchestrator");
    s = reduce(s, { type: "close", app: "orchestrator" });
    expect(s).toEqual(initialState(false));
  });

  it("sends the app in front to minimized when another opens, and closing a hidden app keeps the front", () => {
    let s = reduce(initialState(true), { type: "open", app: "apps" });
    expect(s).toEqual({ windows: { orchestrator: "minimized", apps: "open" }, front: "apps", maximized: FULL });
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
    expect(s).toEqual({ windows: { orchestrator: "open", apps: "closed" }, front: "orchestrator", maximized: FULL });
  });

  it("an app opens maximized, restores to a smaller window and maximizes again", () => {
    let s = initialState(true);
    expect(s.maximized.orchestrator).toBe(true);
    s = reduce(s, { type: "toggleMaximize", app: "orchestrator" });
    expect(s.maximized.orchestrator).toBe(false);
    expect(s.front).toBe("orchestrator"); // restoring never leaves the app or ends it
    expect(s.windows.orchestrator).toBe("open");
    s = reduce(s, { type: "toggleMaximize", app: "orchestrator" });
    expect(s.maximized.orchestrator).toBe(true);
  });

  it("keeps a restored window restored through a minimize and a taskbar restore", () => {
    let s = reduce(initialState(true), { type: "toggleMaximize", app: "orchestrator" });
    s = reduce(s, { type: "minimize", app: "orchestrator" });
    expect(s.front).toBeNull();
    s = reduce(s, toggle(s, "orchestrator"));
    expect(s.front).toBe("orchestrator");
    expect(s.maximized.orchestrator).toBe(false);
  });

  it("closing forgets the restored size, so the next launch opens full size", () => {
    let s = reduce(initialState(true), { type: "toggleMaximize", app: "orchestrator" });
    s = reduce(s, { type: "close", app: "orchestrator" });
    expect(s.maximized.orchestrator).toBe(true);
    expect(s.front).toBeNull(); // back to the desktop; the real window is untouched
  });

  it("maximize of an app that is not open, or hidden, does nothing", () => {
    const closed = initialState(false);
    expect(reduce(closed, { type: "toggleMaximize", app: "orchestrator" })).toBe(closed);
    const hidden = reduce(initialState(true), { type: "minimize", app: "orchestrator" });
    expect(reduce(hidden, { type: "toggleMaximize", app: "orchestrator" })).toBe(hidden);
  });

  it("one app's window size never changes another's", () => {
    let s = reduce(initialState(true), { type: "toggleMaximize", app: "orchestrator" });
    s = reduce(s, { type: "open", app: "apps" });
    expect(s.maximized).toEqual({ orchestrator: false, apps: true });
  });
});
