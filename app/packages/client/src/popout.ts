// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Pure geometry for a floating chat window (the Kotlin AssistantPanelWidth idea, in two dimensions): bounds so it stays
// reachable, drag, and edge resize with a minimum size. Pure so it is testable without a DOM.

export interface Box {
  x: number;
  y: number;
  w: number;
  h: number;
}
export interface Bounds {
  w: number;
  h: number;
}

export const MIN_W = 320;
export const MIN_H = 240;
/** Kept on screen so the header can always be grabbed. */
const GRAB_PX = 80;

/** Fit `b` into the work area. x < 0 means "dock right" (the Kotlin popup sat on the right edge). */
export function clampBox(b: Box, bounds: Bounds): Box {
  const w = Math.min(Math.max(b.w, MIN_W), Math.max(MIN_W, bounds.w));
  const h = Math.min(Math.max(b.h, MIN_H), Math.max(MIN_H, bounds.h));
  const x0 = b.x < 0 ? bounds.w - w - 12 : b.x;
  const x = Math.min(Math.max(x0, GRAB_PX - w), Math.max(0, bounds.w - GRAB_PX));
  const y = Math.min(Math.max(b.y, 0), Math.max(0, bounds.h - 36));
  return { x, y, w, h };
}

export function dragBox(start: Box, dx: number, dy: number, bounds: Bounds): Box {
  return clampBox({ ...start, x: start.x + dx, y: start.y + dy }, bounds);
}

/** Resize from an edge or corner (`n s e w` letters). The opposite edge stays put and the size never drops below the minimum. */
export function resizeBox(start: Box, edge: string, dx: number, dy: number, bounds: Bounds): Box {
  let { x, y, w, h } = start;
  if (edge.includes("e")) w = Math.min(Math.max(start.w + dx, MIN_W), Math.max(MIN_W, bounds.w - start.x));
  if (edge.includes("s")) h = Math.min(Math.max(start.h + dy, MIN_H), Math.max(MIN_H, bounds.h - start.y));
  if (edge.includes("w")) {
    w = Math.min(Math.max(start.w - dx, MIN_W), start.x + start.w);
    x = start.x + start.w - w;
  }
  if (edge.includes("n")) {
    h = Math.min(Math.max(start.h - dy, MIN_H), start.y + start.h);
    y = start.y + start.h - h;
  }
  return { x, y, w, h };
}
