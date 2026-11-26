// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import { describe, expect, it } from "vitest";
import { attentionCount, createAttentionStore, createMailStore, inboxDecisions, threadsOf, unreadCount, type Envelope } from "../src";

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

describe("MAIL-OWNER-1: clean inbox", () => {
  const m = (id: string, threadId: string, sentAt: number, extra: object = {}) => ({ id, threadId, seq: sentAt, from: "pm", to: "owner", subject: id, body: `b${id}`, sentAt, state: "DELIVERED", ...extra });

  it("mark all read reads every unread owner mail; archive read files read mail away, unread stays", () => {
    const l = link();
    const s = createMailStore(l);
    l.push({ type: "mails", data: JSON.stringify([m("old", "t1", 1, { state: "READ", readAt: 1 }), m("new", "t2", 900), { ...m("out", "t3", 5), from: "owner", to: "pm" }]) });
    expect(threadsOf(s.getState().messages, "inbox").map((t) => t.threadId)).toEqual(["t2", "t1"]);
    expect(s.getState().archiveRead()).toBe(1);
    expect(l.sent.at(-1)).toMatchObject({ type: "mail_archive", data: JSON.stringify(["old"]) });
    expect(threadsOf(s.getState().messages, "inbox").map((t) => t.threadId)).toEqual(["t2"]);
    expect(threadsOf(s.getState().messages, "all")).toHaveLength(3);
    expect(s.getState().markAllRead()).toBe(1);
    expect(l.sent.at(-1)).toMatchObject({ type: "mail_read", data: "new" });
    expect(unreadCount(s.getState().messages)).toBe(0);
    expect(s.getState().archiveRead()).toBe(1);
    expect(s.getState().archiveRead()).toBe(0);
  });

  it("folds the same mail sent twice into one, counted once", () => {
    const l = link();
    const s = createMailStore(l);
    l.push({ type: "mails", data: JSON.stringify([m("a", "t1", 1, { subject: "Done", body: "ticket done" }), m("b", "t1", 2, { subject: "Done", body: "ticket done" }), m("c", "t1", 3)]) });
    const [t] = threadsOf(s.getState().messages, "inbox");
    expect(t.messages.map((x) => [x.id, x.folded ?? 1])).toEqual([["b", 2], ["c", 1]]);
    expect(unreadCount(s.getState().messages)).toBe(2);
  });
});

describe("MAIL-OWNER-1: decisions in the inbox", () => {
  const q = (id: string, state: string, createdAt: number, resolvedAt?: number) => ({ id, sessionId: "s", question: id, options: [{ label: "A" }, { label: "B" }], recommended: "A", reason: "r", state, createdAt, resolvedAt, answer: state === "answered" ? "B" : null });

  it("lists open decisions first (oldest first), then recent answered ones; answering sends question_answer", () => {
    const l = link();
    const s = createAttentionStore(l);
    const now = 10 * 86_400_000;
    l.push({ type: "questions", data: JSON.stringify([q("o2", "open", now - 5), q("o1", "open", now - 9), q("done", "answered", 1, now - 1000), q("ancient", "answered", 1, 2)]) });
    expect(inboxDecisions(s.getState().questions, now).map((x) => x.id)).toEqual(["o1", "o2", "done"]);
    expect(s.getState().answer("o1", "B")).toBe(true);
    expect(l.sent.at(-1)).toMatchObject({ type: "question_answer", data: JSON.stringify({ questionId: "o1", answer: "B" }) });
    l.push({ type: "question", data: JSON.stringify({ ...q("o1", "answered", now - 9, now), answer: "B" }) });
    expect(attentionCount(s.getState())).toBe(1);
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
