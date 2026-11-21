// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The usage store: the daemon's `usage_report` (one meter per plan account: percent used, projected landing, pace,
// workers running against the pacing allowance, override) plus the `limits` windows. Asked for on `ready`, refreshed
// on demand (`usage_get` "force"), changed with `usage_set` (a typed percent, an override, the rule). The text lines
// the cards show live here too, as pure functions, so every surface says the same thing. The link is injected, so the
// store is tested with recorded frames and no socket.
import { createStore, type StoreApi } from "zustand/vanilla";
import { json, type Envelope } from "../wire";
import type { HarnessInfo } from "./launcher";

export interface LimitWindow {
  provider: string;
  label: string;
  windowMinutes: number;
  percent: number | null;
  resetsAt: number | null;
  observedAt: number;
  source: string;
  paceDelta: number | null;
}

export interface UsageOverride {
  workers: number | null;
  until: number;
}

export interface PacingTier {
  below?: number | null;
  workers: number;
}

export interface PacingRule {
  enabled: boolean;
  tiers: PacingTier[];
  mode: string;
  maxWorkers: number;
  okProjected: number;
  hardStopPercent: number;
}

export interface MeterUsage {
  meter: string;
  percent: number | null;
  window: string | null;
  resetsAt: number | null;
  paceDelta: number | null;
  source: string;
  windows: LimitWindow[];
  reason: string | null;
  ruleAllowance: number | null;
  override: UsageOverride | null;
  allowance: number | null;
  running: number;
  canStart: boolean;
  warning: string | null;
  updatedAt: number | null;
  projectedPercent: number | null;
  reasoning: string | null;
}

export interface UsageReport {
  meters: MeterUsage[];
  rule: PacingRule;
  observedAt: number;
}

export interface ProviderLimits {
  provider: string;
  status: string;
  windows: LimitWindow[];
  reason: string | null;
  observedAt: number;
  stale?: boolean;
  retryAt?: number | null;
}

/** The `usage_set` body. */
export interface UsageSet {
  meter: string;
  manualPercent?: number;
  manualResetsAt?: number;
  clearManual?: boolean;
  overrideWorkers?: number | null;
  overrideUntil?: number;
  clearOverride?: boolean;
  rule?: unknown;
}

export interface UsageLink {
  sendFrame(env: Envelope): boolean;
  onFrame(listener: (env: Envelope) => void): () => void;
}

export interface UsageState {
  report: UsageReport | null;
  limits: ProviderLimits[];
  /** A forced read is in flight. */
  refreshing: boolean;
  /** The last refusal (a bad percent, an override out of range), shown once and cleared by the view. */
  refusal: string | null;
  /** When the report arrived (client clock), for "updated Nm ago" when the daemon gave no reading time. */
  receivedAt: number | null;
}

export interface UsageActions {
  ensureLoaded(): void;
  /** A fresh live read (the daemon answers its cache inside its manual floor). */
  refresh(): void;
  /** Type a percent by hand for [meter]; [resetsInMinutes] optional. */
  typePercent(meter: string, percent: number, resetsInMinutes?: number): boolean;
  clearTyped(meter: string): boolean;
  /** Raise or lower [meter]'s allowance: [workers] null = no limit, for [hours] (default: until the reset, else 2 h). */
  override(meter: string, workers: number | null, hours?: number): boolean;
  clearOverride(meter: string): boolean;
  clearRefusal(): void;
  dispose(): void;
}

export type UsageStore = StoreApi<UsageState & UsageActions>;

const HOUR = 3_600_000;

