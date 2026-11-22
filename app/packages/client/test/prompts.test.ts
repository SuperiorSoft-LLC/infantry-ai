// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import { describe, expect, it } from "vitest";
import { createPromptsStore, promptsOf, type Envelope } from "../src";

function link(up = true) {
  const sent: Envelope[] = [];
  const taps = new Set<(e: Envelope) => void>();
  return {
    sent,
    sendFrame: (e: Envelope) => (up ? (sent.push(e), true) : false),
    onFrame: (l: (e: Envelope) => void) => (taps.add(l), () => void taps.delete(l)),
    push: (e: Envelope) => taps.forEach((t) => t(e)),
  };
}
const req = (id: string, extra = {}) => ({
  type: "prompt_request",
  data: JSON.stringify({ promptId: id, sessionId: "s1", kind: "question", createdAt: 1, question: "Which?", options: [{ label: "A" }, { label: "B", description: "bee" }], multiSelect: true, allowOther: true, ...extra }),
});

describe("prompts store", () => {
  it("keeps an open question per session and drops it when resolved", () => {
    const l = link();
    const s = createPromptsStore(l);
    l.push(req("p1"));
    l.push(req("p2", { sessionId: "s2" }));
    expect(promptsOf(s.getState().open, "s1").map((p) => p.promptId)).toEqual(["p1"]);
    expect(s.getState().open.p1.multiSelect).toBe(true);
    l.push({ type: "prompt_resolved", data: JSON.stringify({ promptId: "p1", resolvedBy: "owner", outcome: "answered" }) });
    expect(Object.keys(s.getState().open)).toEqual(["p2"]);
  });

  it("answers with labels and other text, then removes the card", () => {
    const l = link();
    const s = createPromptsStore(l);
    l.push(req("p1"));
    expect(s.getState().answer("p1", ["A"], " custom ")).toBe(true);
    expect(l.sent[0].type).toBe("prompt_response");
    expect(JSON.parse(l.sent[0].data)).toEqual({ promptId: "p1", answers: ["A"], otherText: "custom" });
    expect(s.getState().open.p1).toBeUndefined();
  });

  it("keeps the card when the link is down", () => {
    const l = link(false);
    const s = createPromptsStore(l);
    l.push(req("p1"));
    expect(s.getState().answer("p1", ["B"])).toBe(false);
    expect(s.getState().open.p1).toBeDefined();
  });

  it("ignores garbage frames", () => {
    const l = link();
    const s = createPromptsStore(l);
    l.push({ type: "prompt_request", data: "not json" });
    expect(s.getState().open).toEqual({});
  });
});

describe("NEEDS-INPUT-1: routed cards and the waiting split", () => {
  it("draws no owner card for a question routed to the PM, and draws it once escalated", () => {
    const l = link();
    const s = createPromptsStore(l);
    l.push(req("p1", { route: "pm-then-owner" }));
    expect(s.getState().open.p1).toBeUndefined();
    l.push(req("p1"));
    expect(s.getState().open.p1?.question).toBe("Which?");
  });

  it("a waiting flag is `waiting`, an open question is `needs-input`, and the question outranks the flag", async () => {
    const { holdOf } = await import("../src");
    const waiting = { reason: "cargo build", since: 1, untilAt: 2, expiresAt: 3 } as never;
    expect(holdOf({ state: "BUSY", waiting })).toBe("waiting");
    expect(holdOf({ state: "WAITING_INPUT", waiting: undefined })).toBe("needs-input");
    expect(holdOf({ state: "WAITING_INPUT", waiting })).toBe("needs-input");
    expect(holdOf({ state: "BUSY", waiting: undefined })).toBeNull();
  });
});
