// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import { describe, expect, it } from "vitest";
import { createArchiveStore, createBoardStore, type ArchiveAnswer, type Envelope } from "../src";

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
const item = (id: string) => ({ id, title: `T ${id}`, status: "done", archivedAt: 1, updatedAt: 1 });
const body = (e: Envelope) => JSON.parse(e.data);

describe("archive store", () => {
  it("lists, then searches, the project's archive and keeps the count", () => {
    const l = link();
    const s = createArchiveStore(l);
    s.getState().load("", "p1");
    expect(l.sent[0].type).toBe("archive_query");
    expect(body(l.sent[0])).toEqual({ op: "list", projectId: "p1", limit: 200 });
    l.push({ type: "archive_report", data: JSON.stringify({ text: "2 archived", items: [item("a"), item("b")], count: 2 }), requestId: l.sent[0].requestId });
    expect(s.getState().items.map((i) => i.id)).toEqual(["a", "b"]);
    expect(s.getState().count).toBe(2);
    s.getState().load("  fix ", "p1");
    expect(body(l.sent[1])).toEqual({ op: "search", text: "fix", projectId: "p1", limit: 200 });
  });

  it("ignores another client's report", () => {
    const l = link();
    const s = createArchiveStore(l);
    s.getState().load();
    l.push({ type: "archive_report", data: JSON.stringify({ text: "", items: [item("x")], count: 1 }), requestId: "other:1" });
    expect(s.getState().items).toEqual([]);
  });

  it("a dry run answers the callback and does not re-read", () => {
    const l = link();
    const s = createArchiveStore(l);
    let got: ArchiveAnswer | null = null;
    s.getState().apply({ op: "archive", epicId: "e1", dryRun: true }, (a) => (got = a));
    expect(body(l.sent[0])).toEqual({ op: "archive", epicId: "e1", dryRun: true });
    l.push({ type: "archive_report", data: JSON.stringify({ text: "would archive 3", count: 3 }), requestId: l.sent[0].requestId });
    expect(got).toEqual({ ok: true, report: { text: "would archive 3", items: [], count: 3 } });
    expect(l.sent).toHaveLength(1);
  });

  it("a restore re-reads the loaded list, and a refusal reaches the callback", () => {
    const l = link();
    const s = createArchiveStore(l);
    s.getState().load("q");
    l.push({ type: "archive_report", data: JSON.stringify({ text: "", items: [item("a")], count: 1 }), requestId: l.sent[0].requestId });
    s.getState().apply({ op: "restore", ids: ["a"] });
    l.push({ type: "archive_report", data: JSON.stringify({ text: "restored 1", count: 1 }), requestId: l.sent[1].requestId });
    expect(l.sent[2].type).toBe("archive_query");
    expect(body(l.sent[2]).text).toBe("q");
    let got: ArchiveAnswer | null = null;
    s.getState().apply({ op: "archive", ids: ["b"] }, (a) => (got = a));
    l.push({ type: "error", data: "archive_apply refused: in progress", requestId: l.sent[3].requestId });
    expect(got).toEqual({ ok: false, error: "archive_apply refused: in progress" });
  });

  it("the board follows the daemon's tickets push after an archive", () => {
    const l = link();
    const b = createBoardStore(l);
    l.push({ type: "tickets", data: JSON.stringify([{ id: "a", title: "A", status: "done", position: 0 }, { id: "b", title: "B", status: "todo", position: 0 }]) });
    l.push({ type: "tickets", data: JSON.stringify([{ id: "b", title: "B", status: "todo", position: 0 }]) });
    expect(Object.keys(b.getState().tickets)).toEqual(["b"]);
  });
});
