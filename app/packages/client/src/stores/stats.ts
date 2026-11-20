// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Stats: the daemon's `usage_meter` report (tokens by day/week, harness comparison, estimated cost, context per
// session) and the pure derivations the Stats modal prints. Ported 1:1 from the Kotlin Stats plugin
// (UsageMeterView, UsageRequestView, StatsView, ChartScale) so both apps say the same numbers.
import { createStore, type StoreApi } from "zustand/vanilla";
import { json } from "../wire";
import type { FrameLink } from "./link";

export interface MeterRow {
  day: string;
  role: string;
  provider: string;
  model: string;
  source: string;
  runs: number;
  turns: number;
  input: number;
  output: number;
  cacheRead: number;
  cacheWrite: number;
  contextSum: number;
  cost?: number | null;
}
export interface MeterRequest {
  ts: number;
  sessionId: string;
  ticketId?: string | null;
  role: string;
  harness: string;
  model?: string | null;
  source: string;
  input?: number | null;
  output?: number | null;
  cacheRead?: number | null;
  cacheWrite?: number | null;
  context?: number | null;
  cost?: number | null;
}
export interface MeterEvent {
  ts: number;
  sessionId: string;
  ticketId?: string | null;
  harness: string;
  kind: string;
  trigger?: string | null;
  tokensBefore?: number | null;
}
export interface MeterPrice {
  match: string;
  input: number;
  output: number;
  cacheRead: number;
  cacheWrite: number;
}
export interface UsageMeterReport {
  rows: MeterRow[];
  keepWeeks: number;
  generatedAt: number;
  requests: MeterRequest[];
  events: MeterEvent[];
  prices: MeterPrice[];
}

export interface StatsMeterState {
  report: UsageMeterReport | null;
  refusal: string | null;
}
export interface StatsMeterActions {
  refresh(): void;
  dispose(): void;
}
export type StatsMeterStore = StoreApi<StatsMeterState & StatsMeterActions>;

/** The meter store: asks `usage_meter_get` on demand and on `ready`; the daemon pushes a fresh `usage_meter` after a turn. */
export function createStatsMeterStore(link: FrameLink): StatsMeterStore {
  return createStore<StatsMeterState & StatsMeterActions>()((set) => {
    const ask = (): void => {
      if (!link.sendFrame({ type: "usage_meter_get", data: "" })) set({ refusal: "usage_meter_get not sent: the daemon link is down" });
    };
    const off = link.onFrame((env) => {
      if (env.type === "ready") ask();
      else if (env.type === "usage_meter") {
        const r = json<UsageMeterReport>(env.data);
        if (r && Array.isArray(r.rows)) set({ report: r, refusal: null });
      } else if (env.type === "error" && env.data.startsWith("usage_meter_get refused")) set({ refusal: env.data });
    });
    return { report: null, refusal: null, refresh: ask, dispose: off };
  });
}

// ---------------------------------------------------------------- formatting (StatsView / UsageMeterView)

export const NONE = "-";