export function createUsageStore(link: UsageLink, clock: () => number = Date.now): UsageStore {
  let counter = 0;
  const pending = new Set<string>();
  return createStore<UsageState & UsageActions>()((set, get) => {
    const send = (type: string, data: string): boolean => {
      counter += 1;
      const requestId = `usage:${counter.toString(36)}`;
      pending.add(requestId);
      const ok = link.sendFrame({ type, data, requestId });
      if (!ok) {
        pending.delete(requestId);
        set({ refusal: `${type} not sent: the daemon link is down`, refreshing: false });
      }
      return ok;
    };
    const setUsage = (body: UsageSet): boolean => send("usage_set", JSON.stringify(body));
    const ask = (): void => {
      link.sendFrame({ type: "usage_get", data: "" });
      link.sendFrame({ type: "limits", data: "" });
    };
    const off = link.onFrame((env) => {
      switch (env.type) {
        case "ready":
          ask();
          return;
        case "usage_report": {
          const r = json<UsageReport>(env.data);
          if (r && Array.isArray(r.meters)) set({ report: r, receivedAt: clock(), refreshing: false });
          if (env.requestId) pending.delete(env.requestId);
          return;
        }
        case "limits": {
          const l = json<ProviderLimits[]>(env.data);
          if (Array.isArray(l)) set({ limits: l });
          return;
        }
        case "error": {
          if (!env.requestId || !pending.has(env.requestId)) return;
          pending.delete(env.requestId);
          set({ refusal: env.data, refreshing: false });
          return;
        }
      }
    });
    return {
      report: null,
      limits: [],
      refreshing: false,
      refusal: null,
      receivedAt: null,
      ensureLoaded() {
        if (!get().report) ask();
      },
      refresh() {
        set({ refreshing: true });
        if (send("usage_get", "force")) link.sendFrame({ type: "limits", data: "" });
      },
      typePercent(meter, percent, resetsInMinutes) {
        const body: UsageSet = { meter, manualPercent: percent };
        if (resetsInMinutes && resetsInMinutes > 0) body.manualResetsAt = clock() + resetsInMinutes * 60_000;
        return setUsage(body);
      },
      clearTyped: (meter) => setUsage({ meter, clearManual: true }),
      override(meter, workers, hours) {
        const now = clock();
        const m = get().report?.meters.find((x) => x.meter === meter);
        const until = hours && hours > 0 ? now + hours * HOUR : m?.resetsAt && m.resetsAt > now ? m.resetsAt : now + 2 * HOUR;
        return setUsage({ meter, overrideWorkers: workers, overrideUntil: Math.round(until) });
      },
      clearOverride: (meter) => setUsage({ meter, clearOverride: true }),
      clearRefusal() {
        set({ refusal: null });
      },
      dispose() {
        off();
      },
    };
  });
}

// ---- the card lines ----

const num = (d: number): string => (Number.isInteger(d) ? String(d) : d.toFixed(1));

/** "3d 4h", "2h 15m", "12m". */
export function until(at: number, now: number): string {
  const mins = Math.max(0, Math.floor((at - now) / 60_000));
  const d = Math.floor(mins / 1440);
  const h = Math.floor((mins % 1440) / 60);
  if (d > 0) return `${d}d ${h}h`;
  if (h > 0) return `${h}h ${mins % 60}m`;
  return `${mins}m`;
}

/** The rule in one line: "pace · up to 5 ticket workers while projected <= 100% at reset · 1 from 95% used". */
export function ruleLine(rule: PacingRule | null | undefined): string {
  if (!rule || !rule.enabled) return "Pacing off: no limit on ticket workers";
  if (rule.mode === "pace") {
    return `pace · up to ${rule.maxWorkers} ticket workers while projected <= ${num(rule.okProjected)}% at reset · 1 from ${num(rule.hardStopPercent)}% used`;
  }
  return `${rule.tiers.map((t) => (t.below != null ? `<${num(t.below)}% -> ${t.workers}` : `else -> ${t.workers}`)).join(" · ")} ticket workers`;
}

/** "12 pts under pace", "4 pts ahead of pace", "on pace"; null without a reading. */
export function paceWords(delta: number | null | undefined): string | null {
  if (delta == null) return null;
  const pts = Math.round(Math.abs(delta));
  if (pts === 0) return "on pace";
  return delta > 0 ? `${pts} pts ahead of pace` : `${pts} pts under pace`;
}

/** "projected 93% at reset". */
export function projectedLine(m: MeterUsage): string | null {
  return m.projectedPercent == null ? null : `projected ${Math.round(m.projectedPercent)}% at reset`;
}

/** "Resets Mon 18 3:00 PM". */
export function resetLine(at: number | null | undefined): string | null {
  if (at == null) return null;
  const d = new Date(at);
  const day = d.toLocaleDateString("en-US", { weekday: "short", month: "short", day: "numeric" });
  const time = d.toLocaleTimeString("en-US", { hour: "numeric", minute: "2-digit" });
  return `Resets ${day} ${time}`;
}

/** "2 of 3 workers running" or "1 workers running · no limit". */
export function workersLine(m: MeterUsage): string {
  return m.allowance == null ? `${m.running} workers running · no limit` : `${m.running} of ${m.allowance} workers running`;
}

/** "allowance raised to no limit · ends in 38h 45m (pacing rule says 1)"; null without an override. */
export function overrideLine(m: MeterUsage, now: number): string | null {
  const o = m.override;
  if (!o) return null;
  const to = o.workers == null ? "no limit" : String(o.workers);
  const rule = m.ruleAllowance == null ? "no limit" : String(m.ruleAllowance);
  const verb = o.workers != null && m.ruleAllowance != null && o.workers < m.ruleAllowance ? "lowered" : "raised";
  return `allowance ${verb} to ${to} · ends in ${until(o.until, now)} (pacing rule says ${rule})`;
}

