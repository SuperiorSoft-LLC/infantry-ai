// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import { describe, expect, it } from "vitest";
import { applyFrames, writeToDiff, type Envelope } from "../src";

const f = (type: string, data: unknown, sequence: number, extra: Partial<Envelope> = {}): Envelope => ({
  type, data: typeof data === "string" ? data : JSON.stringify(data), sessionId: "s1", sequence, ...extra,
});

describe("transcript reducer", () => {
  it("turns a stub run into user, notes, text and a finished line", () => {
    const items = applyFrames([], [
      f("turn", "hello", 3),
      f("stderr", "[infantry] * session started (stub · default)", 6),
      f("text_delta", "[stub] daemon is running.", 12),
      f("text_delta", " More text.", 14),
      f("finished", { exitCode: 0, outcome: "SUCCEEDED", detail: "" }, 19),
    ]);
    expect(items.map((i) => i.kind)).toEqual(["user", "note", "text", "finished"]);
    expect(items[2]).toMatchObject({ text: "[stub] daemon is running. More text." });
    expect(items[3]).toMatchObject({ outcome: "SUCCEEDED", exitCode: 0 });
  });

  it("keeps the identity of items a batch does not touch", () => {
    const a = applyFrames([], [f("turn", "q", 1), f("text_delta", "a", 2)]);
    const b = applyFrames(a, [f("text_delta", "b", 3)]);
    expect(b[0]).toBe(a[0]);
    expect(b[1]).not.toBe(a[1]);
    expect(b[1]).toMatchObject({ text: "ab" });
    expect(a[1]).toMatchObject({ text: "a" }); // the old item is untouched
  });

  it("shows a non-owner origin as Comms", () => {
    const [item] = applyFrames([], [f("turn", "ship it", 1, { origin: { type: "agent", role: "architect", agentId: "dd062cba-1111" } })]);
    expect(item).toMatchObject({ kind: "comms", from: "architect (dd062cba)", text: "ship it" });
  });

  it("reads the legacy COMMS header", () => {
    const [item] = applyFrames([], [f("turn", "[Infantry AI COMMS] message m1 from pm · thread t1\nPlease review.", 1)]);
    expect(item).toMatchObject({ kind: "comms", from: "pm", text: "Please review." });
  });

  it("coalesces thinking and tool calls, and makes a diff card of a write", () => {
    const items = applyFrames([], [
      f("reasoning", "one ", 1),
      f("reasoning", "two", 2),
      f("status", { state: "BUSY", tool: "Read", activity: "a.ts" }, 3),
      f("status", { state: "BUSY", tool: "Read", activity: "b.ts" }, 4),
      f("status", { state: "BUSY", write: { path: "x.ts", edits: [{ oldText: "a", newText: "b\nc" }] } }, 5),
    ]);
    expect(items.map((i) => i.kind)).toEqual(["thinking", "tools", "edit"]);
    expect(items[0]).toMatchObject({ count: 2, text: "one two" });
    expect(items[1]).toMatchObject({ count: 2, activity: "b.ts" });
    expect(items[2]).toMatchObject({ path: "x.ts", added: 2, removed: 1 });
  });

  it("reads a unified patch with line numbers", () => {
    const d = writeToDiff({ path: "a", patch: "--- a\n+++ b\n@@ -3,2 +3,3 @@\n keep\n-old\n+new\n+more" });
    expect(d).toMatchObject({ added: 2, removed: 1 });
    expect(d.lines[0]).toEqual({ oldNo: 3, newNo: 3, kind: "ctx", text: "keep" });
    expect(d.lines[1]).toEqual({ oldNo: 4, kind: "del", text: "old" });
  });
});
