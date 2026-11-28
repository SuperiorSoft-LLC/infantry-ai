// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// PARITY-AUDIT-1 #19: the attention chip's target and the answered-question history.
import { describe, expect, it } from "vitest";
import { chipQuestionSession, questionHistory, type OwnerQuestion } from "../src";

const q = (id: string, over: Partial<OwnerQuestion> = {}): OwnerQuestion => ({
  id, sessionId: `s-${id}`, question: `question ${id}`, options: [], recommended: "", reason: "", state: "open", createdAt: 1, ...over,
});
const map = (...list: OwnerQuestion[]) => Object.fromEntries(list.map((x) => [x.id, x]));

describe("chipQuestionSession", () => {
  it("is the asking session with one open question", () => {
    expect(chipQuestionSession(map(q("a"), q("b", { state: "answered" })))).toBe("s-a");
  });
  it("is null with none or several open questions", () => {
    expect(chipQuestionSession({})).toBeNull();
    expect(chipQuestionSession(map(q("a"), q("b")))).toBeNull();
  });
});

describe("questionHistory", () => {
  const old = q("old", { state: "answered", answer: "Yes", resolvedBy: "owner", role: "frontend", resolvedAt: 1000 });
  const ancient = q("anc", { state: "answered", otherText: "my words", resolvedBy: "auto", resolvedAt: 10 }); // far past 7 days
  const qs = map(q("open"), old, ancient);
  it("lists every answered question newest first, no age cut", () => {
    expect(questionHistory(qs).map((x) => x.id)).toEqual(["old", "anc"]);
  });
  it("searches question, answer, role and who answered", () => {
    expect(questionHistory(qs, "MY WORDS").map((x) => x.id)).toEqual(["anc"]);
    expect(questionHistory(qs, "frontend").map((x) => x.id)).toEqual(["old"]);
    expect(questionHistory(qs, "auto").map((x) => x.id)).toEqual(["anc"]);
    expect(questionHistory(qs, "nothing")).toEqual([]);
  });
});
