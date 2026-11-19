// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

// Guard: a non-interactive element (div, span, li ...) with an onClick must say it is clickable
// (cursor-pointer, role, data-clickable), since the base CSS gives the hand cursor only to those.
const ROOTS = [join(__dirname, "../../ui/src"), join(__dirname, "../../../shell/src")];
const TAG = /<(div|span|li|tr|td|p|section|article|img|svg|h[1-6])\b((?:[^<>]|=>|\{[^{}]*\})*?)>/gs;

function files(dir: string): string[] {
  return readdirSync(dir).flatMap((n) => {
    const p = join(dir, n);
    if (statSync(p).isDirectory()) return n === "node_modules" ? [] : files(p);
    return p.endsWith(".tsx") && !p.endsWith(".test.tsx") ? [p] : [];
  });
}

describe("pointer cursor", () => {
  it("every non-interactive element with onClick is marked clickable", () => {
    const bad: string[] = [];
    for (const f of ROOTS.flatMap(files)) {
      const src = readFileSync(f, "utf8");
      for (const m of src.matchAll(TAG)) {
        const attrs = m[2];
        if (!/\bonClick\b/.test(attrs)) continue;
        if (/cursor-pointer|cursor-zoom|role=|data-clickable|cursor-not-allowed/.test(attrs)) continue;
        bad.push(`${f}: <${m[1]} onClick> needs cursor-pointer, role or data-clickable`);
      }
    }
    expect(bad).toEqual([]);
  });
});
