// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import { describe, expect, it } from "vitest";
import { attentionCount, createAttentionStore, createMailStore, threadsOf, unreadCount, type Envelope } from "../src";

function link() {
  const sent: Envelope[] = [];
  const taps = new Set<(e: Envelope) => void>();
  return {
    sent,
    sendFrame: (e: Envelope) => (sent.push(e), true),
    onFrame: (l: (e: Envelope) => void) => (taps.add(l), () => void taps.delete(l)),
    push: (e: Envelope) => taps.forEach((t) => t(e)),
  };
}
const msg = (id: string, from: string, to: string, seq: number) => ({ id, threadId: "t1", seq, from, to, subject: seq === 1 ? "Hi" : "", body: `b${id}`, sentAt: seq, state: "DELIVERED" });

describe("mail store", () => {
  it("groups threads, counts the owner's unread and marks read", () => {
    const l = link();
    const s = createMailStore(l);
    s.getState().ensureLoaded();
    expect(l.sent[0]).toMatchObject({ type: "mail_inbox", data: "*" });
    l.push({ type: "mails", data: JSON.stringify([msg("m1", "owner", "dev", 1), msg("m2", "dev", "owner", 2)]) });
    const [t] = threadsOf(s.getState().messages, "inbox");
    expect(t.subject).toBe("Hi");
    expect(t.unread).toBe(1);
    s.getState().markRead("m2");
    expect(unreadCount(s.getState().messages)).toBe(0);
    expect(l.sent[1]).toMatchObject({ type: "mail_read", data: "m2" });
    expect(s.getState().send("dev", "s", "body")).toBe(true);
    expect(JSON.parse(l.sent[2].data)).toMatchObject({ from: "owner", to: "dev" });
  });
});

describe("attention store", () => {
  it("counts open items and questions and acks", () => {
    const l = link();
    const s = createAttentionStore(l);
    l.push({ type: "attention_items", data: JSON.stringify([{ id: "a1", at: 1, kind: "trigger_failed", outcome: "x", detail: "", ackedAt: null }]) });
    l.push({ type: "question", data: JSON.stringify({ id: "q1", sessionId: "s", question: "?", options: [], recommended: "a", reason: "r", state: "open", createdAt: 1 }) });
    expect(attentionCount(s.getState())).toBe(2);
    s.getState().ack("a1");
    expect(attentionCount(s.getState())).toBe(1);
    expect(l.sent[0]).toMatchObject({ type: "attention_ack", data: "a1" });
  });
});
