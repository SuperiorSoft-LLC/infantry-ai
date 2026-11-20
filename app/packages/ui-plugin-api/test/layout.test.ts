// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import { describe, expect, it } from "vitest";
import { arrangeBy, createLayoutProvider, createLayoutStore, type LayoutDocument } from "../src/layout";

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

describe("the layout store", () => {
  it("notifies subscribers on a swap, not on the same provider, and stops after unsubscribe", () => {
    const a = createLayoutProvider(doc);
    const b = createLayoutProvider({ ...doc, id: "u" });
    const store = createLayoutStore(a);
    let calls = 0;
    const off = store.subscribe(() => calls++);
    store.set(a);
    expect(calls).toBe(0);
    store.set(b);
    expect(store.get()).toBe(b);
    expect(calls).toBe(1);
    off();
    store.set(a);
    expect(calls).toBe(1);
  });
  it("carries a root only when the layout owns the screen", () => {
    expect(createLayoutProvider(doc).root).toBeUndefined();
    const root = () => null;
    expect(createLayoutProvider(doc, { root }).root).toBe(root);
  });
});
