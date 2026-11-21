// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import { describe, expect, it } from "vitest";
import { createUsageStore, overrideLine, paceWords, ruleLine, unmeteredHarnesses, workersLine, type Envelope, type HarnessInfo, type MeterUsage } from "../src";

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

const H = 3_600_000;
const meter = (extra: Partial<MeterUsage> = {}): MeterUsage => ({
  meter: "claude", percent: 40, window: "Current week", resetsAt: 10 * H, paceDelta: -12.4, source: "live", windows: [], reason: null,
  ruleAllowance: 1, override: null, allowance: 1, running: 1, canStart: false, warning: null, updatedAt: 0, projectedPercent: 93.2, reasoning: null,
  ...extra,
});
const rule = { enabled: true, tiers: [{ below: 60, workers: 3 }, { workers: 1 }], mode: "pace", maxWorkers: 5, okProjected: 100, hardStopPercent: 95 };

describe("usage store", () => {
  it("asks on ready, keeps the report and sends overrides until the reset", () => {
    const l = link();
    const s = createUsageStore(l, () => 0);
    l.push({ type: "ready", data: "" });
    expect(l.sent.map((e) => e.type)).toEqual(["usage_get", "limits"]);
    l.push({ type: "usage_report", data: JSON.stringify({ meters: [meter()], rule, observedAt: 0 }) });
    expect(s.getState().report?.meters[0].meter).toBe("claude");
    expect(s.getState().override("claude", null)).toBe(true);
    const body = JSON.parse(l.sent[2].data);
    expect(body).toEqual({ meter: "claude", overrideWorkers: null, overrideUntil: 10 * H });
  });

  it("shows a refusal that answers its own request and ignores others", () => {
    const l = link();
    const s = createUsageStore(l);
    s.getState().typePercent("claude", 140);
    l.push({ type: "error", data: "other refused", requestId: "nope" });
    expect(s.getState().refusal).toBeNull();
    l.push({ type: "error", data: "usage_set refused: percent must be in 0..100", requestId: l.sent[0].requestId });
    expect(s.getState().refusal).toContain("0..100");
  });

  it("refuses to pretend when the link is down", () => {
    const s = createUsageStore(link(false));
    expect(s.getState().clearOverride("claude")).toBe(false);
    expect(s.getState().refusal).toContain("link is down");
  });
});

describe("harnesses without a meter", () => {
  const harnesses: HarnessInfo[] = [
    { id: "claude", available: true },
    { id: "pi", available: true, usageNote: "pi has no plan of its own." },
    { id: "codex", available: true, usageNote: "   " },
    { id: "gone", available: false, usageNote: "cannot run, and says why it has no meter" },
    { id: "quiet", available: false, unavailableReason: "quiet is not signed in" },
    { id: "other", usageNote: "availability not stated: listed" },
  ];

  it("lists the ones that cannot run or say why they have no meter, unless a meter already carries their name", () => {
    // one that runs and states nothing (claude, codex) has no card of its own
    expect(unmeteredHarnesses(harnesses, [meter()]).map((h) => h.id)).toEqual(["pi", "gone", "quiet", "other"]);
    // a worker running on pi adds its own meter card, so the note card steps aside
    expect(unmeteredHarnesses(harnesses, [meter(), meter({ meter: "pi", source: "none", percent: null })]).map((h) => h.id)).toEqual(["gone", "quiet", "other"]);
    // a meter that is itself down is still one card, not two
    expect(unmeteredHarnesses([{ id: "claude", available: false }], [meter()])).toEqual([]);
    expect(unmeteredHarnesses([], [meter()])).toEqual([]);
  });
});

describe("usage lines", () => {
  it("says the rule, the pace, the workers and the override", () => {
    expect(ruleLine(rule)).toBe("pace · up to 5 ticket workers while projected <= 100% at reset · 1 from 95% used");
    expect(ruleLine({ ...rule, mode: "bands" })).toBe("<60% -> 3 · else -> 1 ticket workers");
    expect(paceWords(-12.4)).toBe("12 pts under pace");
    expect(paceWords(0.2)).toBe("on pace");
    expect(workersLine(meter())).toBe("1 of 1 workers running");
    expect(workersLine(meter({ allowance: null }))).toBe("1 workers running · no limit");
    const o = meter({ override: { workers: null, until: 38 * H + 45 * 60_000 }, allowance: null });
    expect(overrideLine(o, 0)).toBe("allowance raised to no limit · ends in 1d 14h (pacing rule says 1)");
  });
});
