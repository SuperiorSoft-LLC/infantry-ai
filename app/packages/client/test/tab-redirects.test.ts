// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// UI-DECLUTTER-1: the removed centre tabs (Settings, Prompts) lead to where they live now; the shell files stay wired to it.
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";
import { removedTabTarget } from "../src";

const shell = (p: string) => readFileSync(resolve(__dirname, "../../../shell/src", p), "utf8");

describe("removed centre tabs", () => {
  it("Settings leads to the cog's dialog, Prompts to the right panel, others to nothing", () => {
    for (const id of ["settings", "infantry.settings"]) expect(removedTabTarget(id)).toEqual({ kind: "dialog", dialog: "settings" });
    for (const id of ["prompts", "infantry.prompts"]) expect(removedTabTarget(id)).toEqual({ kind: "right", tab: "prompts" });
    for (const id of ["skills", "scripts", "services", "cli"]) expect(removedTabTarget(id)).toBeNull();
  });
  it("the centre bar and default layout no longer list them; the right panel keeps Prompts", () => {
    const cat = shell("plugins/catalog.tsx");
    const centre = cat.slice(cat.indexOf("const CENTRE"), cat.indexOf("/** Right-panel tabs"));
    expect(centre).not.toMatch(/id: "(prompts|settings)"/);
    expect(cat.slice(cat.indexOf("const RIGHT"))).toMatch(/id: "prompts", declared: "infantry.prompts"/);
    const layout = shell("plugins/default-layout.ts");
    const c = layout.slice(layout.indexOf('id: "center"'), layout.indexOf('id: "right"'));
    expect(c).not.toMatch(/"prompts"|"infantry\.settings"\s*,?\s*\)/);
  });
  it("the session header keeps pop-out and drops the three action buttons", () => {
    const h = shell("components/session/session-header.tsx");
    expect(h).toContain("Pop out this chat");
    expect(h).not.toContain("SessionActionButtons");
  });
});
