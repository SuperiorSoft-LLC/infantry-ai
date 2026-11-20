// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Ports of the Kotlin ChangeBoxModel tests: the line diff, created vs edited, counts, multi-edit headers.
import { describe, expect, it } from "vitest";
import { applyFrames, lineDiff, linesOf, writeToDiff, type Envelope } from "../src";

const f = (type: string, data: unknown, sequence: number): Envelope => ({ type, data: JSON.stringify(data), sessionId: "s1", sequence });

describe("linesOf", () => {
  it("a final newline ends the last line instead of opening an empty one", () => {
    expect(linesOf("a\nb\n")).toEqual(["a", "b"]);
    expect(linesOf("a\r\nb")).toEqual(["a", "b"]);
    expect(linesOf("")).toEqual([]);
  });
});

describe("lineDiff", () => {
  it("keeps the common head and tail as context and marks the middle", () => {
    const d = lineDiff(["a", "b", "c"], ["a", "X", "c"]);
    expect(d.map((l) => l.kind)).toEqual(["ctx", "del", "add", "ctx"]);
    expect(d[1]).toMatchObject({ oldNo: 2, text: "b" });
    expect(d[2]).toMatchObject({ newNo: 2, text: "X" });
  });
  it("finds the common lines inside the changed middle", () => {
    const d = lineDiff(["1", "k", "2"].concat(["z"]), ["a", "k", "b", "z"]);
    expect(d.filter((l) => l.kind === "ctx").map((l) => l.text)).toEqual(["k", "z"]);
  });
  it("a pure insertion has no deletions", () => {
    expect(lineDiff(["a"], ["a", "b"]).map((l) => l.kind)).toEqual(["ctx", "add"]);
  });
});

describe("writeToDiff as a change box", () => {
  it("a created file counts every line as added", () => {
    expect(writeToDiff({ path: "n.txt", content: "a\nb\nc\n" })).toMatchObject({ added: 3, removed: 0 });
  });
  it("an edit counts only the changed lines", () => {
    expect(writeToDiff({ path: "a", edits: [{ oldText: "a\nb\nc", newText: "a\nX\nc" }] })).toMatchObject({ added: 1, removed: 1 });
  });
  it("several edits get an '@@ edit i of n @@' header each", () => {
    const d = writeToDiff({ path: "a", edits: [{ oldText: "1", newText: "2" }, { oldText: "3", newText: "4" }] });
    expect(d.lines.filter((l) => l.kind === "hunk").map((l) => l.text)).toEqual(["@@ edit 1 of 2 @@", "@@ edit 2 of 2 @@"]);
  });
  it("a write item says created only for whole-file content", () => {
    const items = applyFrames([], [
      f("status", { state: "BUSY", write: { path: "n", content: "x" } }, 1),
      f("status", { state: "BUSY", write: { path: "e", edits: [{ oldText: "a", newText: "b" }] } }, 2),
    ]);
    expect(items.map((i) => (i.kind === "edit" ? i.created : null))).toEqual([true, false]);
  });
});
