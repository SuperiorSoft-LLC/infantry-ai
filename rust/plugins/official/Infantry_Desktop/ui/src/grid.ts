// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// DESKTOP-GRID-1: where the desktop icons sit. Pure, so it is tested without a screen. Icons live in cells of a grid. By default
// they fill a column top to bottom from the top left, then the next column. A saved position (app id -> column,row) is kept
// while it fits; an icon with none, or whose cell is outside a small window, takes the first free cell. Nothing here names an app.

/** The one place the cell size and the margin round the grid come from (px). A cell holds an icon (w-24 = 96) plus the old 8px gap. */
export const CELL_W = 104;
export const CELL_H = 104;
export const GRID_PAD = 16;
/** A pointer must travel this far (px) before a press becomes a drag; less is a click. */
export const DRAG_THRESHOLD = 4;
/** The preference key the positions are saved under (a JSON string; the shell's preferences hold strings and booleans). */
export const POSITIONS_KEY = "desktop.iconPositions";

export interface Cell {
  col: number;
  row: number;
}
export type Positions = Record<string, Cell>;
export interface GridSize {
  cols: number;
  rows: number;
}

/** How many columns and rows fit in a [width] x [height] area (at least one of each). */
export function gridSize(width: number, height: number): GridSize {
  return {
    cols: Math.max(1, Math.floor((width - 2 * GRID_PAD) / CELL_W)),
    rows: Math.max(1, Math.floor((height - 2 * GRID_PAD) / CELL_H)),
  };
}

const inGrid = (c: Cell, g: GridSize): boolean => Number.isInteger(c.col) && Number.isInteger(c.row) && c.col >= 0 && c.row >= 0 && c.col < g.cols && c.row < g.rows;
const key = (c: Cell): string => `${c.col},${c.row}`;

/** The cell of every icon right now: saved cells that fit claim theirs first, the rest take the first free cell, column by column. */
export function place(ids: readonly string[], saved: Positions, g: GridSize): Positions {
  const out: Positions = {};
  const taken = new Set<string>();
  for (const id of ids) {
    const c = saved[id];
    if (c && inGrid(c, g) && !taken.has(key(c))) {
      out[id] = { col: c.col, row: c.row };
      taken.add(key(c));
    }
  }
  let next = 0;
  for (const id of ids) {
    if (out[id]) continue;
    // More icons than cells: the rest stack on the last cell rather than vanish.
    let cell: Cell = { col: g.cols - 1, row: g.rows - 1 };
    while (next < g.cols * g.rows) {
      const c = { col: Math.floor(next / g.rows), row: next % g.rows };
      next++;
      if (!taken.has(key(c))) {
        cell = c;
        taken.add(key(c));
        break;
      }
    }
    out[id] = cell;
  }
  return out;
}

/**
 * Drop [id] on [target]. A free cell takes it; an occupied cell swaps the two. A target outside the grid changes nothing. Returns the
 * positions to save: every shown icon's cell, except an icon shown in a fallback cell because its saved cell does not fit this
 * window, which keeps its saved cell (it comes back when the window is large again). Entries of apps no longer there are dropped.
 */
export function drop(ids: readonly string[], saved: Positions, g: GridSize, id: string, target: Cell): Positions {
  const shown = place(ids, saved, g);
  if (!ids.includes(id) || !inGrid(target, g)) return saved;
  const from = shown[id]!;
  const occupant = ids.find((o) => o !== id && shown[o]!.col === target.col && shown[o]!.row === target.row);
  const next: Positions = { ...shown, [id]: { ...target } };
  if (occupant) next[occupant] = { ...from };
  const out: Positions = {};
  for (const o of ids) {
    const s = saved[o];
    const displaced = s !== undefined && !inGrid(s, g) && o !== id && o !== occupant;
    out[o] = displaced ? s : next[o]!;
  }
  return out;
}

export type Direction = "left" | "right" | "up" | "down";

/** The icon an arrow key moves the focus to: the nearest one strictly in that direction (ties: the one closest to the same line). */
export function neighbor(shown: Positions, from: string, dir: Direction): string | null {
  const a = shown[from];
  if (!a) return null;
  let best: string | null = null;
  let bestScore = Infinity;
  for (const [id, c] of Object.entries(shown)) {
    if (id === from) continue;
    const dc = c.col - a.col;
    const dr = c.row - a.row;
    const along = dir === "left" ? -dc : dir === "right" ? dc : dir === "up" ? -dr : dr;
    if (along <= 0) continue;
    const across = Math.abs(dir === "left" || dir === "right" ? dr : dc);
    const score = along * 1000 + across;
    if (score < bestScore) {
      bestScore = score;
      best = id;
    }
  }
  return best;
}

/** The cell under a point ([x], [y] relative to the grid's top left), or null when it is outside the grid. */
export function cellAt(x: number, y: number, g: GridSize): Cell | null {
  const c = { col: Math.floor((x - GRID_PAD) / CELL_W), row: Math.floor((y - GRID_PAD) / CELL_H) };
  return x >= GRID_PAD && y >= GRID_PAD && inGrid(c, g) ? c : null;
}

/** Saved positions from the preference string; anything malformed is ignored (the default layout then applies). */
export function parsePositions(raw: string): Positions {
  try {
    const v = JSON.parse(raw) as unknown;
    if (typeof v !== "object" || v === null || Array.isArray(v)) return {};
    const out: Positions = {};
    for (const [id, p] of Object.entries(v)) {
      if (Array.isArray(p) && p.length === 2 && Number.isInteger(p[0]) && Number.isInteger(p[1]) && p[0] >= 0 && p[1] >= 0) out[id] = { col: p[0], row: p[1] };
    }
    return out;
  } catch {
    return {};
  }
}

/** The preference string for [p]: app id -> [column, row]. */
export const serializePositions = (p: Positions): string => JSON.stringify(Object.fromEntries(Object.entries(p).map(([id, c]) => [id, [c.col, c.row]])));
