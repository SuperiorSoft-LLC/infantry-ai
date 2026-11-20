// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import { describe, expect, it } from "vitest";
import {
  PACE_QUERY, createPaceStore, paceCaption, paceFmt, paceLabel, paceNearest, paceSigned, paceSpec, paceTicks, paceTooltip, paceTrendLabel,
  paceVisible, parsePaceReport, parsePaceScale, parseUsageFit, type Envelope, type PaceSeries,
} from "../src";

const H = 3_600_000;
const NOW = 1_000 * H;

function series(over: Partial<PaceSeries> = {}): PaceSeries {
  const points = Array.from({ length: 8 }, (_, i) => ({ t: NOW - (7 - i) * H, used: 50 + i, paceDelta: -2 + i * 0.5, projected: 100 + i, segment: 0, burn: i === 0 ? null : 1 }));
  return {
    provider: "claude", window: "Weekly limit", windowMinutes: 10_080, resetsAt: NOW + 84 * H, samples: 8, points, trend: "RISING",
    forecast: {
      ready: true, reason: null, sentence: "at the current rate you land at 107%", hoursLeft: 84,
      current: { label: "current rate", landing: 107, hoursToCross: 73 }, recent: { label: "last 6h rate", landing: 95.5, hoursToCross: null },
    },
    ...over,
  };
}

describe("readable labels", () => {
  it("never prints a bare points figure", () => {
    expect(paceLabel(-1)).toBe("on pace (−1)");
    expect(paceLabel(0)).toBe("on pace (0)");
    expect(paceLabel(4)).toBe("ahead of pace (+4)");
    expect(paceLabel(-12.4)).toBe("under pace (−12)");
    expect(paceTooltip("pace", NOW, -1)).toMatch(/^on pace \(−1\) at /);
    expect(paceTooltip("used", NOW, 3)).toMatch(/^3% used at /);
    expect(paceTooltip("burn", NOW, 0)).toMatch(/^idle, burning 0 points of percent an hour at /);
    expect(paceCaption(series())).toBe("57% of limit · on pace (+2) · lands at 107%");
    expect(paceSigned(-1)).toBe("−1");
    expect(paceSigned(2.5)).toBe("+2.5");
    expect(paceTrendLabel("FALLING")).toBe("▼ Falling");
  });
});

describe("chart model", () => {
  it("draws the used line with a dashed and a dotted forecast to the reset, and the 100% cap", () => {
    const sp = paceSpec(series(), "used", "7d", "fit", NOW);
    expect(sp.empty).toBe(false);
    expect(sp.t1).toBe(NOW + 84 * H);
    expect(sp.currentForecast?.pts[1]).toEqual([NOW + 84 * H, 107]);
    expect(sp.recentForecast?.pts[1]).toEqual([NOW + 84 * H, 95.5]);
    expect(sp.hasBand).toBe(true);
    expect(sp.capLine).toBe(100);
    expect(sp.yMax).toBeGreaterThanOrEqual(107);
  });

  it("pace delta forecast ends at landing - 100 and shows the even-pace line", () => {
    const sp = paceSpec(series(), "pace", "window", "fit", NOW);
    expect(sp.currentForecast?.pts[1][1]).toBe(7);
    expect(sp.zeroLine).toBe(true);
    expect(sp.capLine).toBeNull();
  });

  it("short ranges draw no forecast", () => {
    const sp = paceSpec(series(), "used", "6h", "fit", NOW);
    expect(sp.currentForecast).toBeNull();
    expect(sp.t1).toBe(NOW);
  });

  it("a fixed scale clips and says so in words", () => {
    const sp = paceSpec(series(), "used", "7d", 50, NOW);
    expect(sp.yMin).toBe(0);
    expect(sp.yMax).toBe(50);
    expect(sp.clipped).toBe(true);
    expect(sp.clipNote).toBe("over scale: line clipped at 50%");
  });

  it("under two samples the card says so instead of drawing", () => {
    const sp = paceSpec(series({ points: series().points.slice(0, 1) }), "used", "7d", "fit", NOW);
    expect(sp.empty).toBe(true);
    expect(sp.emptyText).toContain("1 of 2");
  });

  it("a reset breaks the line: one line per segment, and the Window range is the last segment", () => {
    const pts = series().points.map((p, i) => ({ ...p, segment: i < 4 ? 0 : 1 }));
    const s = series({ points: pts });
    expect(paceSpec(s, "used", "7d", "fit", NOW).actual).toHaveLength(2);
    expect(paceVisible(s, "window", NOW)).toHaveLength(4);
  });

  it("burn has no forecast and skips the first sample with no burn rate", () => {
    const sp = paceSpec(series(), "burn", "window", "fit", NOW);
    expect(sp.actual[0].pts).toHaveLength(7);
    expect(sp.currentForecast).toBeNull();
  });

  it("round ticks, custom scale parsing, nearest point", () => {
    expect(paceTicks(0, 100)).toEqual([0, 20, 40, 60, 80, 100]);
    expect(parsePaceScale("120%")).toBe(120);
    expect(parsePaceScale("0")).toBeNull();
    expect(parsePaceScale("abc")).toBeNull();
    expect(paceFmt(12.04)).toBe("12");
    expect(paceFmt(12.5)).toBe("12.5");
    const sp = paceSpec(series(), "used", "window", "fit", NOW);
    expect(paceNearest(sp, NOW + 5 * H)?.[0]).toBe(NOW);
  });
});

