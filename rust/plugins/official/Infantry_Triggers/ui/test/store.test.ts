// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Moved with the store from app/packages/client/test/plugins.test.ts (WEBUI-1): the same frames, the same expectations.
import { describe, expect, it } from "vitest";
import type { Envelope } from "@infantry/ui-plugin-api";
import { createTriggersStore } from "../src/store";

function link(up = true) {
  const sent: Envelope[] = [];
  const taps = new Set<(e: Envelope) => void>();
  return {
    sent,
    sendFrame: (e: Envelope) => (up ? (sent.push(e), true) : false),
    onFrame: (l: (e: Envelope) => void) => (taps.add(l), () => void taps.delete(l)),
    push: (e: Envelope) => taps.forEach((t) => t(e)),
  };
}

describe("the Triggers store", () => {
  it("folds trigger pushes and keeps a refusal", () => {
    const l = link();
    const s = createTriggersStore(l);
    s.getState().refresh();
    const t = { id: "t1", name: "Nightly", kind: "manual", action: { kind: "start_next" }, enabled: true, state: "armed", coalesceMs: 0, ceilingRuns: 6, ceilingWindowMs: 1, createdAt: 1 };
    l.push({ type: "triggers", requestId: l.sent[0].requestId, data: JSON.stringify([t]) });
    l.push({ type: "trigger", data: JSON.stringify({ ...t, state: "tripped" }) });
    expect(s.getState().triggers.map((x) => x.state)).toEqual(["tripped"]);
    s.getState().fire("t1");
    expect(l.sent[1]).toMatchObject({ type: "trigger_fire", data: "t1" });
    l.push({ type: "error", requestId: l.sent[1].requestId, data: "trigger_fire refused: tripped" });
    expect(s.getState().refusal).toBe("trigger_fire refused: tripped");
  });
});
