// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import { describe, expect, it } from "vitest";
import { shownPending } from "./send-follow";

type P = { clientId: string; sessionId: string; text: string; state: "sending" | "accepted" | "failed" };
const p = (state: P["state"], sessionId = "s1"): P => ({ clientId: "c1", sessionId, text: "hi", state });

describe("CHAT-ORDER-3 an acked send stays visible until its echo", () => {
  it("the old filter hid the send between prompt_ack and the TURN echo (the repro)", () => {
    const old = (m: Record<string, P>, sid: string) => Object.values(m).filter((x) => x.sessionId === sid && x.state !== "accepted");
    expect(old({ c1: p("accepted") }, "s1")).toEqual([]);
  });
  it("sending, accepted and failed sends are all drawn; other sessions are not", () => {
    for (const st of ["sending", "accepted", "failed"] as const) expect(shownPending({ c1: p(st) }, "s1")).toHaveLength(1);
    expect(shownPending({ c1: p("accepted", "s2") }, "s1")).toEqual([]);
  });
  it("once the echo settles it (store removes the clientId) the row is gone: no duplicate", () => {
    expect(shownPending({}, "s1")).toEqual([]);
  });
});
