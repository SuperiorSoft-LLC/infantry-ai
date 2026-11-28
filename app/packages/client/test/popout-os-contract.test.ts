// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// MIN-TASKBAR-1 contract: the popout's window calls through the real frame bridge of BattalionOS (frames.ts `answer` and
// `winEventFor`, vendored from origin/battalion-os babe8ec). The page side is the real frameTransport + osBackend; only the
// host's window manager (win_calls.rs, Rust) is a small model here. Minimize lands the chat in the app's window list (the
// list the taskbar groups under the Infantry AI button, WM-3 App.tsx `wins-loaded`), and the taskbar click
// (`os_frame_win_focus {wid}`, App.tsx) restores it.
import { afterEach, describe, expect, it } from "vitest";
import { answer, winEventFor, type FrameBackend, type FrameEntry } from "./fixtures/bos-frames/src/frames";
import { bindPopout, createWindows, frameTransport, osBackend, resetPopoutRefusal, setPopoutWindows } from "../src/popout-windows";

type Fn = (e: { source?: unknown; data: unknown }) => void;
const APP = "infantry-ai";
const ORIGIN = "http://127.0.0.1:18991";

function rig(opts: { permissions: string[] }) {
  const listeners = new Set<Fn>();
  const page = { addEventListener: (_: "message", f: Fn) => listeners.add(f), removeEventListener: (_: "message", f: Fn) => listeners.delete(f) };
  const frames: FrameEntry[] = [{ id: APP, origin: ORIGIN, window: page }];
  const wins: { wid: string; key: string; title: string; state: string }[] = [];
  const deliver = (m: unknown) => listeners.forEach((f) => f({ source: shell, data: m }));
  // The host's os://win event, routed to the frame by the real winEventFor.
  const emit = (wid: string, state: string) => {
    const r = winEventFor(frames, { bos: 1, event: "os_win", id: APP, wid, change: "state", state, bounds: { x: 0, y: 0, w: 1, h: 1 } });
    if (r) deliver(r.message);
  };
  const win: FrameBackend["win"] = async (id, cmd, args) => {
    if (!opts.permissions.includes("window")) throw new Error(`app '${id}' has no 'window' permission in its manifest, so '${cmd}' is refused`);
    const w = wins.find((x) => x.wid === args.wid);
    switch (cmd) {
      case "os_win_open": {
        const n = { wid: `w-${id}-${wins.length + 1}`, key: String(args.key), title: String(args.title ?? ""), state: "normal" };
        wins.push(n);
        return { ...n, kind: "inline", bounds: { x: 0, y: 0, w: 1, h: 1 }, focused: true };
      }
      case "os_win_minimize":
      case "os_win_focus":
        w!.state = cmd === "os_win_minimize" ? "minimized" : "normal";
        emit(w!.wid, w!.state);
        return { state: w!.state };
      case "os_win_list":
        return wins.map((x) => ({ ...x, app: id }));
      default:
        return null;
    }
  };
  const no = () => Promise.reject(new Error("not in this test"));
  const be: FrameBackend = { appStatus: no, appHealth: no, badge: async () => {}, close: () => {}, minimize: () => {}, pickFolder: no, pickFile: no, openExternal: no, loginOpen: no, loginClose: no, notify: no, scanCode: no, win };
  const shell = {
    postMessage(m: unknown) {
      void answer(frames, { source: page, origin: ORIGIN, data: m }, be).then((r) => r && deliver(r.message));
    },
  };
  /** The OS shell's own side of a taskbar click (App.tsx: os.frameWin(id, "os_win_focus", {wid})). */
  const taskbarClick = (wid: string) => win(APP, "os_win_focus", { wid });
  setPopoutWindows(createWindows(osBackend(frameTransport(shell, page))));
  return { wins, taskbarClick, list: () => win(APP, "os_win_list", {}) as Promise<{ wid: string; app: string; state: string; title: string }[]> };
}

afterEach(() => {
  setPopoutWindows(null);
  resetPopoutRefusal();
});

describe("MIN-TASKBAR-1 contract with the BattalionOS frame bridge", () => {
  it("minimize -> the chat is a minimized window of the Infantry AI app; the taskbar click restores it", async () => {
    const r = rig({ permissions: ["network", "window"] });
    const seen: boolean[] = [];
    const w = await bindPopout({ key: "infantry.popout.ffa26a73", title: "Senior PM · ffa26a73" }, (m) => seen.push(m), { timeoutMs: 500 });
    expect(w.handle).not.toBeNull();
    await w.minimize();
    expect(await r.list()).toEqual([expect.objectContaining({ app: APP, title: "Senior PM · ffa26a73", state: "minimized" })]);
    await r.taskbarClick(r.wins[0].wid);
    await new Promise((ok) => setTimeout(ok, 0));
    expect(seen).toEqual([true, false]);
    expect(r.wins[0].state).toBe("normal");
  });

  it("an installed manifest without the window permission is refused like an old OS: no dock, one notice", async () => {
    rig({ permissions: ["network"] });
    let told = 0;
    const w = await bindPopout({ key: "k", title: "Pm" }, () => {}, { timeoutMs: 500, onRefused: () => told++ });
    expect(w.handle).toBeNull();
    await w.minimize();
    expect(told).toBe(1);
  });
});
