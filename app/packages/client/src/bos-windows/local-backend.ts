// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// VENDORED from BattalionOS battalion-os @ 957c38f (WM-5), crates/battalion-desktop/ui/kit/lib. Do not edit here: change it in BattalionOS and copy again.
// WM-5: `localBackend`, the in-page window manager for standalone Tauri and the browser. It runs the OS's own rules
// (battalion-desktop win_calls.rs / windows.rs): focus instead of a duplicate key, saved state wins over the given
// bounds, z order, halves/quarters snap, focus restores a minimized window, a minimized window saves its last open
// state. Geometry is kept per key in `localStorage`. Only `inline` exists here: `float` and `native` answer `inline`.
import { clampBox, snapBox, type Surface } from "./geometry";
import type { WinCall, WindowsBackend } from "./backend";
import type { Opened, Rect, SnapZone, WinEvent, WindowBadge, WindowInfo, WindowState } from "./types.gen";

/** The part of `Storage` used. */
export interface KeyValueStore {
  getItem(k: string): string | null;
  setItem(k: string, v: string): void;
}

export interface LocalOptions {
  /** Storage key suffix (one app per page; pass the app id when several share an origin). */
  scope?: string;
  /** Defaults to `localStorage` when there is one; else nothing is kept. */
  storage?: KeyValueStore | null;
  /** The area windows live in; defaults to the page minus the dock strip. */
  surface?: () => Surface;
}

type Saved = { bounds: Rect; state: WindowState };
const ZONES: SnapZone[] = ["left", "right", "tl", "tr", "bl", "br"];
/** The dock strip's height (px); the default surface leaves it free. */
export const DOCK_H = 40;
const DEFAULT_BOUNDS: Rect = { x: 40, y: 40, w: 480, h: 360 };

const str = (v: unknown) => (typeof v === "string" ? v : undefined);
function rect(v: unknown): Rect | undefined {
  const r = v as Rect | null;
  return r && typeof r === "object" && [r.x, r.y, r.w, r.h].every((n) => typeof n === "number") ? { x: r.x, y: r.y, w: r.w, h: r.h } : undefined;
}
function state(v: unknown): WindowState | undefined {
  if (v === "normal" || v === "minimized" || v === "maximized") return v;
  const z = (v as { snap?: unknown } | null)?.snap;
  return ZONES.includes(z as SnapZone) ? { snap: z as SnapZone } : undefined;
}

export class LocalWindowManager implements WindowsBackend {
  readonly name = "local" as const;
  private table = new Map<string, WindowInfo>();
  private n = 0;
  private z = 0;
  private listeners = new Set<(e: WinEvent) => void>();
  private changeListeners = new Set<() => void>();
  private storeKey: string;
  private storage: KeyValueStore | null;
  private surfaceOf: () => Surface;

  constructor(opts: LocalOptions = {}) {
    this.storeKey = `bos.windows.${opts.scope ?? "app"}`;
    this.storage = opts.storage !== undefined ? opts.storage : typeof localStorage !== "undefined" ? localStorage : null;
    this.surfaceOf = opts.surface ?? (() => ({ w: globalThis.innerWidth ?? 1280, h: (globalThis.innerHeight ?? 800) - DOCK_H }));
  }

  surface(): Surface {
    return this.surfaceOf();
  }

  onEvent(fn: (e: WinEvent) => void) {
    this.listeners.add(fn);
    return () => void this.listeners.delete(fn);
  }

  /** Any change to the table (for the desk and dock to render). */
  onChange(fn: () => void) {
    this.changeListeners.add(fn);
    return () => void this.changeListeners.delete(fn);
  }

  /** The windows, bottom to top. */
  windows(): WindowInfo[] {
    return [...this.table.values()].sort((a, b) => a.z - b.z).map((w) => ({ ...w }));
  }

  async call(cmd: WinCall, args: Record<string, unknown> = {}): Promise<unknown> {
    const out = this.run(cmd, args);
    for (const l of this.changeListeners) l();
    return out;
  }

