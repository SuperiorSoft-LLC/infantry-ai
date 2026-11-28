// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// POPOUT-1: a session in its own BattalionOS window: the route, focus-instead-of-duplicate, hidden outside the OS.
import { describe, expect, it } from "vitest";
import { createWindows, LocalWindowManager, type Windows } from "../src/popout-windows";
import { nativePopoutAvailable, openSessionPopout, popoutViewOf, sessionPopoutRoute } from "../src/native-popout";

/** A fake OS window manager: one window per key, like os_win_open. */
function fakeOs() {
  const calls: [string, Record<string, unknown>][] = [];
  const byKey = new Map<string, string>();
  let n = 0;
  const ws: Windows = createWindows({
    name: "os",
    onEvent: () => () => {},
    async call(cmd, args = {}) {
      calls.push([cmd, args]);
      if (cmd === "os_win_open") {
        const key = args.key as string;
        const wid = byKey.get(key) ?? `w-infantry-ai-${++n}`;
        byKey.set(key, wid);
        return { wid, kind: "native", state: "normal", bounds: { x: 0, y: 0, w: 900, h: 700 }, restored: false };
      }
      return { state: "normal" };
    },
  });
  return { ws, calls, opened: () => byKey.size };
}

describe("POPOUT-1 native session window", () => {
  it("the route is this page with its OS flag and popout=session:<id>; the pop-out page reads it back", () => {
    const r = sessionPopoutRoute("/ui/index.html", "?bos-app=infantry-ai", "s-1");
    expect(r).toBe("/ui/index.html?bos-app=infantry-ai&popout=session%3As-1");
    expect(popoutViewOf(r.slice(r.indexOf("?")))).toEqual({ kind: "session", id: "s-1" });
    expect(sessionPopoutRoute("//evil", "", "a")).toBe("/?popout=session%3Aa");
    expect(popoutViewOf("?bos-app=x")).toBeNull();
    expect(popoutViewOf("?popout=session:../x")).toBeNull();
  });

  it("opens a native window keyed session.<id>; a second click focuses it instead of a duplicate", async () => {
    const os = fakeOs();
    const a = await openSessionPopout(os.ws, { sessionId: "s-1", title: "Ada", pathname: "/", search: "?bos-app=infantry-ai" });
    const b = await openSessionPopout(os.ws, { sessionId: "s-1", title: "Ada", pathname: "/", search: "?bos-app=infantry-ai" });
    expect(b).toBe(a);
    expect(os.opened()).toBe(1);
    const open = os.calls.find(([c]) => c === "os_win_open")![1];
    expect(open).toMatchObject({ key: "session.s-1", kind: "native", title: "Ada" });
    expect(os.calls.filter(([c]) => c === "os_win_focus")).toHaveLength(2);
    await openSessionPopout(os.ws, { sessionId: "s-2", title: "Bo", pathname: "/", search: "" });
    expect(os.opened()).toBe(2); // no cap
  });

  it("is hidden outside BattalionOS, on a phone, and inside a pop-out", () => {
    expect(nativePopoutAvailable(fakeOs().ws)).toBe(true);
    expect(nativePopoutAvailable(createWindows(new LocalWindowManager({ storage: null })))).toBe(false);
    expect(nativePopoutAvailable(null)).toBe(false);
    expect(nativePopoutAvailable(fakeOs().ws, { mobile: true })).toBe(false);
    expect(nativePopoutAvailable(fakeOs().ws, { inPopout: true })).toBe(false);
  });
});

describe("FLOAT-ADOPT-1 float session window", () => {
  it("opens kind float keyed session.<id> with the pop-out route and bounds; a closed event calls back", async () => {
    let emit: (e: unknown) => void = () => {};
    const calls: [string, Record<string, unknown>][] = [];
    const ws: Windows = createWindows({
      name: "os",
      onEvent: (fn) => ((emit = fn as (e: unknown) => void), () => {}),
      async call(cmd, args = {}) {
        calls.push([cmd, args]);
        return { wid: "w-1", kind: "float", state: "normal", bounds: { x: 1, y: 2, w: 3, h: 4 }, restored: false };
      },
    });
    let closed = 0;
    const { openSessionFloat } = await import("../src/native-popout");
    await openSessionFloat(ws, { sessionId: "s-1", title: "Bob", pathname: "/ui/index.html", search: "?bos-app=infantry-ai", bounds: { x: 1, y: 2, w: 3, h: 4 } }, () => closed++);
    expect(calls[0]).toEqual(["os_win_open", { key: "session.s-1", kind: "float", title: "Bob", route: "/ui/index.html?bos-app=infantry-ai&popout=session%3As-1", bounds: { x: 1, y: 2, w: 3, h: 4 } }]);
    emit({ change: "closed", wid: "w-1" });
    expect(closed).toBe(1);
  });
});
