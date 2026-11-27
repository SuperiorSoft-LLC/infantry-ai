// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// VENDORED from BattalionOS battalion-os @ 957c38f (WM-5), crates/battalion-desktop/ui/kit/lib. Do not edit here: change it in BattalionOS and copy again.
// WM-5 (OS-WINDOW-1 §2.4): the `windows` client apps use. One interface, two backends picked at start:
//   const windows = await pickWindows();
//   const w = await windows.open({ key: `session.${id}`, kind: "inline", title });
//   w.on("state", (s) => render(s)); w.minimize(); w.snap("left"); w.set({ badge: { count: 2 } }); w.close();
import type { WindowsBackend } from "./backend";
import { LocalWindowManager, type LocalOptions } from "./local-backend";
import { frameTransport, osBackend } from "./os-backend";
import type { Opened, Rect, SnapZone, WinEvent, WindowInfo, WindowKind, WindowPatch, WindowSpec, WindowState } from "./types.gen";

export type HandleEvent = "state" | "closed" | "focus";
type Listener<E extends HandleEvent> = E extends "state"
  ? (s: { state: WindowState; bounds: Rect }) => void
  : E extends "focus"
    ? (f: { z: number; state?: WindowState }) => void
    : () => void;

export interface WindowHandle {
  readonly wid: string;
  readonly key: string;
  /** The kind actually used (the local backend answers `inline`). */
  readonly kind: WindowKind;
  readonly restored: boolean;
  readonly state: WindowState;
  readonly bounds: Rect;
  readonly closed: boolean;
  minimize(): Promise<WindowState>;
  restore(): Promise<WindowState>;
  maximize(): Promise<WindowState>;
  focus(): Promise<WindowState>;
  snap(zone: SnapZone): Promise<{ state: WindowState; bounds: Rect }>;
  set(patch: WindowPatch): Promise<void>;
  close(): Promise<void>;
  on<E extends HandleEvent>(event: E, fn: Listener<E>): () => void;
}

export interface Windows {
  readonly backend: WindowsBackend;
  open(spec: WindowSpec): Promise<WindowHandle>;
  list(): Promise<WindowInfo[]>;
}

export function createWindows(backend: WindowsBackend): Windows {
  const handles = new Map<string, Handle>();
  backend.onEvent((e) => handles.get(e.wid)?.apply(e));

  class Handle implements WindowHandle {
    state: WindowState;
    bounds: Rect;
    closed = false;
    private ls = { state: new Set<Function>(), closed: new Set<Function>(), focus: new Set<Function>() };
    constructor(
      readonly key: string,
      private o: Opened,
    ) {
      this.state = o.state;
      this.bounds = o.bounds;
    }
    get wid() {
      return this.o.wid;
    }
    get kind() {
      return this.o.kind;
    }
    get restored() {
      return this.o.restored;
    }
    apply(e: WinEvent) {
      if (e.change === "state") {
        this.state = e.state;
        this.bounds = e.bounds;
        for (const l of this.ls.state) l({ state: e.state, bounds: e.bounds });
      } else if (e.change === "focus") {
        if (e.state) this.state = e.state;
        for (const l of this.ls.focus) l({ z: e.z, state: e.state });
      } else {
        this.closed = true;
        handles.delete(this.wid);
        for (const l of this.ls.closed) l();
      }
    }
    private async st(cmd: "os_win_minimize" | "os_win_restore" | "os_win_maximize" | "os_win_focus") {
      const r = (await backend.call(cmd, { wid: this.wid })) as { state: WindowState };
      this.state = r.state;
      return r.state;
    }
    minimize() {
      return this.st("os_win_minimize");
    }
    restore() {
      return this.st("os_win_restore");
    }
    maximize() {
      return this.st("os_win_maximize");
    }
    focus() {
      return this.st("os_win_focus");
    }
    async snap(zone: SnapZone) {
      const r = (await backend.call("os_win_snap", { wid: this.wid, zone })) as { state: WindowState; bounds: Rect };
      this.state = r.state;
      this.bounds = r.bounds;
      return r;
    }
    async set(patch: WindowPatch) {
      await backend.call("os_win_set", { ...patch, wid: this.wid });
      if (patch.bounds) this.bounds = patch.bounds;
    }
    async close() {
      await backend.call("os_win_close", { wid: this.wid });
      // Under the OS the closed event follows; settle here too so a handle is closed when close() resolves.
      if (!this.closed) this.apply({ change: "closed", wid: this.wid });
    }
    on<E extends HandleEvent>(event: E, fn: Listener<E>) {
      this.ls[event].add(fn);
      return () => void this.ls[event].delete(fn);
    }
  }

  return {
    backend,
    async open(spec) {
      const o = (await backend.call("os_win_open", { ...spec })) as Opened;
      const had = handles.get(o.wid);
      if (had) {
        had.state = o.state;
        return had;
      }
      const h = new Handle(spec.key, o);
      handles.set(o.wid, h);
      return h;
    },
    async list() {
      return (await backend.call("os_win_list")) as WindowInfo[];
    },
  };
}

/** Under BattalionOS (an OS frame answering `os_window_info`): the OS backend; else the local one. */
export async function pickWindows(opts: LocalOptions & { timeoutMs?: number } = {}): Promise<Windows> {
  const w = globalThis as unknown as Window;
  if (typeof w.parent !== "undefined" && w.parent !== w) {
    const t = frameTransport(w.parent, w, { timeoutMs: opts.timeoutMs ?? 800 });
    try {
      await t.send("os_window_info", {});
      return createWindows(osBackend(t));
    } catch {
      // Not under the OS (or an OS without the bridge): fall through.
    }
  }
  return createWindows(new LocalWindowManager(opts));
}