  private run(cmd: WinCall, args: Record<string, unknown>): unknown {
    switch (cmd) {
      case "os_win_open":
        return this.open(args);
      case "os_win_list":
        return this.windows();
      case "os_win_close": {
        const w = this.own(cmd, args);
        this.table.delete(w.wid);
        this.emit({ change: "closed", wid: w.wid });
        return null;
      }
      case "os_win_minimize":
      case "os_win_restore":
      case "os_win_maximize": {
        const w = this.own(cmd, args);
        w.state = cmd === "os_win_minimize" ? "minimized" : cmd === "os_win_maximize" ? "maximized" : "normal";
        this.remember(w);
        this.emit({ change: "state", wid: w.wid, state: w.state, bounds: w.bounds });
        return { state: w.state };
      }
      case "os_win_focus": {
        const w = this.own(cmd, args);
        if (w.state === "minimized") {
          w.state = "normal";
          this.remember(w);
        }
        w.z = ++this.z;
        this.emit({ change: "focus", wid: w.wid, z: w.z, state: w.state });
        return { state: w.state };
      }
      case "os_win_snap": {
        const w = this.own(cmd, args);
        const zone = args.zone as SnapZone;
        if (!ZONES.includes(zone)) throw new Error("os_win_snap needs a zone: left, right, tl, tr, bl or br");
        w.state = { snap: zone };
        w.bounds = snapBox(zone, this.surfaceOf());
        this.remember(w);
        this.emit({ change: "state", wid: w.wid, state: w.state, bounds: w.bounds });
        return { state: w.state, bounds: w.bounds };
      }
      case "os_win_set": {
        const w = this.own(cmd, args);
        if (typeof args.title === "string") w.title = args.title;
        if (typeof args.icon === "string") w.icon = args.icon;
        if ("badge" in args) {
          if (args.badge === null) delete w.badge;
          else if (args.badge && typeof args.badge === "object") w.badge = args.badge as WindowBadge;
        }
        if (typeof args.attention === "boolean") w.attention = args.attention;
        const b = rect(args.bounds);
        if (b) {
          w.bounds = b;
          this.remember(w);
          this.emit({ change: "state", wid: w.wid, state: w.state, bounds: w.bounds });
        }
        return null;
      }
    }
  }

  private open(args: Record<string, unknown>): Opened {
    const key = str(args.key)?.trim();
    if (!key) throw new Error("os_win_open needs a key");
    const open = [...this.table.values()].find((w) => w.key === key);
    if (open) {
      open.z = ++this.z;
      return { wid: open.wid, kind: open.kind, state: open.state, bounds: open.bounds, restored: false };
    }
    const saved = this.saved()[key];
    const bounds = saved?.bounds ?? rect(args.bounds) ?? DEFAULT_BOUNDS;
    const w: WindowInfo = {
      wid: `w-local-${++this.n}`,
      key,
      kind: "inline",
      title: str(args.title) ?? "",
      state: saved?.state ?? state(args.state) ?? "normal",
      bounds: clampBox(bounds, this.surfaceOf()),
      z: ++this.z,
    };
    const icon = str(args.icon);
    if (icon) w.icon = icon;
    this.table.set(w.wid, w);
    this.remember(w);
    return { wid: w.wid, kind: w.kind, state: w.state, bounds: w.bounds, restored: saved !== undefined };
  }

  private own(cmd: string, args: Record<string, unknown>): WindowInfo {
    const wid = str(args.wid);
    if (!wid) throw new Error(`${cmd} needs a wid`);
    const w = this.table.get(wid);
    if (!w) throw new Error(`no window ${wid}`);
    return w;
  }

  private emit(e: WinEvent) {
    for (const l of this.listeners) l(e);
  }

  private saved(): Record<string, Saved> {
    try {
      const v = JSON.parse(this.storage?.getItem(this.storeKey) ?? "{}") as unknown;
      return v && typeof v === "object" ? (v as Record<string, Saved>) : {};
    } catch {
      return {};
    }
  }

  /** Keep `key`'s geometry. A minimized window keeps its last open state. */
  private remember(w: WindowInfo) {
    if (!this.storage) return;
    const all = this.saved();
    const keep = w.state === "minimized" ? (all[w.key]?.state ?? "normal") : w.state;
    all[w.key] = { bounds: w.bounds, state: keep };
    try {
      this.storage.setItem(this.storeKey, JSON.stringify(all));
    } catch {
      // Storage full or refused: the windows still work, only not kept.
    }
  }
}

export function localBackend(opts: LocalOptions = {}): LocalWindowManager {
  return new LocalWindowManager(opts);
}
