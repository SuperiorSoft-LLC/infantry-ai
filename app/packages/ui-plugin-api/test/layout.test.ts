// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import { describe, expect, it } from "vitest";
import { arrangeBy, createLayoutProvider, type LayoutDocument } from "../src/layout";

const doc: LayoutDocument = {
  id: "t",
  name: "T",
  regions: [
    { id: "center", components: [{ id: "a" }, { id: "b", size: 2 }, { id: "c" }] },
    { id: "right", components: [{ id: "z" }] },
  ],
};

describe("the layout provider", () => {
  const p = createLayoutProvider(doc);
  it("says what goes in a region, with sizes, and nothing for an unknown one", () => {
    expect(p.componentsIn("center").map((c) => c.id)).toEqual(["a", "b", "c"]);
    expect(p.componentsIn("center")[1]?.size).toBe(2);
    expect(p.componentsIn("nowhere")).toEqual([]);
    expect(p.layout()).toBe(doc);
  });
  it("finds a component's position per region", () => {
    expect(p.positionOf("center", "c")).toBe(2);
    expect(p.positionOf("right", "c")).toBe(-1);
  });
  it("arranges items by the layout, unplaced ones last in their given order", () => {
    const items = ["x", "c", "y", "a"];
    expect(arrangeBy(p, "center", items, (s) => s)).toEqual(["a", "c", "x", "y"]);
  });
});
