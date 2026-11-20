// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The Stats derivations against the Kotlin plugin's own test fixtures (UsageMeterViewTest, UsageRequestViewTest,
// StatsView): the same inputs must print the same numbers in both apps.
import { describe, expect, it } from "vitest";
import {
  avgContextText, cacheHitText, compareHarnesses, costTable, createStatsMeterStore, deltaText, gaugeOver, gaugePercent, meterPeriods, meterSplit,
  parseTokens, periodKey, resolveAxis, statsCompact, statsCost, statsDuration, totalRows, type MeterRequest, type MeterRow, type UsageMeterReport, watchLine,
} from "../src/index";

const row = (day: string, source: string, input: number, cacheRead: number, turns = 2): MeterRow => ({
  day, role: "pm", provider: "claude", model: "m", source, runs: 1, turns, input, output: 10, cacheRead, cacheWrite: 0, contextSum: input + cacheRead, cost: 1,
});

describe("meter periods", () => {
  it("weeks start on Monday", () => {
    expect(periodKey("2026-10-28", true)).toBe("2026-10-26");
    expect(periodKey("2026-11-01", true)).toBe("2026-10-26");
    expect(periodKey("2026-11-02", true)).toBe("2026-11-02");
  });
  it("newest first, split by source, cache hit and average context", () => {
    const rows = [row("2026-10-27", "pm", 100, 900), row("2026-10-28", "worker", 100, 300), row("2026-10-28", "pm", 100, 100)];
    const days = meterPeriods(rows, false);
    expect(days.map((d) => d[0])).toEqual(["2026-10-28", "2026-10-27"]);
    const t = totalRows(days[0]![1]);
    expect(t.tokens).toBe(200 + 20 + 400);
    expect(cacheHitText(t)).toBe("66.7%");
    expect(avgContextText(t)).toBe("150");
    expect(meterSplit(days[0]![1], "source").map((s) => s[0])).toEqual(["worker", "pm"]);
    expect(deltaText(100, 100)).toBe("0%");
    expect(deltaText(150, 100)).toBe("+50%");
    expect(deltaText(1, null)).toBe("-");
    expect(meterPeriods(rows, true)).toHaveLength(1);
  });
});

describe("harness comparison and cost", () => {
  const req = (over: Partial<MeterRequest>): MeterRequest => ({ ts: 1, sessionId: "s1", role: "worker", harness: "claude", source: "worker", ...over });
  it("an empty report has no lines and n/a is never 0", () => {
    const empty: UsageMeterReport = { rows: [], keepWeeks: 4, generatedAt: 0, requests: [], events: [], prices: [] };
    expect(compareHarnesses(empty)).toEqual([]);
    const r = { ...empty, requests: [req({ ticketId: "t-123456789", model: "opus", input: 1_000_000 }), req({ model: "opus" })], prices: [{ match: "opus", input: 3, output: 15, cacheRead: 0.3, cacheWrite: 3.75 }] };
    const rows = Object.fromEntries(costTable(r, "ticket", { tickets: {}, sessions: {} }).rows.map((x) => [x.key, x.cost]));
    expect(rows["t-123456"]).toBe(3);
    expect(rows["(no ticket)"]).toBeNull();
  });
});

describe("session numbers", () => {
  it("formats like StatsView", () => {
    expect(statsDuration(4 * 60_000 + 12_000)).toBe("4m 12s");
    expect(statsDuration(null)).toBe("-");
    expect(statsCompact(950)).toBe("950");
    expect(statsCompact(3300)).toBe("3.3K");
    expect(statsCompact(1_200_000)).toBe("1.2M");
    expect(statsCost(0.5)).toBe("$0.5000");
    expect(statsCost(null)).toBe("cost not reported");
  });
  it("the gauge is null without a limit and red past it", () => {
    expect(gaugePercent({ lastContextTokens: 100, modelContextLimit: null })).toBeNull();
    expect(gaugePercent({ lastContextTokens: 100_000, modelContextLimit: 200_000 })).toBe(50);
    expect(gaugeOver({ lastContextTokens: 250_000, modelContextLimit: 200_000 })).toBe(true);
  });
  it("chart scale resolves like ChartScale", () => {
    expect(resolveAxis("context", { kind: "default" }, 300_000, null)).toEqual({ max: 200_000, overScale: true });
    expect(resolveAxis("context", { kind: "fit" }, 300_000, null)).toEqual({ max: 300_000, overScale: false });
    expect(resolveAxis("context", { kind: "limit" }, 10, 1_000_000).max).toBe(1_000_000);
    expect(parseTokens("300k")).toBe(300_000);
    expect(parseTokens("1.5m")).toBe(1_500_000);
    expect(parseTokens("abc")).toBeNull();
  });
});

describe("the meter store", () => {
  it("asks on ready and keeps the pushed report", () => {
    const sent: string[] = [];
    let listener: (e: { type: string; data: string }) => void = () => {};
    const store = createStatsMeterStore({ sendFrame: (e) => (sent.push(e.type), true), onFrame: (l) => ((listener = l), () => {}) });
    listener({ type: "ready", data: "" });
    expect(sent).toEqual(["usage_meter_get"]);
    listener({ type: "usage_meter", data: JSON.stringify({ rows: [], keepWeeks: 4, generatedAt: 1, requests: [], events: [], prices: [] }) });
    expect(store.getState().report?.keepWeeks).toBe(4);
  });
});

describe("job watcher line", () => {
  it("reads like the Kotlin STALL-2 line", () => {
    expect(watchLine({ events: 3, heartbeats: 2, optOuts: 1, stallNudges: 4, hookRewrites: 5, hookOverrides: 0, note: "watcher, heartbeat and stall detector use no model tokens" })).toBe(
      "Job watcher: 3 events, 2 heartbeats, 5 commands routed by hooks (0 overridden by another hook), 1 opt-outs, 4 stall nudges (watcher, heartbeat and stall detector use no model tokens)",
    );
  });
});
