// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// MIN-TASKBAR-1 (replaces the WINDOW-MIN-2 dock fallback, which this ticket names to change): under BattalionOS a minimized
// chat goes ONLY to the OS taskbar. A BattalionOS without os_win_* (refuses or never answers) gets no dock and no fold: the
// first Minimize shows the update notice once and the chat stays open.
import { afterEach, describe, expect, it } from "vitest";
import { bindPopout, bindPopoutWindow, createWindows, dockItems, LocalWindowManager, OS_UPDATE_NOTICE, popoutWindows, resetPopoutRefusal, setPopoutWindows, type Windows } from "../src/popout-windows";
import type { WindowsBackend } from "../src/bos-windows/backend";

const os = (call: WindowsBackend["call"]): Windows => createWindows({ name: "os", call, onEvent: () => () => {} });

afterEach(() => {
  setPopoutWindows(null);
  resetPopoutRefusal();
});

describe("MIN-TASKBAR-1 under an old BattalionOS (no os_win_*)", () => {
  it("the OS backend without os_win_open makes bindPopoutWindow reject", async () => {
    await expect(bindPopoutWindow(os(() => Promise.reject(new Error("unknown command"))), { key: "k", title: "t" }, () => {})).rejects.toThrow();
  });

  it("os refuses -> no dock, no fold: one notice, the chat stays open, the OS backend is kept", async () => {
    const ws = os(() => Promise.reject(new Error("'os_win_open' is not available to an app's page")));
    setPopoutWindows(ws);
    const notices: string[] = [];
    const states: boolean[] = [];
    const w = await bindPopout({ key: "k", title: "Pm" }, (m) => states.push(m), { timeoutMs: 50, onRefused: () => notices.push(OS_UPDATE_NOTICE) });
    expect(w.handle).toBeNull();
    await w.minimize();
    await w.minimize();
    const w2 = await bindPopout({ key: "k2", title: "Pm2" }, () => {}, { timeoutMs: 50, onRefused: () => notices.push(OS_UPDATE_NOTICE) });
    await w2.minimize();
    expect(notices).toEqual(["Update BattalionOS to 0.2.0 to minimize to the taskbar"]);
    expect(states).toEqual([]);
    expect((await popoutWindows()).backend).toBe(ws.backend);
  });

  it("os hangs -> the same after the bound", async () => {
    setPopoutWindows(os(() => new Promise(() => {})));
    let told = 0;
    const t0 = Date.now();
    const w = await bindPopout({ key: "k", title: "Pm" }, () => {}, { timeoutMs: 80, onRefused: () => told++ });
    expect(Date.now() - t0).toBeGreaterThanOrEqual(70);
    await w.minimize();
    expect(told).toBe(1);
    expect((await popoutWindows()).backend.name).toBe("os");
  });
});

describe("MIN-TASKBAR-1 standalone and in a browser", () => {
  it("the local manager (the in-app dock) is still used", async () => {
    const wm = new LocalWindowManager({ storage: null, surface: () => ({ w: 1200, h: 800 }) });
    setPopoutWindows(createWindows(wm));
    const w = await bindPopout({ key: "k", title: "Pm" }, () => {});
    await w.minimize();
    expect(dockItems(wm.windows()).map((x) => x.title)).toEqual(["Pm"]);
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
