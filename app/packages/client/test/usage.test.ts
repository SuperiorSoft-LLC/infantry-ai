// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import { describe, expect, it } from "vitest";
import {
  createUsageStore,
  meterLines,
  nextTryLine,
  overrideLine,
  paceWords,
  readingNote,
  retryAtFor,
  ruleLine,
  unmeteredHarnesses,
  workersLine,
  type Envelope,
  type HarnessInfo,
  type MeterUsage,
  type ProviderLimits,
} from "../src";

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

// #147: the Claude card showed "no reading" and the same rate limit three times over (the headline, the detail line and
// the raw warning), and never the last good reading.
describe("a reading that is missing or old", () => {
  const NOW = 100 * H;
  const MIN = 60_000;
  const limits = (retryAt: number | null): ProviderLimits[] => [{ provider: "claude", status: "unknown", windows: [], reason: "rate_limited", observedAt: NOW, retryAt }];
  const said = (m: MeterUsage, l: ProviderLimits[]) => Object.values(meterLines(m, l, NOW)).filter(Boolean).join("\n");
  const noReading = meter({
    percent: null, window: null, resetsAt: null, paceDelta: null, source: "none", reason: "rate_limited", updatedAt: null, projectedPercent: null,
    allowance: null, canStart: true, warning: "usage unknown (rate_limited): pacing off for claude (fail open)",
  });
  const kept = meter({
    percent: 42, resetsAt: null, reason: "rate_limited", updatedAt: NOW - 12 * MIN,
    warning: "usage reading 12 min old (rate_limited): pacing on the last good reading for claude",
  });

  it("says why there is no reading ONCE, with the next try and that pacing is off", () => {
    const lines = meterLines(noReading, limits(NOW + 4 * MIN), NOW);
    expect(lines.note).toBe("The provider asked us to slow down · next try in 4m · pacing is off meanwhile");
    expect(lines).toMatchObject({ headline: null, detail: null, warning: null });
    const text = said(noReading, limits(NOW + 4 * MIN));
    expect(text.match(/slow down/g)).toHaveLength(1);
    expect(text).not.toMatch(/rate_limited|fail open|usage unknown/);
  });

  it("keeps showing the last good reading with its age while the provider says slow down", () => {
    const lines = meterLines(kept, limits(NOW + 4 * MIN), NOW);
    expect(lines.detail).toBe("updated 12m ago");
    expect(lines.note).toBe("The provider asked us to slow down · showing the last good reading · next try in 4m");
    expect(lines.headline).toBe("projected 93% at reset");
    expect(lines.warning).toBeNull();
    expect(said(kept, limits(NOW + 4 * MIN)).match(/slow down/g)).toHaveLength(1);
    expect(said(kept, limits(NOW + 4 * MIN))).not.toMatch(/rate_limited/);
  });

  it("is not called live while it is old, and is called live when it is clean", () => {
    expect(meterLines({ ...kept, projectedPercent: null }, [], NOW).headline).toBeNull();
    const clean = meter({ updatedAt: NOW - 30_000, projectedPercent: null });
    expect(meterLines(clean, [], NOW)).toMatchObject({ headline: "live", note: null, warning: null });
    expect(meterLines(clean, [], NOW).detail).toMatch(/^Resets .* · updated just now$/);
    expect(meterLines(meter({ updatedAt: NOW }), [], NOW).headline).toBe("projected 93% at reset");
  });

  it("says a value typed by hand once", () => {
    const typed = meter({ source: "manual", window: "Manual entry", projectedPercent: null, paceDelta: null, updatedAt: null, resetsAt: null });
    expect(meterLines(typed, [], NOW)).toMatchObject({ headline: "typed by hand", detail: null, note: null });
    const down = meterLines({ ...typed, reason: "network" }, [], NOW);
    expect(down.note).toBe("Could not reach the provider · showing the typed value");
    expect(said({ ...typed, reason: "network" }, []).match(/typed by hand/g)).toHaveLength(1);
  });

  it("takes the next try from the daemon's limits, only while it is ahead", () => {
    expect(retryAtFor(limits(NOW + 4 * MIN), "claude", NOW)).toBe(NOW + 4 * MIN);
    expect(retryAtFor(limits(NOW - 1), "claude", NOW)).toBeNull();
    expect(retryAtFor(limits(null), "claude", NOW)).toBeNull();
    expect(retryAtFor(limits(NOW + 4 * MIN), "opencode-go", NOW)).toBeNull();
    expect(nextTryLine(NOW + 4 * MIN, NOW)).toBe("next try in 4m");
    expect(nextTryLine(NOW + 90 * MIN, NOW)).toBe("next try in 1h 30m");
    expect(nextTryLine(NOW + 20_000, NOW)).toBe("next try shortly");
    expect(nextTryLine(null, NOW)).toBeNull();
    expect(readingNote(noReading, null, NOW)).toBe("The provider asked us to slow down · pacing is off meanwhile");
  });

  it("names the other reasons plainly and keeps a warning nothing else explains", () => {
    const signedOut = meter({ percent: null, window: null, source: "none", reason: "no_credentials", projectedPercent: null, paceDelta: null, resetsAt: null, updatedAt: null, warning: null });
    expect(readingNote(signedOut, null, NOW)).toBe("Not signed in");
    expect(readingNote(meter({ percent: null, source: "none", reason: null, warning: null }), null, NOW)).toBe("No usage reading for this account");
    expect(readingNote(meter({ percent: null, source: "none", reason: null, warning: "usage unknown: pacing off for claude (fail open)" }), null, NOW)).toBe(
      "No usage reading for this account · pacing is off meanwhile",
    );
    expect(meterLines(meter({ warning: "something else" }), [], NOW).warning).toBe("something else");
  });
});
