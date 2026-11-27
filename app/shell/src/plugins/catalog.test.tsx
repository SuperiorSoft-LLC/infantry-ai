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

// HIDDEN-TABS-1: Pods, Profiles, Epics (centre) and Review (right) draw daemon core units
// (pods.rs PodsUnit, host_frames agent_registry, infantry-board epic_*), so no plugin declares them.
describe("core-backed tabs need no declaring plugin", () => {
  const reg = { tabs: [], web: {} } as unknown as SlotRegistry;
  it("shows Pods, Profiles and Epics in the centre", () => {
    const ids = tabsFor("center", reg).map((t) => t.id);
    for (const id of ["pods", "profiles", "epics"]) expect(ids).toContain(id);
  });
  it("shows Review in the right panel", () => {
    expect(tabsFor("right", reg).map((t) => t.id)).toContain("review");
  });
  it("keeps Calendar hidden until a provider declares infantry.calendar (no Rust backend yet)", () => {
    expect(tabsFor("center", reg).map((t) => t.id)).not.toContain("calendar");
  });
});
