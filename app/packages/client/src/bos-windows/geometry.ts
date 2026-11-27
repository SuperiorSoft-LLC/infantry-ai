// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// VENDORED from BattalionOS battalion-os @ 957c38f (WM-5), crates/battalion-desktop/ui/kit/lib. Do not edit here: change it in BattalionOS and copy again.
// OS-WINDOW-1 window geometry (pure, no DOM): the twin of battalion-app-api `geometry.rs`. clampBox, dragBox and
// resizeBox are moved from infantry-ai app/packages/client/src/popout.ts (same rules), generalised to a surface with
// an origin; snap zones are a surface's halves and quarters.

export interface Box {
  x: number;
  y: number;
  w: number;
  h: number;
}
/** An app's view, the OS work area or a monitor. `x`/`y` default to 0. */
export interface Surface {
  x?: number;
  y?: number;
  w: number;
  h: number;
}
export type Snap = "left" | "right" | "tl" | "tr" | "bl" | "br";

export const MIN_W = 320;
export const MIN_H = 240;
/** Kept on the surface so the title bar can always be grabbed. */
export const GRAB_PX = 80;
export const BAR_PX = 36;
export const DOCK_GAP_PX = 12;

/** Fit `b` into the surface. An x left of the surface means "dock right". */
export function clampBox(b: Box, s: Surface): Box {
  const ox = s.x ?? 0, oy = s.y ?? 0;
  const w = Math.min(Math.max(b.w, MIN_W), Math.max(MIN_W, s.w));
  const h = Math.min(Math.max(b.h, MIN_H), Math.max(MIN_H, s.h));
  const lx = b.x - ox;
  const x0 = lx < 0 ? s.w - w - DOCK_GAP_PX : lx;
  const x = Math.min(Math.max(x0, GRAB_PX - w), Math.max(0, s.w - GRAB_PX));
  const y = Math.min(Math.max(b.y - oy, 0), Math.max(0, s.h - BAR_PX));
  return { x: x + ox, y: y + oy, w, h };
}

export function dragBox(start: Box, dx: number, dy: number, s: Surface): Box {
  return clampBox({ ...start, x: start.x + dx, y: start.y + dy }, s);
}

/** Resize from an edge or corner (`n s e w` letters). The opposite edge stays put; the size never drops below the minimum. */
export function resizeBox(start: Box, edge: string, dx: number, dy: number, s: Surface): Box {
  const ox = s.x ?? 0, oy = s.y ?? 0;
  const sx = start.x - ox, sy = start.y - oy;
  let x = sx, y = sy, w = start.w, h = start.h;
  if (edge.includes("e")) w = Math.min(Math.max(start.w + dx, MIN_W), Math.max(MIN_W, s.w - sx));
  if (edge.includes("s")) h = Math.min(Math.max(start.h + dy, MIN_H), Math.max(MIN_H, s.h - sy));
  if (edge.includes("w")) {
    w = Math.min(Math.max(start.w - dx, MIN_W), sx + start.w);
    x = sx + start.w - w;
  }
  if (edge.includes("n")) {
    h = Math.min(Math.max(start.h - dy, MIN_H), sy + start.h);
    y = sy + start.h - h;
  }
  return { x: x + ox, y: y + oy, w, h };
}

/** The box a window takes when snapped to `zone`. */
export function snapBox(zone: Snap, s: Surface): Box {
  const ox = s.x ?? 0, oy = s.y ?? 0;
  const hw = s.w / 2, hh = s.h / 2;
  const zones: Record<Snap, [number, number, number, number]> = {
    left: [0, 0, hw, s.h],
    right: [hw, 0, s.w - hw, s.h],
    tl: [0, 0, hw, hh],
    tr: [hw, 0, s.w - hw, hh],
    bl: [0, hh, hw, s.h - hh],
    br: [hw, hh, s.w - hw, s.h - hh],
  };
  const [x, y, w, h] = zones[zone];
  return { x: x + ox, y: y + oy, w, h };
}

/** The zone a drag pointer asks for: near the left/right edge a half, near a corner a quarter, else none. */
export function snapZoneAt(px: number, py: number, s: Surface, edge: number): Snap | null {
  const lx = px - (s.x ?? 0), ly = py - (s.y ?? 0);
  const left = lx <= edge, right = lx >= s.w - edge, top = ly <= edge, bottom = ly >= s.h - edge;
  if (left) return top ? "tl" : bottom ? "bl" : "left";
  if (right) return top ? "tr" : bottom ? "br" : "right";
  return null;
}
