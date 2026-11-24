// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The desktop icon grid: default order, snap, swap, cancel, save and load, new and removed apps, a small window.
import { describe, expect, it } from "vitest";
import { cellAt, CELL_H, CELL_W, drop, GRID_PAD, gridSize, neighbor, parsePositions, place, serializePositions } from "../src/grid";

const G = { cols: 4, rows: 3 };

describe("desktop icon grid", () => {
  it("fills a column top to bottom from the top left, then the next column", () => {
    expect(place(["a", "b", "c", "d"], {}, G)).toEqual({ a: { col: 0, row: 0 }, b: { col: 0, row: 1 }, c: { col: 0, row: 2 }, d: { col: 1, row: 0 } });
  });

  it("sizes the grid from the area, at least 1x1", () => {
    expect(gridSize(2 * GRID_PAD + 4 * CELL_W, 2 * GRID_PAD + 3 * CELL_H)).toEqual(G);
    expect(gridSize(10, 10)).toEqual({ cols: 1, rows: 1 });
  });

  it("snaps a pixel to the nearest cell and is null outside the grid", () => {
    expect(cellAt(GRID_PAD + CELL_W + 5, GRID_PAD + 2 * CELL_H + 5, G)).toEqual({ col: 1, row: 2 });
    expect(cellAt(2, 2, G)).toBeNull();
    expect(cellAt(GRID_PAD + 4 * CELL_W + 1, GRID_PAD, G)).toBeNull();
  });

  it("moves an icon to a free cell", () => {
    const s = drop(["a", "b"], {}, G, "a", { col: 3, row: 2 });
    expect(place(["a", "b"], s, G)).toEqual({ a: { col: 3, row: 2 }, b: { col: 0, row: 1 } });
  });

  it("swaps with the icon in an occupied cell", () => {
    const s = drop(["a", "b", "c"], {}, G, "a", { col: 0, row: 2 });
    expect(place(["a", "b", "c"], s, G)).toEqual({ a: { col: 0, row: 2 }, b: { col: 0, row: 1 }, c: { col: 0, row: 0 } });
  });

  it("a drop outside the grid (a cancel) changes nothing", () => {
    const saved = { a: { col: 1, row: 1 } };
    expect(drop(["a", "b"], saved, G, "a", { col: 9, row: 9 })).toBe(saved);
  });

  it("saves and loads, ignoring malformed data", () => {
    const p = { a: { col: 2, row: 1 } };
    expect(parsePositions(serializePositions(p))).toEqual(p);
    expect(parsePositions("not json")).toEqual({});
    expect(parsePositions('{"a":[1],"b":[-1,0],"c":[2,2]}')).toEqual({ c: { col: 2, row: 2 } });
  });

  it("a new app takes the first free cell and moves no one; a removed app frees its cell", () => {
    const saved = { a: { col: 0, row: 0 }, b: { col: 0, row: 2 } };
    expect(place(["a", "b", "n"], saved, G).n).toEqual({ col: 0, row: 1 });
    expect(place(["a", "n"], saved, G)).toEqual({ a: { col: 0, row: 0 }, n: { col: 0, row: 1 } });
    expect(Object.keys(drop(["a", "n"], saved, G, "a", { col: 3, row: 0 }))).toEqual(["a", "n"]);
  });

  it("a small window shows an outside icon in the first free cell, and its saved cell returns in a large one", () => {
    const saved = { a: { col: 0, row: 0 }, b: { col: 3, row: 2 } };
    const small = { cols: 2, rows: 2 };
    expect(place(["a", "b"], saved, small)).toEqual({ a: { col: 0, row: 0 }, b: { col: 0, row: 1 } });
    const after = drop(["a", "b"], saved, small, "a", { col: 1, row: 1 });
    expect(after.b).toEqual({ col: 3, row: 2 });
    expect(place(["a", "b"], after, G).b).toEqual({ col: 3, row: 2 });
  });

  it("arrow keys pick the nearest icon in that direction", () => {
    const shown = place(["a", "b", "c", "d"], {}, { cols: 2, rows: 2 });
    expect(neighbor(shown, "a", "down")).toBe("b");
    expect(neighbor(shown, "a", "right")).toBe("c");
    expect(neighbor(shown, "d", "left")).toBe("b");
    expect(neighbor(shown, "a", "up")).toBeNull();
  });
});
