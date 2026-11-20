// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The Pace store: the daemon's `pace_report` (per provider window: the history of percent used, pace delta, burn rate,
// the forecast to reset and the momentum) and `usage_fit` (what drives the meter), parsed, and the chart model behind
// the Pace tab of the Stats modal. A port of the Kotlin PaceChartModel: the same ranges, scales, ticks, clipping and
// forecast lines, with readable words ("3% used, on pace (−1)") where Kotlin printed a bare "-1 pts". The link is
// injected, so the store is tested with recorded frames and no socket.
import { createStore, type StoreApi } from "zustand/vanilla";
import { json, type Envelope } from "../wire";
import type { UsageLink } from "./usage";

export type PaceTrend = "RISING" | "FLAT" | "FALLING" | "UNKNOWN";

export interface PacePoint {
  t: number;
  used: number;
  paceDelta: number | null;
  projected: number | null;
  segment: number;
  burn: number | null;
}

export interface PaceRate {
  label: string;
  landing: number;
  hoursToCross: number | null;
}

export interface PaceForecast {
  ready: boolean;
  reason: string | null;
  sentence: string;
  hoursLeft: number | null;
  current: PaceRate | null;
  recent: PaceRate | null;
}

export interface PaceSeries {
  provider: string;
  window: string;
  windowMinutes: number;
  resetsAt: number | null;
  samples: number;
  points: PacePoint[];
  trend: PaceTrend;
  forecast: PaceForecast;
}

export interface PaceReportView {
  generatedAt: number;
  retentionDays: number;
  series: PaceSeries[];
}

type Obj = Record<string, unknown>;
const num = (v: unknown): number | null => (typeof v === "number" && Number.isFinite(v) ? v : null);
const str = (v: unknown): string | null => (typeof v === "string" ? v : null);
const isObj = (v: unknown): v is Obj => typeof v === "object" && v !== null && !Array.isArray(v);

function rate(v: unknown): PaceRate | null {
  if (!isObj(v)) return null;
  return { label: str(v.label) ?? "", landing: num(v.landingPercent) ?? 0, hoursToCross: num(v.hoursToCross) };
}

function forecast(v: unknown): PaceForecast {
  if (!isObj(v)) return { ready: false, reason: "no forecast", sentence: "not enough data yet", hoursLeft: null, current: null, recent: null };
  return {
    ready: v.ready === true,
    reason: str(v.reason),
    sentence: str(v.sentence) ?? "",
    hoursLeft: num(v.hoursLeft),
    current: rate(v.current),
    recent: rate(v.recent),
  };
}

function trendOf(v: unknown): PaceTrend {
  return v === "RISING" || v === "FLAT" || v === "FALLING" ? v : "UNKNOWN";
}

/** Null = not a pace report (an older daemon, a torn frame). */
export function parsePaceReport(text: string | null | undefined): PaceReportView | null {
  const o = json<Obj>(text ?? "");
  if (!o || !Array.isArray(o.series)) return null;
  return {
    generatedAt: num(o.generatedAt) ?? 0,
    retentionDays: num(o.retentionDays) ?? 60,
    series: o.series.filter(isObj).map((s) => ({
      provider: str(s.provider) ?? "",
      window: str(s.window) ?? "",
      windowMinutes: num(s.windowMinutes) ?? 0,
      resetsAt: num(s.resetsAt),
      samples: num(s.samples) ?? 0,
      points: (Array.isArray(s.points) ? s.points : []).filter(isObj).map((p) => ({
        t: num(p.t) ?? 0,
        used: num(p.u) ?? 0,
        paceDelta: num(p.d),
        projected: num(p.j),
        segment: num(p.s) ?? 0,
        burn: num(p.b),
      })),
      trend: trendOf(s.momentum),
      forecast: forecast(s.forecast),
    })),
  };
}

// ---- the usage-fit answer (what drives the plan meter) ----

export interface UsageFitSpread {
  mean: number;
  sd: number;
  median: number;
}

