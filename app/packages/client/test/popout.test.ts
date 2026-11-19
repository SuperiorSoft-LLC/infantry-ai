// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import { describe, expect, it } from "vitest";
import { clampBox, dragBox, resizeBox, MIN_H, MIN_W } from "../src";

const area = { w: 1000, h: 700 };

describe("popout geometry", () => {
  it("docks a box with x < 0 to the right edge", () => {
    const b = clampBox({ x: -1, y: 8, w: 400, h: 500 }, area);
    expect(b.x + b.w).toBeLessThanOrEqual(area.w);
    expect(b.x).toBeGreaterThan(500);
  });
  it("keeps a dragged box grabbable and inside the work area", () => {
    const b = dragBox({ x: 100, y: 100, w: 400, h: 400 }, 5000, 5000, area);
    expect(b.x).toBeLessThan(area.w);
    expect(b.y).toBeLessThan(area.h);
    const c = dragBox({ x: 100, y: 100, w: 400, h: 400 }, -5000, -5000, area);
    expect(c.x + c.w).toBeGreaterThan(0);
    expect(c.y).toBe(0);
  });
  it("never resizes below the minimum and keeps the opposite edge fixed", () => {
    const start = { x: 300, y: 200, w: 400, h: 400 };
    const w = resizeBox(start, "w", 900, 0, area);
    expect(w.w).toBe(MIN_W);
    expect(w.x + w.w).toBe(start.x + start.w);
    const n = resizeBox(start, "n", 0, 900, area);
    expect(n.h).toBe(MIN_H);
    expect(n.y + n.h).toBe(start.y + start.h);
  });
  it("grows from the east and south edges inside the area", () => {
    const b = resizeBox({ x: 100, y: 100, w: 400, h: 400 }, "se", 100, 100, area);
    expect(b).toMatchObject({ x: 100, y: 100, w: 500, h: 500 });
    expect(resizeBox({ x: 100, y: 100, w: 400, h: 400 }, "e", 9999, 0, area).w).toBe(area.w - 100);
  });
});