describe("parsing the daemon answers", () => {
  const report = {
    generatedAt: NOW, retentionDays: 60,
    series: [{
      provider: "claude", window: "Weekly limit", windowMinutes: 10_080, samples: 2, resetsAt: NOW + H,
      points: [{ t: 1, u: 10, d: -1, j: 90, s: 0, b: null }, { t: 2, u: 11, d: null, j: null, s: 1, b: 0 }],
      momentum: "FALLING",
      forecast: { ready: false, reason: "not enough data yet", sentence: "not enough data yet", hoursLeft: null, current: null, recent: null },
    }],
  };

  it("parses a pace_report and survives junk", () => {
    const r = parsePaceReport(JSON.stringify(report))!;
    expect(r.series[0].points[1]).toEqual({ t: 2, used: 11, paceDelta: null, projected: null, segment: 1, burn: 0 });
    expect(r.series[0].trend).toBe("FALLING");
    expect(parsePaceReport("not json")).toBeNull();
    expect(parsePaceReport(JSON.stringify({ nope: 1 }))).toBeNull();
  });

  it("parses a usage_fit with and without a fit", () => {
    const f = parseUsageFit(JSON.stringify({ generatedAt: 1, provider: "claude", windows: [{ kind: "weekly", label: "Weekly limit", steps: 2, minSteps: 8, enough: false, pointsTotal: 3, cacheReadPerPoint: 1000, requestsPerPoint: null, perPoint: { cacheRead: { mean: 1, sd: 0, median: 1 }, output: null }, groups: [], series: [], fit: null }] }))!;
    expect(f.windows[0].enough).toBe(false);
    expect(f.windows[0].fit).toBeNull();
    expect(f.windows[0].perPoint.cacheRead?.mean).toBe(1);
    expect(f.windows[0].perPoint.output).toBeNull();
  });
});

describe("pace store", () => {
  function link() {
    const sent: Envelope[] = [];
    const taps = new Set<(e: Envelope) => void>();
    return { sent, sendFrame: (e: Envelope) => (sent.push(e), true), onFrame: (l: (e: Envelope) => void) => (taps.add(l), () => void taps.delete(l)), push: (e: Envelope) => taps.forEach((t) => t(e)) };
  }

  it("asks with the 8-day query, keeps the report and the fit per provider, and keeps a refusal", () => {
    const l = link();
    const s = createPaceStore(l);
    s.getState().refresh();
    expect(l.sent[0]).toMatchObject({ type: "pace_get", data: PACE_QUERY });
    expect(s.getState().loading).toBe(true);
    l.push({ type: "pace_report", data: JSON.stringify({ generatedAt: 5, retentionDays: 60, series: [] }), requestId: l.sent[0].requestId });
    expect(s.getState().report?.generatedAt).toBe(5);
    expect(s.getState().loading).toBe(false);
    s.getState().loadFit("claude");
    expect(l.sent[1]).toMatchObject({ type: "usage_fit_get", data: JSON.stringify({ provider: "claude" }) });
    l.push({ type: "usage_fit", data: JSON.stringify({ generatedAt: 5, provider: "claude", windows: [] }) });
    expect(s.getState().fit.claude.windows).toEqual([]);
    s.getState().refresh();
    l.push({ type: "error", data: "pace_get failed", requestId: l.sent[2].requestId });
    expect(s.getState().refusal).toBe("pace_get failed");
  });
});