export interface UsageFitWindow {
  kind: string;
  label: string;
  steps: number;
  minSteps: number;
  enough: boolean;
  pointsTotal: number;
  cacheReadPerPoint: number | null;
  requestsPerPoint: number | null;
  perPoint: Record<string, UsageFitSpread | null>;
  groups: { role: string; model: string; requests: number; cacheRead: number; share: number; totalRequests: number; totalCacheRead: number }[];
  fit: {
    n: number;
    r2: number;
    residualSd: number;
    best: { key: string; label: string };
    backgroundPerHour: number | null;
    backgroundShare: number | null;
    candidates: { key: string; label: string; columns: number; r2: number; adjR2: number }[];
    tokensPerPoint: { name: string; value: number | null }[];
  } | null;
}

export interface UsageFitView {
  generatedAt: number;
  provider: string;
  windows: UsageFitWindow[];
}

function spread(v: unknown): UsageFitSpread | null {
  return isObj(v) ? { mean: num(v.mean) ?? 0, sd: num(v.sd) ?? 0, median: num(v.median) ?? 0 } : null;
}

export function parseUsageFit(text: string | null | undefined): UsageFitView | null {
  const o = json<Obj>(text ?? "");
  if (!o || !Array.isArray(o.windows)) return null;
  return {
    generatedAt: num(o.generatedAt) ?? 0,
    provider: str(o.provider) ?? "",
    windows: o.windows.filter(isObj).map((w) => {
      const f = isObj(w.fit) ? w.fit : null;
      const pp = isObj(w.perPoint) ? w.perPoint : {};
      return {
        kind: str(w.kind) ?? "",
        label: str(w.label) ?? "",
        steps: num(w.steps) ?? 0,
        minSteps: num(w.minSteps) ?? 0,
        enough: w.enough === true,
        pointsTotal: num(w.pointsTotal) ?? 0,
        cacheReadPerPoint: num(w.cacheReadPerPoint),
        requestsPerPoint: num(w.requestsPerPoint),
        perPoint: Object.fromEntries(Object.entries(pp).map(([k, v]) => [k, spread(v)])),
        groups: (Array.isArray(w.groups) ? w.groups : []).filter(isObj).map((g) => ({
          role: str(g.role) ?? "",
          model: str(g.model) ?? "",
          requests: num(g.requests) ?? 0,
          cacheRead: num(g.cacheRead) ?? 0,
          share: num(g.share) ?? 0,
          totalRequests: num(g.totalRequests) ?? 0,
          totalCacheRead: num(g.totalCacheRead) ?? 0,
        })),
        fit: f
          ? {
              n: num(f.n) ?? 0,
              r2: num(f.r2) ?? 0,
              residualSd: num(f.residualSd) ?? 0,
              best: { key: str((f.best as Obj | undefined)?.key) ?? "", label: str((f.best as Obj | undefined)?.label) ?? "" },
              backgroundPerHour: num(f.backgroundPerHour),
              backgroundShare: num(f.backgroundShare),
              candidates: (Array.isArray(f.candidates) ? f.candidates : []).filter(isObj).map((c) => ({
                key: str(c.key) ?? "",
                label: str(c.label) ?? "",
                columns: num(c.columns) ?? 0,
                r2: num(c.r2) ?? 0,
                adjR2: num(c.adjR2) ?? 0,
              })),
              tokensPerPoint: (Array.isArray(f.tokensPerPoint) ? f.tokensPerPoint : []).filter(isObj).map((t) => ({ name: str(t.name) ?? "", value: num(t.value) })),
            }
          : null,
      };
    }),
  };
}

// ---- the chart model (no DOM) ----

export const PACE_TREND_WORD: Record<PaceTrend, string> = { RISING: "Rising", FLAT: "Flat", FALLING: "Falling", UNKNOWN: "Not enough data" };
const TREND_ARROW: Record<PaceTrend, string> = { RISING: "▲", FLAT: "▬", FALLING: "▼", UNKNOWN: "•" };

/** Arrow plus word, never colour alone. */
export function paceTrendLabel(t: PaceTrend): string {
  return `${TREND_ARROW[t]} ${PACE_TREND_WORD[t]}`;
}

export type PaceRange = "6h" | "24h" | "7d" | "window";
export const PACE_RANGES: { id: PaceRange; label: string; hours: number | null }[] = [
  { id: "6h", label: "6h", hours: 6 },
  { id: "24h", label: "24h", hours: 24 },
  { id: "7d", label: "7d", hours: 168 },
  { id: "window", label: "Window", hours: null },
];
/** The forecast runs to the reset time only where the range is wide enough to show it. */
export const paceShowsForecast = (r: PaceRange): boolean => r === "7d" || r === "window";

