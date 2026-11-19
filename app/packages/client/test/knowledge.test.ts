// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import { describe, expect, it } from "vitest";
import { createKnowledgeStore, decisionTimeline, notesByFolder, type Envelope } from "../src";

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
const note = (name: string, folder = "") => ({ projectId: "p1", name, folder, description: `d ${name}`, body: "b" });

describe("knowledge store", () => {
  it("loads notes, decisions and issues; rolls a refused save back", () => {
    const l = link();
    const s = createKnowledgeStore(l);
    s.getState().setProject("p1");
    expect(l.sent.map((e) => e.type)).toEqual(["memory_list", "decision_list", "issue_list"]);
    l.push({ type: "memory_notes", data: JSON.stringify([note("a"), note("b", "arch")]), requestId: l.sent[0].requestId });
    expect(notesByFolder(s.getState().notes).map((g) => g.folder)).toEqual(["", "arch"]);

    s.getState().saveNote({ name: "c", description: "x", body: "y" });
    expect(s.getState().notes).toHaveLength(3);
    l.push({ type: "error", data: "memory_save refused: name taken", requestId: l.sent[3].requestId });
    expect(s.getState().notes).toHaveLength(2);
    expect(s.getState().refusal).toContain("refused");

    l.push({ type: "decision_item", data: JSON.stringify({ projectId: "p1", number: 1, ref: "D1", summary: "one", body: "", createdAt: 1 }) });
    s.getState().addDecision("two", undefined, "D1");
    expect(JSON.parse(l.sent[4].data)).toMatchObject({ projectId: "p1", summary: "two", supersedes: "D1" });
    l.push({ type: "decision_item", data: JSON.stringify({ projectId: "p1", number: 2, ref: "D2", summary: "two", body: "", supersedes: "D1", createdAt: 2 }) });
    const [d2, d1] = decisionTimeline(s.getState().decisions);
    expect(d2.ref).toBe("D2");
    expect(d1.supersededBy).toBe("D2");

    l.push({ type: "issue_item", data: JSON.stringify({ projectId: "p1", number: 1, ref: "I1", summary: "bug", location: "a.ts", implicit: false, status: "open", createdAt: 1, events: [] }) });
    expect(s.getState().issues[0].status).toBe("open");
  });
});
