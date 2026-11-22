// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// RR-9: Add runner sends `runner_admin add` with the two values and the new row shows; an offline runner shows its reason.
import { describe, expect, it } from "vitest";
import type { Envelope } from "@infantry/ui-plugin-api";
import { createRunnersStore, pingLine, statusOf } from "../src/store";

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
const answer = (l: ReturnType<typeof link>, i: number, body: unknown) => l.push({ type: "runner_admin", requestId: l.sent[i].requestId, data: JSON.stringify(body) });
const LOCAL = { name: "local", kind: "embedded", state: "online", url: "", contract: "1.0" };

describe("the Runners store", () => {
  it("Add runner sends runner_admin add with the two values, then lists the new row", () => {
    const l = link();
    const s = createRunnersStore(l);
    s.getState().refresh();
    answer(l, 0, { ok: true, runners: [LOCAL] });
    s.getState().add(" wss://10.0.0.5:18991 ", " tok.abc ");
    expect(s.getState().adding).toBe(true);
    expect(l.sent[1]).toMatchObject({ type: "runner_admin" });
    expect(JSON.parse(l.sent[1].data)).toEqual({ op: "add", endpoint: "wss://10.0.0.5:18991", bearer: "tok.abc" });
    answer(l, 1, { ok: true, name: "box", url: "wss://10.0.0.5:18991", contract: "1.0" });
    expect(s.getState().adding).toBe(false);
    expect(JSON.parse(l.sent[2].data)).toEqual({ op: "list" });
    answer(l, 2, { ok: true, runners: [LOCAL, { name: "box", url: "wss://10.0.0.5:18991", state: "online", rttMs: 12, contract: "1.0" }] });
    expect(s.getState().runners.map((r) => r.name)).toEqual(["local", "box"]);
    expect(pingLine(s.getState().runners[1])).toBe("contract 1.0 · 12 ms");
    expect(JSON.stringify(s.getState())).not.toContain("tok.abc");
  });

  it("shows the daemon's refusal of an add as written, inside the dialog", () => {
    const l = link();
    const s = createRunnersStore(l);
    s.getState().add("http://x", "b");
    answer(l, 0, { ok: false, error: "the endpoint must start with wss://" });
    expect(s.getState().addError).toBe("the endpoint must start with wss://");
    expect(s.getState().adding).toBe(false);
    expect(s.getState().refusal).toBeNull();
  });

  it("an offline runner shows its reason; an outdated one too", () => {
    expect(statusOf({ name: "box", url: "wss://x", state: "offline", reason: "connection refused" })).toEqual({ dot: "error", word: "offline", reason: "connection refused" });
    expect(statusOf({ name: "box", url: "wss://x", state: "outdated", reason: "the runner moved: contract 2" }).reason).toBe("the runner moved: contract 2");
    expect(statusOf({ name: "local", url: "", state: "online" }).reason).toBeNull();
    expect(statusOf({ name: "box", url: "wss://x", state: "offline" }).reason).toBe("no answer");
  });

  it("check keeps the rows (pong included) and detach drops them", () => {
    const l = link();
    const s = createRunnersStore(l);
    s.getState().check("box");
    expect(JSON.parse(l.sent[0].data)).toEqual({ op: "check", name: "box" });
    answer(l, 0, { ok: true, name: "box", state: "online", rows: [{ id: "link", ok: true, detail: "pong from 'box' in 9 ms" }] });
    expect(s.getState().checks.box[0].detail).toContain("pong");
    expect(s.getState().checking).toBeNull();
    s.getState().detach("box");
    expect(JSON.parse(l.sent[2].data)).toEqual({ op: "detach", name: "box" });
    answer(l, 2, { ok: true, name: "box", remote: true });
    expect(s.getState().checks.box).toBeUndefined();
  });

  it("a down link is a refusal, not a silent no-op", () => {
    const s = createRunnersStore(link(false));
    s.getState().add("wss://a", "b");
    expect(s.getState().adding).toBe(false);
    expect(s.getState().refusal).toMatch(/link is down/);
  });
});
