// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The Sites tab's model: how a `sites_list` answer reads (state chips, messages, dates, the Manage link) and the refresh
// loop that keeps it current. Pure and DOM-free so the panel stays a view. Texts follow the legacy SitesModel.kt.
import type { QueryStore } from "./stores/query";

export type SiteTone = "neutral" | "red" | "green" | "blue" | "amber";
export type SiteRec = Record<string, unknown>;

/** The setting that holds the refresh period (seconds, 0 = off); declared in Infantry_Sites' module.json. */
export const SITES_REFRESH_NODE = "Infantry_Sites.refresh";
export const SITES_REFRESH_KEY = "sites.refreshSeconds";
export const SITES_REFRESH_DEFAULT = 30;

const s = (o: SiteRec | null | undefined, k: string): string | null => (o && typeof o[k] === "string" && (o[k] as string).trim() ? (o[k] as string) : null);

export interface StateView {
  label: string;
  tone: SiteTone;
  /** The sentence the tab shows for this state; null when the daemon's own message (or nothing) is enough. */
  text: string | null;
}

/** Why hosting is unavailable for this plan state. */
export function entitlementText(planStatus: string | null): string {
  if (planStatus === "past_due") return "Payment failed. Update your payment method to keep hosting.";
  if (planStatus === "canceled") return "Your subscription ended.";
  return "Your plan does not include hosting yet.";
}

/** The account-level state of a `sites_list` answer (signed_out, not_entitled, limit, ...). `ok`/absent is the normal list. */
export function accountState(data: SiteRec | null): StateView | null {
  const state = s(data, "state");
  switch (state) {
    case null:
    case "ok":
    case "active":
      return null;
    case "signed_out":
      return { label: "signed out", tone: "amber", text: "Sign in to Infantry AI Hosting first." };
    case "not_entitled":
      return { label: "not included", tone: "amber", text: entitlementText(s(data, "planStatus")) };
    case "limit":
      return { label: "limit reached", tone: "amber", text: "You're at your plan's server limit." };
    case "email_unverified":
      return { label: "verify email", tone: "amber", text: "Verify your email address to create a server." };
    case "not_configured":
      return { label: "not configured", tone: "neutral", text: s(data, "message") ?? "Hosting is not configured." };
    case "unavailable":
      return { label: "unavailable", tone: "red", text: s(data, "message") ?? "Hosting is unavailable right now." };
    default:
      return { label: state, tone: "neutral", text: s(data, "message") }; // unknown: its raw text, never a break
  }
}

/** A server's status as a chip; an unknown status shows its raw text. */
export function serverStatus(status: string | null): { label: string; tone: SiteTone } {
  const raw = status ?? "?";
  switch (raw.toLowerCase()) {
    case "active":
    case "online":
      return { label: "online", tone: "green" };
    case "off":
      return { label: "off", tone: "neutral" };
    case "new":
    case "setting_up":
      return { label: "setting up", tone: "amber" };
    case "destroying":
      return { label: "destroying", tone: "red" };
    default:
      return { label: raw, tone: "neutral" };
  }
}

/** A server time as a readable local date ("Nov 21, 2026"): epoch milliseconds or an ISO string; unparsable gives null. */
export function siteDate(value: unknown): string | null {
  const v = typeof value === "number" ? String(value) : typeof value === "string" ? value.trim() : "";
  if (!v) return null;
  const t = /^\d+$/.test(v) ? Number(v) : Date.parse(v);
  if (!Number.isFinite(t)) return null;
  return new Date(t).toLocaleDateString("en-US", { month: "short", day: "numeric", year: "numeric" });
}

/** A site address only when it is an http(s) URL. */
export function manageUrl(data: SiteRec | null): string | null {
  const u = s(data, "siteUrl");
  return u && /^https?:\/\//i.test(u) ? u : null;
}

/** The answer to show: a failed refresh (a refusal, or an `unavailable` answer) keeps the last good one. */
export function shownAnswer(data: SiteRec | null, refusal: string | null, lastGood: SiteRec | null): { data: SiteRec | null; error: string | null; stale: boolean } {
  const failed = refusal !== null || s(data, "state") === "unavailable";
  if (!failed) return { data, error: null, stale: false };
  const error = (refusal ?? s(data, "message") ?? "Hosting is unavailable right now.").replace(/[. ]+$/, "");
  return lastGood ? { data: lastGood, error, stale: true } : { data, error: refusal, stale: false };
}

export interface AutoRefresh {
  seconds: number;
  /** True while the tab is on screen. */
  visible: () => boolean;
  /** Test seams. */
  setTimer?: (fn: () => void, ms: number) => unknown;
  clearTimer?: (h: unknown) => void;
  now?: () => number;
}

/**
 * Re-reads [store] every `seconds` while visible: off at 0, one request in flight (a slow answer never stacks another;
 * a request unanswered for four periods is given up on). Returns the stop function; call `poke` when visibility changes.
 */
export function startAutoRefresh<T>(store: QueryStore<T>, o: AutoRefresh): { stop: () => void; poke: () => void } {
  const setT = o.setTimer ?? ((f, ms) => setInterval(f, ms));
  const clearT = o.clearTimer ?? ((h) => clearInterval(h as ReturnType<typeof setInterval>));
  const now = o.now ?? Date.now;
  let sentAt = 0;
  const tick = () => {
    if (!o.visible()) return;
    const st = store.getState();
    if (st.loading && now() - sentAt < o.seconds * 4000) return;
    sentAt = now();
    st.refresh();
  };
  const h = o.seconds > 0 ? setT(tick, o.seconds * 1000) : null;
  return {
    stop: () => {
      if (h !== null) clearT(h);
    },
    poke: tick,
  };
}