/** "1d 12h", "3h 5m", "4m 12s", "42s"; "-" for null. */
export function statsDuration(ms: number | null | undefined): string {
  if (ms == null || ms < 0) return NONE;
  const s = Math.floor(ms / 1000);
  const d = Math.floor(s / 86400), h = Math.floor((s % 86400) / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60;
  return d > 0 ? `${d}d ${h}h` : h > 0 ? `${h}h ${m}m` : m > 0 ? `${m}m ${sec}s` : `${sec}s`;
}

/** 950, 3.3K, 593.4K, 1.2M, 6.6B. */
export function statsCompact(n: number | null | undefined): string {
  if (n == null) return NONE;
  const one = (v: number, unit: string): string => {
    const t = Math.round(v * 10);
    return (t % 10 === 0 ? `${t / 10}` : `${Math.trunc(t / 10)}.${Math.abs(t % 10)}`) + unit;
  };
  const a = Math.abs(n);
  return a >= 1e9 ? one(n / 1e9, "B") : a >= 1e6 ? one(n / 1e6, "M") : a >= 1e3 ? one(n / 1e3, "K") : String(n);
}

/** 234,310 grouping. */
export const statsGrouped = (n: number | null | undefined): string => (n == null ? NONE : n.toLocaleString("en-US"));

/** "$0.1234" (four decimals), the harness-reported cost. */
export const statsCost = (v: number | null | undefined): string => (v == null ? "cost not reported" : `$${(Math.round(v * 10_000) / 10_000).toFixed(4)}`);

export interface GaugeInput {
  lastContextTokens?: number | null;
  modelContextLimit?: number | null;
}
export function gaugePercent(s: GaugeInput): number | null {
  const last = s.lastContextTokens, limit = s.modelContextLimit;
  return last == null || limit == null || limit <= 0 ? null : Math.round((last * 100) / limit);
}
export const gaugeOver = (s: GaugeInput): boolean => s.lastContextTokens != null && s.modelContextLimit != null && s.modelContextLimit > 0 && s.lastContextTokens > s.modelContextLimit;

// ---------------------------------------------------------------- chart scale (ChartScale)

export type ChartKind = "context" | "billed";
export const CHART_KINDS: Record<ChartKind, { defaultMax: number; presets: number[] }> = {
  context: { defaultMax: 200_000, presets: [128_000, 200_000, 500_000, 1_000_000] },
  billed: { defaultMax: 2_000_000, presets: [500_000, 1_000_000, 2_000_000] },
};
export type ChartScaleSetting = { kind: "default" } | { kind: "fixed"; tokens: number } | { kind: "limit" } | { kind: "fit" };

export function resolveAxis(kind: ChartKind, setting: ChartScaleSetting, seriesMax: number, modelLimit: number | null | undefined): { max: number; overScale: boolean } {
  const peak = Math.max(0, seriesMax);
  const d = CHART_KINDS[kind].defaultMax;
  const max = setting.kind === "fixed" ? setting.tokens : setting.kind === "limit" ? (modelLimit && modelLimit > 0 ? modelLimit : d) : setting.kind === "fit" ? Math.max(peak, 1) : d;
  return { max, overScale: peak > max };
}
/** "150k", "1.5m", "200000" -> tokens; null when not a positive number. */
export function parseTokens(text: string): number | null {
  const t = text.trim().toLowerCase().replace(/[,_]/g, "");
  if (!t) return null;
  const last = t[t.length - 1];
  const mult = last === "k" ? 1e3 : last === "m" ? 1e6 : 1;
  const num = Number(mult === 1 ? t : t.slice(0, -1));
  if (!Number.isFinite(num)) return null;
  const v = Math.round(num * mult);
  return v > 0 ? v : null;
}

// ---------------------------------------------------------------- periods (UsageMeterView)

export interface MeterAgg {
  runs: number;
  turns: number;
  input: number;
  output: number;
  cacheRead: number;
  cacheWrite: number;
  contextSum: number;
  cost: number | null;
  tokens: number;
  context: number;
}
export function totalRows(rows: MeterRow[]): MeterAgg {
  const a = { runs: 0, turns: 0, input: 0, output: 0, cacheRead: 0, cacheWrite: 0, contextSum: 0, cost: null as number | null };
  for (const r of rows) {
    a.runs += r.runs; a.turns += r.turns; a.input += r.input; a.output += r.output;
    a.cacheRead += r.cacheRead; a.cacheWrite += r.cacheWrite; a.contextSum += r.contextSum;
    if (r.cost != null) a.cost = (a.cost ?? 0) + r.cost;
  }
  return { ...a, tokens: a.input + a.output + a.cacheRead + a.cacheWrite, context: a.input + a.cacheRead + a.cacheWrite };
}
const epochDay = (day: string): number => Math.floor(Date.UTC(+day.slice(0, 4), +day.slice(5, 7) - 1, +day.slice(8, 10)) / 86_400_000);
const dayString = (e: number): string => new Date(e * 86_400_000).toISOString().slice(0, 10);
/** The day, or for a week the Monday that starts it (UTC like the daemon). */
export function periodKey(day: string, weekly: boolean): string {
  if (!weekly) return day;
  const e = epochDay(day);
  return dayString(e - ((((e + 3) % 7) + 7) % 7));
}
/** Rows grouped per period, newest first. */
export function meterPeriods(rows: MeterRow[], weekly: boolean): [string, MeterRow[]][] {
  const m = new Map<string, MeterRow[]>();
  for (const r of rows) {
    const k = periodKey(r.day, weekly);
    m.set(k, [...(m.get(k) ?? []), r]);
  }
  return [...m.entries()].sort((a, b) => (a[0] < b[0] ? 1 : a[0] > b[0] ? -1 : 0));
}
export type MeterSplit = "source" | "role" | "model";
export function meterSplit(rows: MeterRow[], by: MeterSplit): [string, MeterAgg][] {
  const key = (r: MeterRow): string => (by === "source" ? r.source : by === "role" ? r.role : `${r.provider}/${r.model}`);
  const m = new Map<string, MeterRow[]>();
  for (const r of rows) m.set(key(r), [...(m.get(key(r)) ?? []), r]);
  return [...m.entries()].map(([k, v]) => [k, totalRows(v)] as [string, MeterAgg]).sort((a, b) => b[1].tokens - a[1].tokens);
}
export const cacheHitText = (a: MeterAgg): string => (a.context <= 0 ? NONE : `${Math.round((a.cacheRead * 1000) / a.context) / 10}%`);
export const avgContextText = (a: MeterAgg): string => (a.turns <= 0 ? NONE : statsCompact(Math.trunc(a.contextSum / a.turns)));
export const meterCostText = (a: MeterAgg): string => (a.cost == null ? NONE : `$${Math.round(a.cost * 100) / 100}`);
export function deltaText(cur: number, prev: number | null): string {
  if (prev == null || prev <= 0) return NONE;
  const pct = Math.round(((cur - prev) * 100) / prev);
  return (pct > 0 ? "+" : "") + pct + "%";
}

// ---------------------------------------------------------------- requests, comparison, cost (UsageRequestView)

export const NA = "n/a";
export function priceFor(model: string | null | undefined, prices: MeterPrice[]): MeterPrice | null {
  if (!model) return null;
  const m = model.toLowerCase();
  return prices.find((p) => p.match.trim() !== "" && m.includes(p.match.toLowerCase())) ?? null;
}
function estimate(p: MeterPrice | null, i?: number | null, o?: number | null, cr?: number | null, cw?: number | null): number | null {
  return p ? ((i ?? 0) * p.input + (o ?? 0) * p.output + (cr ?? 0) * p.cacheRead + (cw ?? 0) * p.cacheWrite) / 1e6 : null;
}
export const estimateRow = (r: MeterRow, prices: MeterPrice[]): number | null => estimate(priceFor(r.model, prices), r.input, r.output, r.cacheRead, r.cacheWrite);
export const estimateRequest = (r: MeterRequest, prices: MeterPrice[]): number | null =>
  r.input == null && r.output == null && r.cacheRead == null && r.cacheWrite == null ? null : estimate(priceFor(r.model, prices), r.input, r.output, r.cacheRead, r.cacheWrite);
export const pctText = (v: number | null): string => (v == null ? NA : `${Math.round(v * 10) / 10}%`);
export const usdText = (v: number | null): string => (v == null ? NA : `~$${Math.round(v * 100) / 100}`);
export const turnsText = (n: number): string => (n === 1 ? "1 turn" : `${n} turns`);
export const shareText = (v: number | null): string => (v == null ? NA : v > 0 && v < 0.005 ? "<1%" : `${Math.round(v * 100)}%`);

export interface HarnessLine {
  harness: string;
  turns: number;
  tokensPerTurn: number | null;
  avgContext: number | null;
  cacheHit: number | null;
  estCost: number | null;
}
export function compareHarnesses(report: UsageMeterReport): HarnessLine[] {
  const by = new Map<string, MeterRow[]>();
  for (const r of report.rows) by.set(r.provider, [...(by.get(r.provider) ?? []), r]);
  return [...by.entries()]
    .map(([h, rows]) => {
      const agg = totalRows(rows);
      const reqs = report.requests.filter((r) => r.harness === h);
      const cacheKnown = reqs.length === 0 || reqs.some((r) => r.cacheRead != null);
      const est = rows.map((r) => estimateRow(r, report.prices));
      return {
        harness: h,
        turns: agg.turns,
        tokensPerTurn: agg.turns > 0 ? Math.trunc(agg.tokens / agg.turns) : null,
        avgContext: agg.turns > 0 ? Math.trunc(agg.contextSum / agg.turns) : null,
        cacheHit: !cacheKnown || agg.context <= 0 ? null : (agg.cacheRead * 100) / agg.context,
        estCost: est.length > 0 && est.every((e) => e != null) ? est.reduce<number>((a, e) => a + (e as number), 0) : null,
      };
    })
    .sort((a, b) => b.turns - a.turns);
}
/** Sessions that have requests, most recently active first. */
export function requestSessions(requests: MeterRequest[]): string[] {
  const last = new Map<string, number>();
  for (const r of requests) last.set(r.sessionId, Math.max(last.get(r.sessionId) ?? 0, r.ts));
  return [...last.entries()].sort((a, b) => b[1] - a[1]).map((e) => e[0]);
}
/** Context per request for one session, oldest first (null = the harness did not report). */
export const contextSeries = (requests: MeterRequest[], sessionId: string): (number | null)[] =>
  requests.filter((r) => r.sessionId === sessionId).sort((a, b) => a.ts - b.ts).map((r) => r.context ?? null);

export type CostBy = "session" | "ticket" | "role" | "model" | "harness";
export const COST_BYS: CostBy[] = ["session", "ticket", "role", "model", "harness"];
export interface CostNames {
  tickets: Record<string, string>;
  sessions: Record<string, string>;
}
export interface CostRow {
  key: string;
  label: string;
  detail: string | null;
  turns: number;
  cost: number | null;
  share: number | null;
}
export interface CostTable {
  rows: CostRow[];
  total: number | null;
  turns: number;
  unpriced: number;
}
const short = (id: string): string => id.slice(0, 8);
function costKey(r: MeterRequest, by: CostBy): string {
  switch (by) {
    case "session": return short(r.sessionId);
    case "ticket": return r.ticketId ? short(r.ticketId) : "(no ticket)";
    case "role": return r.role;
    case "model": return `${r.harness}/${r.model ?? NA}`;
    case "harness": return r.harness;
  }
}
function costLabel(r: MeterRequest, by: CostBy, names: CostNames): [string, string | null] {
  switch (by) {
    case "session": return [names.sessions[r.sessionId] ?? r.role, short(r.sessionId)];
    case "ticket": return r.ticketId ? [names.tickets[r.ticketId] ?? short(r.ticketId), short(r.ticketId)] : ["(no ticket)", null];
    case "role": return [r.role, null];
    case "model": return [r.model ?? NA, r.harness];
    case "harness": return [r.harness, null];
  }
}
export function costTable(report: UsageMeterReport, by: CostBy, names: CostNames): CostTable {
  const groups = new Map<string, MeterRequest[]>();
  for (const r of report.requests) groups.set(costKey(r, by), [...(groups.get(costKey(r, by)) ?? []), r]);
  const raw = [...groups.entries()].map(([k, rs]) => {
    const e = rs.map((r) => estimateRequest(r, report.prices));
    const [label, detail] = costLabel(rs[0]!, by, names);
    return { key: k, label, detail, turns: rs.length, cost: e.some((x) => x != null) ? e.reduce<number>((a, x) => a + (x ?? 0), 0) : null, share: null as number | null };
  });
  const priced = raw.filter((r) => r.cost != null).map((r) => r.cost as number);
  const total = priced.length ? priced.reduce((a, b) => a + b, 0) : null;
  const rows = raw
    .map((r) => ({ ...r, share: r.cost != null && total != null && total > 0 ? r.cost / total : null }))
    .sort((a, b) => (b.cost ?? -1) - (a.cost ?? -1) || b.turns - a.turns);
  return { rows, total, turns: raw.reduce((a, r) => a + r.turns, 0), unpriced: raw.filter((r) => r.cost == null).length };
}
