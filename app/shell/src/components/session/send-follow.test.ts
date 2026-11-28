// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import { describe, expect, it } from "vitest";
import { applyFrames, Frame, type Envelope } from "@infantry/client";
import { sentSince } from "./send-follow";

/** A long seeded transcript: thousands of items with tool calls and thinking, as the store holds them. */
function seeded(n: number) {
  const frames: Envelope[] = [];
  for (let i = 0; i < n; i++) {
    const seq = i * 4 + 1;
    frames.push({ type: Frame.TURN, data: `q${i}`, sequence: seq } as Envelope);
    frames.push({ type: Frame.REASONING, data: "hmm", sequence: seq + 1 } as Envelope);
    frames.push({ type: Frame.STATUS, data: JSON.stringify({ tool: "Bash", activity: `run ${i}` }), sequence: seq + 2 } as Envelope);
    frames.push({ type: Frame.TEXT_DELTA, data: `a${i}`, sequence: seq + 3 } as Envelope);
  }
  return applyFrames([], frames);
}

describe("CHAT-ORDER-2 send follows to the latest", () => {
  it("a new pending send is detected, even into a long transcript", () => {
    const items = seeded(1000);
    expect(items.length).toBeGreaterThan(3000);
    const rows = [...items.map((i) => i.id), "client-1"];
    expect(rows[rows.length - 1]).toBe("client-1");
    expect(sentSince(new Set(), ["client-1"])).toBe(true);
  });
  it("the same pending send, or one leaving, does not move the view again", () => {
    expect(sentSince(new Set(["client-1"]), ["client-1"])).toBe(false);
    expect(sentSince(new Set(["client-1"]), [])).toBe(false);
  });
  it("the echoed turn lands last, after the history", () => {
    const items = seeded(1000);
    const next = applyFrames(items, [{ type: Frame.TURN, data: "newest", sequence: 99999 } as Envelope]);
    expect(next[next.length - 1]).toMatchObject({ kind: "user", text: "newest" });
  });
});
