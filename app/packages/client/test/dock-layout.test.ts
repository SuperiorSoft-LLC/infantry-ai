// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// DOCK-CLIP-1: the in-app dock is a flex row of the shell column above the status bar, never an absolute overlay over the
// session view, so the composer's toolbar keeps its full height when the dock shows. (The shell package has no test runner;
// this checks the structure from the sources.)
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { dockItems, LocalWindowManager } from "../src/popout-windows";

const src = (p: string) =>
  readFileSync(fileURLToPath(new URL(`../../../shell/src/components/${p}`, import.meta.url)), "utf8").split("\r\n").join("\n");

describe("DOCK-CLIP-1 dock layout", () => {
  it("mounts the dock in the shell column between the main row and the status bar", () => {
    const shell = src("layout/app-shell.tsx");
    const main = shell.indexOf('<div className="relative flex min-h-0 flex-1">');
    const close = shell.indexOf("</div>\n      <PopoutDock />");
    const status = shell.indexOf("<StatusBar");
    expect(main).toBeGreaterThan(-1);
    expect(close).toBeGreaterThan(main);
    expect(status).toBeGreaterThan(close);
    expect(shell).toMatch(/<div className="flex h-full flex-col"/);
  });
  it("the dock takes real height and is no overlay; PopoutHost no longer mounts it inside the main row", () => {
    const host = src("session/popout-host.tsx");
    const nav = host.slice(host.indexOf("<nav"), host.indexOf(">", host.indexOf("<nav")));
    expect(nav).toContain("shrink-0");
    expect(nav).toContain("h-10");
    expect(nav).not.toMatch(/\babsolute\b/);
    const hostFn = host.slice(host.indexOf("export function PopoutHost"));
    expect(hostFn).not.toContain("<PopoutDock");
    expect(host).toContain("if (!wm || items.length === 0) return null;");
  });
  it("no minimized window -> no dock item; one minimized -> exactly that item", async () => {
    const wm = new LocalWindowManager({ storage: null, surface: () => ({ w: 1000, h: 800 }) });
    const a = (await wm.call("os_win_open", { key: "session.a", title: "Pm · a" })) as { wid: string };
    await wm.call("os_win_open", { key: "session.b", title: "Dev · b" });
    expect(dockItems(wm.windows())).toEqual([]);
    await wm.call("os_win_minimize", { wid: a.wid });
    expect(dockItems(wm.windows()).map((w) => w.title)).toEqual(["Pm · a"]);
  });
});