export type PaceMetric = "pace" | "used" | "burn";
export const PACE_METRIC_LABEL: Record<PaceMetric, string> = { pace: "Pace delta", used: "Used", burn: "Burn rate" };
export const PACE_METRIC_TOGGLE: PaceMetric[] = ["pace", "used"];

/** "fit" or a typed maximum (pace delta: -max to +max). */
export type PaceScale = "fit" | number;
export const paceScalePresets = (m: PaceMetric): number[] => (m === "used" ? [50, 100, 150] : [10, 25, 50]);

/** Parses a typed maximum ("120", "120%"); null = refused. */
export function parsePaceScale(text: string): number | null {
  const n = Number(text.trim().replace(/%$/, "").trim());
  return Number.isFinite(n) && n >= 1 && n <= 1000 ? n : null;
}

export interface PaceLine {
  pts: [number, number][];
}

export interface PaceChartSpec {
  empty: boolean;
  emptyText: string | null;
  yMin: number;
  yMax: number;
  ticks: number[];
  t0: number;
  t1: number;
  actual: PaceLine[];
  /** Dashed: the current-rate forecast to reset; dotted: the last-lookback-rate forecast. */
  currentForecast: PaceLine | null;
  recentForecast: PaceLine | null;
  hasBand: boolean;
  zeroLine: boolean;
  capLine: number | null;
  clipped: boolean;
  clipNote: string | null;
  yUnit: string;
}

export const PACE_MIN_SAMPLES = 2;

const valueOf = (p: PacePoint, m: PaceMetric): number | null => (m === "used" ? p.used : m === "pace" ? p.paceDelta : p.burn);

/** The points of [s] a range shows, as of [now]. "window" = the last segment. */
export function paceVisible(s: PaceSeries, range: PaceRange, now: number): PacePoint[] {
  const hours = PACE_RANGES.find((r) => r.id === range)?.hours ?? null;
  if (hours === null) {
    const last = s.points.at(-1)?.segment;
    return last === undefined ? [] : s.points.filter((p) => p.segment === last);
  }
  const from = now - hours * 3_600_000;
  return s.points.filter((p) => p.t >= from);
}

/** 4-6 round tick values covering lo..hi. */
export function paceTicks(lo: number, hi: number, target = 6): number[] {
  const span = Math.max(hi - lo, 1e-6);
  const raw = span / target;
  const mag = 10 ** Math.floor(Math.log10(raw));
  const step = [1, 2, 2.5, 5, 10].map((x) => x * mag).find((x) => x >= raw - 1e-12) as number;
  const out: number[] = [];
  let v = Math.ceil(lo / step - 1e-9) * step;
  while (v <= hi + 1e-9 && out.length < 12) {
    out.push(Math.round(v / step) * step + 0);
    v += step;
  }
  return out.length ? out : [lo, hi];
}

export function paceFmt(v: number): string {
  return Math.abs(v - Math.round(v)) < 0.05 ? String(Math.round(v)) : v.toFixed(1);
}

const MINUS = "−";
/** "+4", "−1", "0": a real minus sign. */
export function paceSigned(v: number): string {
  const r = Math.round(v * 10) / 10;
  if (Math.abs(r - Math.round(r)) < 0.05) {
    const i = Math.round(r);
    return i > 0 ? `+${i}` : i < 0 ? `${MINUS}${-i}` : "0";
  }
  return r > 0 ? `+${r.toFixed(1)}` : `${MINUS}${(-r).toFixed(1)}`;
}

/** A pace delta within this many points of even still reads "on pace" (the Rust daemon's ON_PACE_POINTS). */
export const ON_PACE_POINTS = 2;

/** The readable pace words, never a bare "-1 pts": "on pace (−1)", "ahead of pace (+4)", "under pace (−12)". */
export function paceLabel(delta: number): string {
  const pts = Math.floor(delta + 0.5);
  const figure = pts > 0 ? `+${pts}` : pts < 0 ? `${MINUS}${-pts}` : "0";
  const words = Math.abs(pts) <= ON_PACE_POINTS ? "on pace" : pts > 0 ? "ahead of pace" : "under pace";
  return `${words} (${figure})`;
}

