// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import { describe, expect, it } from "vitest";
import { bindPopoutWindow, createWindows, dockItems, LocalWindowManager, minimizeGeometry, minimizeMode, MINIMIZE_MODE_KEY } from "../src/popout-windows";

const setup = () => {
  const wm = new LocalWindowManager({ storage: null, surface: () => ({ w: 1200, h: 800 }) });
  return { wm, windows: createWindows(wm) };
};

describe("WINDOW-MIN-1 popout windows", () => {
  it("minimize hides into the dock and a dock click restores it", async () => {
    const { wm, windows } = setup();
    const seen: boolean[] = [];
    const w = await bindPopoutWindow(windows, { key: "infantry.popout.session.a", title: "Pm · a" }, (m) => seen.push(m));
    expect(dockItems(wm.windows())).toEqual([]);
    await w.minimize();
    expect(seen).toEqual([true]);
    expect(dockItems(wm.windows()).map((x) => x.title)).toEqual(["Pm · a"]);
    // The dock button: os_win_focus on the item.
    await wm.call("os_win_focus", { wid: w.handle!.wid });
    expect(seen).toEqual([true, false]);
    expect(dockItems(wm.windows())).toEqual([]);
  });

  it("minimize then restore keeps the popout's size and position", async () => {
    const { windows } = setup();
    const g = { box: { x: 30, y: 40, w: 500, h: 600 }, minimized: false, maximized: true };
    const w = await bindPopoutWindow(windows, { key: "k", title: "t" }, () => {});
    await w.minimize();
    await w.restore();
    expect(minimizeGeometry(g, "taskbar")).toEqual(g);
  });

  it("close removes the item", async () => {
    const { wm, windows } = setup();
    const w = await bindPopoutWindow(windows, { key: "k", title: "t" }, () => {});
    await w.minimize();
    expect(dockItems(wm.windows())).toHaveLength(1);
    await w.close();
    expect(wm.windows()).toEqual([]);
  });

  it("badge and attention reach the item", async () => {
    const { wm, windows } = setup();
    const w = await bindPopoutWindow(windows, { key: "k", title: "t" }, () => {});
    await w.set({ badge: { count: 2 }, attention: true });
    await w.minimize();
    expect(dockItems(wm.windows())[0]).toMatchObject({ badge: { count: 2 }, attention: true });
  });

  it("fold mode still folds to the title bar and keeps the box", () => {
    const box = { x: 1, y: 2, w: 3, h: 4 };
    const folded = minimizeGeometry({ box, minimized: false, maximized: true }, "fold");
    expect(folded).toEqual({ box, minimized: true, maximized: false });
    expect(minimizeGeometry(folded, "fold")).toEqual({ box, minimized: false, maximized: false });
  });

  it("the mode defaults to taskbar; fold is the opt-in", () => {
    const store = (v: string | null) => ({ getItem: (k: string) => (k === MINIMIZE_MODE_KEY ? v : null) });
    expect(minimizeMode(store(null))).toBe("taskbar");
    expect(minimizeMode(store("fold"))).toBe("fold");
    expect(minimizeMode(null)).toBe("taskbar");
  });
});
