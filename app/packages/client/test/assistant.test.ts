// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import { describe, expect, it } from "vitest";
import { Frame, assistantOf, createAssistantAskStore, inProject, withoutAssistant, type Envelope } from "../src";

function link(up = true) {
  const sent: Envelope[] = [];
  const taps = new Set<(e: Envelope) => void>();
  return {
    sent,
    sendFrame: (e: Envelope) => (up ? (sent.push(e), true) : false),
    onFrame: (l: (e: Envelope) => void) => (taps.add(l), () => void taps.delete(l)),
    push: (e: Envelope) => taps.forEach((t) => t(e)),
    listeners: () => taps.size,
  };
}

const v = (id: string, global?: boolean, projectId?: string | null) => ({ record: { id, global, projectId } });

describe("the Assistant among the sessions", () => {
  const all = [v("a"), v("g", true), v("b", false, "p1")];

  it("finds the installation-wide session, or none", () => {
    expect(assistantOf(all)?.record.id).toBe("g");
    expect(assistantOf([v("a"), v("b")])).toBeNull();
    expect(assistantOf([])).toBeNull();
  });

  it("keeps it out of the agents lists, and leaves a list without it as it is", () => {
    expect(withoutAssistant(all).map((x) => x.record.id)).toEqual(["a", "b"]);
    const plain = [v("a"), v("b")];
    expect(withoutAssistant(plain)).toBe(plain);
  });

  it("is hidden whatever project is selected (it is projectless, so inProject alone would always show it)", () => {
    expect(inProject(all, "p1").map((x) => x.record.id)).toContain("g");
    expect(withoutAssistant(inProject(all, "p1")).map((x) => x.record.id)).toEqual(["a", "b"]);
  });
});

describe("the Assistant ask", () => {
  it("sends the assistant frame under its own request id and starts with no refusal", () => {
    const l = link();
    const s = createAssistantAskStore(l);
    expect(s.getState().refusal).toBeNull();
    s.getState().ask();
    expect(l.sent).toHaveLength(1);
    expect(l.sent[0].type).toBe(Frame.ASSISTANT);
    expect(l.sent[0].requestId).toMatch(/^assistant:/);
    expect(s.getState().refusal).toBeNull();
  });

  it("keeps the daemon's refusal of its own ask, and nobody else's", () => {
    const l = link();
    const s = createAssistantAskStore(l);
    s.getState().ask();
    l.push({ type: "error", data: "role_save refused: nope", requestId: "roles:x-1" });
    l.push({ type: "error", data: "assistant refused: this daemon does not serve that frame yet" });
    expect(s.getState().refusal).toBeNull();
    l.push({ type: "error", data: "assistant refused: this daemon does not serve that frame yet", requestId: l.sent[0].requestId });
    expect(s.getState().refusal).toBe("assistant refused: this daemon does not serve that frame yet");
  });

  it("clears the refusal on a new ask, with a new request id", () => {
    const l = link();
    const s = createAssistantAskStore(l);
    s.getState().ask();
    l.push({ type: "error", data: "assistant refused: no harness", requestId: l.sent[0].requestId });
    expect(s.getState().refusal).toBe("assistant refused: no harness");
    s.getState().ask();
    expect(s.getState().refusal).toBeNull();
    expect(l.sent).toHaveLength(2);
    expect(l.sent[1].requestId).not.toBe(l.sent[0].requestId);
    // The first ask was answered already: a late copy of its refusal is not this ask's.
    l.push({ type: "error", data: "assistant refused: no harness", requestId: l.sent[0].requestId });
    expect(s.getState().refusal).toBeNull();
  });

  it("is answered by the Assistant itself: no refusal, and a late refusal of that ask is not taken", () => {
    const l = link();
    const s = createAssistantAskStore(l);
    s.getState().ask();
    const requestId = l.sent[0].requestId;
    l.push({ type: "session", data: '{"id":"g","global":true}', sessionId: "g", requestId });
    expect(s.getState().refusal).toBeNull();
    l.push({ type: "error", data: "assistant refused: stale", requestId });
    expect(s.getState().refusal).toBeNull();
  });

  it("says so when the link is down, and stops listening once disposed", () => {
    const down = link(false);
    const s = createAssistantAskStore(down);
    s.getState().ask();
    expect(s.getState().refusal).toBe("assistant not sent: the daemon link is down");

    const l = link();
    const t = createAssistantAskStore(l);
    t.getState().ask();
    t.getState().dispose();
    expect(l.listeners()).toBe(0);
    l.push({ type: "error", data: "assistant refused: late", requestId: l.sent[0].requestId });
    expect(t.getState().refusal).toBeNull();
  });
});