export function paceSpec(s: PaceSeries, metric: PaceMetric, range: PaceRange, scale: PaceScale, now: number): PaceChartSpec {
  const shown = paceVisible(s, range, now).filter((p) => valueOf(p, metric) !== null);
  const unit = metric === "used" ? "%" : metric === "pace" ? " pts" : " pts/h";
  if (shown.length < PACE_MIN_SAMPLES) {
    return {
      empty: true,
      emptyText: `Not enough samples in this range yet (${shown.length} of ${PACE_MIN_SAMPLES}). The daemon records a reading about every minute while it runs.`,
      yMin: 0, yMax: 1, ticks: [], t0: 0, t1: 1, actual: [], currentForecast: null, recentForecast: null,
      hasBand: false, zeroLine: false, capLine: null, clipped: false, clipNote: null, yUnit: unit,
    };
  }
  const bySegment = new Map<number, [number, number][]>();
  for (const p of shown) {
    const l = bySegment.get(p.segment) ?? [];
    l.push([p.t, valueOf(p, metric) as number]);
    bySegment.set(p.segment, l);
  }
  const actual: PaceLine[] = [...bySegment.values()].map((pts) => ({ pts }));
  const last = shown[shown.length - 1];
  const lastV = valueOf(last, metric) as number;
  const resets = s.resetsAt;
  const f = s.forecast;
  let cur: PaceLine | null = null;
  let rec: PaceLine | null = null;
  let t1 = last.t;
  if (metric !== "burn" && paceShowsForecast(range) && f.ready && resets !== null && resets > last.t) {
    // Pace delta at the reset = landing - 100: at the reset an even pace is 100% used.
    const end = (r: PaceRate | null): number | null => (r ? (metric === "used" ? r.landing : r.landing - 100) : null);
    const c = end(f.current);
    const r = end(f.recent);
    cur = c === null ? null : { pts: [[last.t, lastV], [resets, c]] };
    rec = r === null ? null : { pts: [[last.t, lastV], [resets, r]] };
    if (cur || rec) t1 = resets;
  }
  const t0 = shown[0].t;
  const all = shown.map((p) => valueOf(p, metric) as number).concat(cur ? [cur.pts[1][1]] : [], rec ? [rec.pts[1][1]] : []);
  const dataMin = Math.min(...all);
  const dataMax = Math.max(...all);
  let yMin: number;
  let yMax: number;
  if (typeof scale === "number") {
    yMin = metric === "used" ? 0 : -scale;
    yMax = scale;
  } else {
    let lo = dataMin;
    let hi = dataMax;
    if (metric !== "used") {
      lo = Math.min(lo, 0);
      hi = Math.max(hi, 0);
    } else if (hi >= 70) hi = Math.max(hi, 100);
    const span = Math.max(hi - lo, metric === "used" ? 4 : metric === "pace" ? 2 : 0.5);
    const pad = span * 0.08;
    lo -= pad;
    hi = lo + span + 2 * pad;
    if (metric === "used") lo = Math.max(lo, 0);
    yMin = lo;
    yMax = hi;
  }
  const ticks = paceTicks(yMin, yMax);
  // Under Fit the axis is the ticks' hull so labelled gridlines reach the plot edges.
  const fy0 = scale === "fit" ? Math.min(yMin, ticks[0]) : yMin;
  const fy1 = scale === "fit" ? Math.max(yMax, ticks[ticks.length - 1]) : yMax;
  const clipped = all.some((v) => v > fy1 + 1e-9 || v < fy0 - 1e-9);
  const over = Math.max(...all) > fy1 + 1e-9;
  const clipNote = clipped ? `over scale: line clipped at ${paceFmt(over ? fy1 : fy0)}${unit}` : null;
  const cap = metric === "used" && fy0 < 100 && 100 < fy1 + 1e-9 ? 100 : null;
  return {
    empty: false, emptyText: null, yMin: fy0, yMax: fy1, ticks: ticks.filter((v) => v >= fy0 - 1e-9 && v <= fy1 + 1e-9), t0, t1,
    actual, currentForecast: cur, recentForecast: rec, hasBand: cur !== null && rec !== null,
    zeroLine: metric !== "used" && fy0 < 0 && fy1 > 0, capLine: cap, clipped, clipNote, yUnit: unit,
  };
}

