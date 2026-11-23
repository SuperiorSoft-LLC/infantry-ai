// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import { describe, expect, it } from "vitest";
import { createQueryStore, type Envelope } from "../src";
import type { FrameLink } from "../src/stores/link";

function fake() {
  const sent: Envelope[] = [];
  const listeners = new Set<(e: Envelope) => void>();
  const link: FrameLink = { sendFrame: (e) => (sent.push(e), true), onFrame: (l) => (listeners.add(l), () => listeners.delete(l)) };
  return { link, sent, push: (e: Envelope) => listeners.forEach((l) => l(e)) };
}
const make = () => {
  const f = fake();
  const store = createQueryStore<number[]>(f.link, { prefix: "q", request: "list", answers: ["list"], empty: [], parse: (r) => (Array.isArray(r) ? (r as number[]) : null) });
  return { f, store };
};

describe("query store refusal", () => {
  it("a refusal keeps the last list, and the next good answer clears it and shows the new list", () => {
    const { f, store } = make();
    store.getState().refresh("{}");
    f.push({ type: "list", data: "[1]", requestId: f.sent[0].requestId });
    store.getState().refresh();
    f.push({ type: "error", data: "q: plugin refused", requestId: f.sent[1].requestId });
    expect(store.getState().refusal).toBe("q: plugin refused");
    expect(store.getState().data).toEqual([1]);
    store.getState().refresh();
    f.push({ type: "list", data: "[2,3]", requestId: f.sent[2].requestId });
    expect(store.getState().refusal).toBeNull();
    expect(store.getState().data).toEqual([2, 3]);
  });
  it("an answer that is not understood still shows a refusal", () => {
    const { f, store } = make();
    store.getState().refresh("{}");
    f.push({ type: "list", data: "{}", requestId: f.sent[0].requestId });
    expect(store.getState().refusal).toMatch(/not understood/);
  });
});