const REASONS: Record<string, string> = {
  no_credentials: "not signed in",
  expired: "sign-in expired",
  no_subscription: "no plan on this account",
  rate_limited: "the provider asked us to slow down",
  network: "could not reach the provider",
  bad_response: "the provider answered in an unknown format",
  live_sources_off: "live reading is off",
  source_failed: "the reader failed",
  not_read_yet: "not read yet",
};

export function reasonText(reason: string | null | undefined): string {
  if (!reason) return "unknown";
  if (reason in REASONS) return REASONS[reason];
  return reason.startsWith("http_") ? `the provider answered HTTP ${reason.slice(5)}` : reason;
}

/** "updated just now", "updated 4m ago", "updated 2h ago". */
export function updatedLine(at: number | null | undefined, now: number): string | null {
  if (at == null) return null;
  const mins = Math.floor((now - at) / 60_000);
  if (mins < 1) return "updated just now";
  if (mins < 60) return `updated ${mins}m ago`;
  return `updated ${Math.floor(mins / 60)}h ago`;
}

/** When the daemon asks [meter]'s provider again: the retry time of its last failed read while still ahead, else null. */
export function retryAtFor(limits: ProviderLimits[], meter: string, now: number): number | null {
  const at = limits.find((l) => l.provider === meter)?.retryAt;
  return at != null && at > now ? at : null;
}

/** "next try in 4m", or "next try shortly" inside the last minute; null when the daemon states no time. */
export function nextTryLine(retryAt: number | null | undefined, now: number): string | null {
  if (retryAt == null) return null;
  return retryAt - now < 60_000 ? "next try shortly" : `next try in ${until(retryAt, now)}`;
}

/**
 * Why a reading is not a clean live one, said ONCE: the reason, what the card shows meanwhile (the last good reading, a
 * typed value), when the daemon asks again, and that pacing is off while there is nothing to judge by. Null for a clean
 * live reading or one typed by hand. The age of a kept reading is the "updated Nm ago" of [meterLines].
 */
export function readingNote(m: MeterUsage, retryAt: number | null | undefined, now: number): string | null {
  // the daemon warns without a reading only when pacing fails open on this meter
  const failsOpen = m.percent == null && m.warning != null ? "pacing is off meanwhile" : null;
  if (!m.reason) return m.source === "none" ? ["No usage reading for this account", failsOpen].filter(Boolean).join(" · ") : null;
  const reason = reasonText(m.reason);
  const shown = m.source === "live" ? "showing the last good reading" : m.source === "manual" ? "showing the typed value" : null;
  return [reason.charAt(0).toUpperCase() + reason.slice(1), shown, nextTryLine(retryAt, now), failsOpen].filter(Boolean).join(" · ");
}

/** The text lines of one meter card. */
export interface MeterLines {
  /** Under the bar, left: the projected landing, or how the number was got. */
  headline: string | null;
  /** "Resets Mon 18 3:00 PM · updated 12m ago". */
  detail: string | null;
  /** Why the reading is not a clean live one (the one place the reason is said), or null. */
  note: string | null;
  /** The daemon's own warning, only while the note does not already say it. */
  warning: string | null;
}

/**
 * What the card says under its bar. Every fact once: the reason a reading is missing or old is the note alone (it was
 * the headline, the detail line and the raw warning too, so a rate limit read three times over), and a reading the
 * daemon kept from before a failure keeps its age ("updated 12m ago") instead of reading "no reading".
 */
export function meterLines(m: MeterUsage, limits: ProviderLimits[], now: number): MeterLines {
  const note = readingNote(m, retryAtFor(limits, m.meter, now), now);
  const live = m.source === "live";
  return {
    headline: projectedLine(m) ?? (m.source === "manual" ? "typed by hand" : live && !m.reason ? "live" : null),
    detail: [resetLine(m.resetsAt), live ? updatedLine(m.updatedAt, now) : null].filter(Boolean).join(" · ") || null,
    note,
    warning: note ? null : m.warning,
  };
}

/** The meter's display name (the plan account). */
export function meterName(meter: string): string {
  if (meter === "claude") return "Claude";
  if (meter === "opencode-go") return "OpenCode Go";
  return meter.charAt(0).toUpperCase() + meter.slice(1);
}

/**
 * The harnesses the Usage tab has no meter card for but can still list, each with a reason: it cannot run here (the
 * daemon's `unavailableReason`) or it runs and the daemon reads no plan limit for it (its `usageNote`). A harness that is
 * already a meter is left out (a worker running on one adds its own card). The daemon states the reasons; nothing here
 * names a harness.
 */
export function unmeteredHarnesses(harnesses: HarnessInfo[], meters: MeterUsage[]): HarnessInfo[] {
  const metered = new Set(meters.map((m) => m.meter));
  return harnesses.filter((h) => !metered.has(h.id) && (h.available === false || !!h.usageNote?.trim()));
}
