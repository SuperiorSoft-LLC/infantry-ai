// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// WINDOW-MIN-2: a BattalionOS without os_win_* (rejects, or never answers) falls back to the in-app dock, never the fold.
import { afterEach, describe, expect, it } from "vitest";
import { bindPopout, bindPopoutWindow, createWindows, dockItems, LocalWindowManager, onPopoutWindowsChange, popoutWindows, setPopoutWindows, type Windows } from "../src/popout-windows";
import type { WindowsBackend } from "../src/bos-windows/backend";

const os = (call: WindowsBackend["call"]): Windows => createWindows({ name: "os", call, onEvent: () => () => {} });
const local = () => new LocalWindowManager({ storage: null, surface: () => ({ w: 1200, h: 800 }) });

afterEach(() => setPopoutWindows(null));

describe("WINDOW-MIN-2 popout fallback", () => {
  it("repro: the OS backend without os_win_open makes bindPopoutWindow reject (the old code then folded)", async () => {
    await expect(bindPopoutWindow(os(() => Promise.reject(new Error("unknown command"))), { key: "k", title: "t" }, () => {})).rejects.toThrow();
  });

  it("os missing (rejects) -> the in-app dock", async () => {
    setPopoutWindows(os(() => Promise.reject(new Error("unknown command"))));
    const wm = local();
    let switched = 0;
    const off = onPopoutWindowsChange(() => switched++);
    const w = await bindPopout({ key: "k", title: "Pm" }, () => {}, { timeoutMs: 50, fallback: () => createWindows(wm) });
    off();
    expect(w.handle).toBeTruthy();
    expect((await popoutWindows()).backend).toBe(wm);
    expect(switched).toBe(1);
    await w.minimize();
    expect(dockItems(wm.windows()).map((x) => x.title)).toEqual(["Pm"]);
  });

  it("os hangs -> the in-app dock after the bound", async () => {
    setPopoutWindows(os(() => new Promise(() => {})));
    const wm = local();
    const t0 = Date.now();
    const w = await bindPopout({ key: "k", title: "Pm" }, () => {}, { timeoutMs: 80, fallback: () => createWindows(wm) });
    expect(Date.now() - t0).toBeGreaterThanOrEqual(70);
    await w.minimize();
    expect(dockItems(wm.windows())).toHaveLength(1);
  });

  it("os present -> the taskbar path (OS backend kept)", async () => {
    const calls: string[] = [];
    const ws = os(async (cmd) => {
      calls.push(cmd);
      return cmd === "os_win_open" ? { wid: "1", key: "k", kind: "inline", title: "Pm", state: "normal", bounds: { x: 0, y: 0, w: 1, h: 1 }, focused: true } : { state: "minimized" };
    });
    setPopoutWindows(ws);
    const w = await bindPopout({ key: "k", title: "Pm" }, () => {}, { timeoutMs: 50, fallback: () => createWindows(local()) });
    await w.minimize();
    expect((await popoutWindows()).backend.name).toBe("os");
    expect(calls).toContain("os_win_minimize");
  });
});

describe("WINDOW-MIN-2 fold only by setting", () => {
  it("fold setting -> fold; default -> taskbar (no fold)", async () => {
    const { minimizeMode, minimizeGeometry, MINIMIZE_MODE_KEY } = await import("../src/popout-windows");
    const g = { box: 1, minimized: false, maximized: false };
    expect(minimizeGeometry(g, minimizeMode({ getItem: (k) => (k === MINIMIZE_MODE_KEY ? "fold" : null) })).minimized).toBe(true);
    expect(minimizeMode({ getItem: () => null })).toBe("taskbar");
  });
});
