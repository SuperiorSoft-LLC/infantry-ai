// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// RUN-TAB-1: the Run tab draws the daemon's core run unit, so no plugin has to declare it (only the Kotlin Infantry_Run did).
import { describe, expect, it } from "vitest";
import type { SlotRegistry } from "@infantry/client";
import { tabsFor } from "./catalog";

describe("centre tab gate", () => {
  it("shows Run with a registry where no plugin declares infantry.run", () => {
    const reg = { tabs: [], web: {} } as unknown as SlotRegistry;
    const ids = tabsFor("center", reg).map((t) => t.id);
    expect(ids).toContain("run");
    expect(ids).toContain("processes");
  });
  it("shows Run with no registry", () => {
    expect(tabsFor("center", null).map((t) => t.id)).toContain("run");
  });
});