/** Position of [v] on the axis, 0..1 (clipped). */
export const paceY = (sp: PaceChartSpec, v: number): number => Math.min(1, Math.max(0, (v - sp.yMin) / (sp.yMax - sp.yMin)));

/** The point of the paceSpec's actual lines closest in time to [t] (hover / tap). */
export function paceNearest(sp: PaceChartSpec, t: number): [number, number] | null {
  let best: [number, number] | null = null;
  for (const l of sp.actual) for (const p of l.pts) if (!best || Math.abs(p[0] - t) < Math.abs(best[0] - t)) best = p;
  return best;
}

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
const pad2 = (n: number): string => String(n).padStart(2, "0");

/** Axis / paceTooltip clock text: `14:05`, or `May 4 14:05` with the date. */
export function paceTime(t: number, withDate: boolean): string {
  const d = new Date(t);
  const hm = `${pad2(d.getHours())}:${pad2(d.getMinutes())}`;
  return withDate ? `${MONTHS[d.getMonth()]} ${d.getDate()} ${hm}` : hm;
}

/** The exact-value paceTooltip of one point, in words. */
export function paceTooltip(metric: PaceMetric, t: number, v: number): string {
  const what =
    metric === "used" ? `${paceFmt(v)}% used` : metric === "pace" ? paceLabel(v) : v === 0 ? "idle, burning 0 points of percent an hour" : `burning ${paceFmt(v)} points of percent an hour`;
  return `${what} at ${paceTime(t, true)}`;
}

/** The card's header numbers in words: "3% of limit · on pace (−1) · lands at 107%". */
export function paceCaption(s: PaceSeries): string {
  const l = s.points.at(-1);
  if (!l) return "no reading yet";
  const parts = [`${paceFmt(l.used)}% of limit`];
  if (l.paceDelta !== null) parts.push(paceLabel(l.paceDelta));
  if (l.projected !== null) parts.push(`lands at ${paceFmt(l.projected)}%`);
  return parts.join(" · ");
}

/** The query the tab asks with: the last 8 days (the longest range plus a window), thinned to 600 points per series. */
export const PACE_QUERY = JSON.stringify({ sinceHours: 192, maxPoints: 600 });

// ---- the store ----

export interface PaceState {
  report: PaceReportView | null;
  fit: Record<string, UsageFitView>;
  /** The last refusal (the daemon answered `error` to a pace or fit request). */
  refusal: string | null;
  loading: boolean;
}

export interface PaceActions {
  refresh(): void;
  loadFit(provider: string): void;
  dispose(): void;
}

export type PaceStore = StoreApi<PaceState & PaceActions>;

export function createPaceStore(link: UsageLink): PaceStore {
  const pending = new Map<string, string>();
  let counter = 0;
  return createStore<PaceState & PaceActions>()((set) => {
    const send = (type: string, data: string, tag: string): void => {
      counter += 1;
      const requestId = `pace:${counter.toString(36)}`;
      pending.set(requestId, tag);
      if (!link.sendFrame({ type, data, requestId })) {
        pending.delete(requestId);
        set({ refusal: `${type} not sent: the daemon link is down`, loading: false });
      }
    };
    const off = link.onFrame((env: Envelope) => {
      if (env.type === "pace_report") {
        const r = parsePaceReport(env.data);
        if (env.requestId) pending.delete(env.requestId);
        if (r) set({ report: r, loading: false, refusal: null });
        else set({ loading: false, refusal: "The daemon answered with a pace report this app cannot read." });
      } else if (env.type === "usage_fit") {
        const f = parseUsageFit(env.data);
        if (env.requestId) pending.delete(env.requestId);
        if (f) set((s) => ({ fit: { ...s.fit, [f.provider]: f } }));
      } else if (env.type === "error" && env.requestId && pending.has(env.requestId)) {
        pending.delete(env.requestId);
        set({ refusal: env.data, loading: false });
      }
    });
    return {
      report: null,
      fit: {},
      refusal: null,
      loading: false,
      refresh() {
        set({ loading: true });
        send("pace_get", PACE_QUERY, "pace");
      },
      loadFit(provider) {
        send("usage_fit_get", JSON.stringify({ provider }), "fit");
      },
      dispose() {
        off();
      },
    };
  });
}
