// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import { describe, expect, it } from "vitest";
import { scrollToReveal, stripEdges, wheelDelta } from "../src/scroll-strip";

describe("stripEdges", () => {
  it("shows no arrow when the row fits", () => {
    expect(stripEdges(0, 300, 300)).toEqual({ overflow: false, atStart: true, atEnd: true });
  });
  it("hides each arrow at its end", () => {
    expect(stripEdges(0, 300, 600)).toMatchObject({ overflow: true, atStart: true, atEnd: false });
    expect(stripEdges(150, 300, 600)).toMatchObject({ atStart: false, atEnd: false });
    expect(stripEdges(300, 300, 600)).toMatchObject({ atStart: false, atEnd: true });
  });
});

describe("scrollToReveal", () => {
  it("leaves a visible item alone", () => expect(scrollToReveal(0, 300, 50, 80)).toBe(0));
  it("scrolls left to an item cut off at the start", () => expect(scrollToReveal(200, 300, 100, 80)).toBe(92));
  it("scrolls right to an item cut off at the end", () => expect(scrollToReveal(0, 300, 400, 100)).toBe(208));
  it("never goes below zero", () => expect(scrollToReveal(10, 300, 0, 80)).toBe(0));
});

describe("wheelDelta", () => {
  it("turns a vertical wheel into sideways scroll", () => expect(wheelDelta(0, 40)).toBe(40));
  it("leaves a sideways gesture to the browser", () => expect(wheelDelta(30, 5)).toBe(0));
});
