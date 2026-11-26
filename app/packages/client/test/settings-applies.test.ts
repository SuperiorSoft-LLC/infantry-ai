// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// SVC-FIX-7 item 4: the Settings card shows "Applies to:" once, even when a manifest's text already carries the label.
import { readFileSync, readdirSync, existsSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

const card = readFileSync(resolve(__dirname, "../../../shell/src/components/settings/setting-card.tsx"), "utf8");
const pattern = card.match(/replace\((\/\^applies to:[^/]*\/i)/)?.[1];

function strip(applies: string): string {
  const [, body, flags] = pattern!.match(/^\/(.*)\/(\w*)$/)!;
  return applies.trim().replace(new RegExp(body, flags), "").trim();
}

describe("Settings card applies-to line", () => {
  it("strips a manifest's own label so the card never doubles it", () => {
    expect(pattern, "setting-card.tsx strips the label").toBeTruthy();
    expect(card).toContain("Applies to: {shown}");
    expect(strip("Applies to: the Sites tab")).toBe("the Sites tab");
    expect(strip("the Sites tab")).toBe("the Sites tab");
  });

  it("every official plugin's applies text renders with one label", () => {
    const root = resolve(__dirname, "../../../../rust/plugins/official");
    let seen = 0;
    for (const m of readdirSync(root)) {
      const f = resolve(root, m, "module.json");
      if (!existsSync(f)) continue;
      for (const [, v] of readFileSync(f, "utf8").matchAll(/"applies"\s*:\s*"([^"]*)"/g)) {
        seen++;
        expect(`Applies to: ${strip(v)}`).not.toMatch(/Applies to:\s*Applies to/i);
      }
    }
    expect(seen).toBeGreaterThan(0);
  });
});
