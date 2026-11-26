// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The Services tab's account model: what a provider card shows and does, read from the daemon's `account_status` document
// (`providers[]` with `signedIn`, `user.email` (masked), `plan`, `error`, and `flow` while a device-code sign-in runs).
// Pure: the panel only draws it, so every state (signed out, pending, denied, expired, error, signed in) is tested here.

export type FlowState = "pending" | "denied" | "expired" | "error" | "signed_in";

export interface AccountFlow {
  state: FlowState;
  userCode: string | null;
  verificationUri: string | null;
  error: string | null;
}

/** The one sign-in state of a provider (daemon `signIn`): the card shows exactly this one. */
export type SignInState = "signed_in" | "expired" | "signed_out" | "unreachable";
const SIGN_IN_STATES: SignInState[] = ["signed_in", "expired", "signed_out", "unreachable"];

export interface AccountProvider {
  id: string;
  name: string;
  description: string | null;
  /** True while the app holds a sign-in (also when the service cannot be reached right now). */
  signedIn: boolean;
  signIn: SignInState;
  /** Masked by the daemon. */
  email: string | null;
  plan: string | null;
  /** The account document could not be read. */
  error: string | null;
  flow: AccountFlow | null;
}

import type { QuerySpec } from "./stores/query";

type Obj = Record<string, unknown>;
const obj = (v: unknown): Obj | null => (v && typeof v === "object" && !Array.isArray(v) ? (v as Obj) : null);
const text = (o: Obj | null, k: string): string | null => (o && typeof o[k] === "string" && (o[k] as string) !== "" ? (o[k] as string) : null);

export function accountProviders(data: Obj | null): AccountProvider[] {
  const list = data && Array.isArray(data.providers) ? data.providers : [];
  return list.flatMap((raw) => {
    const p = obj(raw);
    const id = text(p, "id");
    if (!p || !id) return [];
    const plan = obj(p.plan);
    const f = obj(p.flow);
    const state = text(f, "state");
    return [
      {
        id,
        name: text(p, "name") ?? id,
        description: text(p, "description"),
        signedIn: p.signedIn === true,
        signIn: SIGN_IN_STATES.includes(p.signIn as SignInState) ? (p.signIn as SignInState) : p.signedIn === true ? "signed_in" : "signed_out",
        email: text(obj(p.user), "email"),
        plan: plan ? (text(plan, "name") ?? text(plan, "status")) : text(p, "plan"),
        error: text(p, "error"),
        flow: f && state ? { state: state as FlowState, userCode: text(f, "userCode"), verificationUri: text(f, "verificationUri"), error: text(f, "error") } : null,
      },
    ];
  });
}

export type FlowTone = "neutral" | "red" | "green" | "amber";

/** What the card says about a sign-in flow; null when there is none worth showing (none, or a finished one on a signed-in card). */
export function flowView(p: AccountProvider): { tone: FlowTone; label: string; detail: string | null; code: string | null; pending: boolean } | null {
  const f = p.flow;
  if (!f || p.signedIn || f.state === "signed_in") return null;
  switch (f.state) {
    case "pending":
      return { tone: "amber", label: "Waiting for approval", detail: "Approve this device in your browser, then come back.", code: f.userCode, pending: true };
    case "denied":
      return { tone: "red", label: "Denied", detail: "The sign-in was denied in the browser.", code: null, pending: false };
    case "expired":
      return { tone: "red", label: "Expired", detail: "The sign-in code ran out. Start again.", code: null, pending: false };
    default:
      return { tone: "red", label: "Sign-in failed", detail: f.error ?? "The sign-in failed.", code: null, pending: false };
  }
}

export const signInBody = (provider: string): string => JSON.stringify({ provider });
export const signOutBody = (provider: string): string => JSON.stringify({ provider });

/** The page to open for an `account_sign_in` answer; null when the answer carries none. */
export function signInUri(answer: Obj | null): string | null {
  return text(answer, "verification_uri_complete") ?? text(answer, "verificationUri");
}

/**
 * The account store's spec. `account_status` answers and pushes replace the data; the answer to a sign-in or sign-out only
 * means "the account changed", so the store reads `account_status` again and the card updates without a reload.
 */
export const accountQuerySpec: QuerySpec<Obj | null> = {
  prefix: "account",
  request: "account_status",
  body: () => "{}",
  answers: ["account_status"],
  pushes: { account_status: (raw) => obj(raw) },
  rereads: ["account_sign_in", "account_sign_out"],
  parse: (raw) => obj(raw),
  empty: null,
  own: ["account_status", "account_sign_in", "account_sign_out"],
};

/**
 * Which provider card asked for the sign-in answer in the shared `account_sign_in` store. Every card reads that one answer
 * and refusal, so only the card that clicked Sign in may open its page or show its refusal. The answer's own `provider`
 * wins when the daemon names one.
 */
export function createSignInTracker() {
  let clicked: string | null = null;
  return {
    start(provider: string): void {
      clicked = provider;
    },
    owns(provider: string, answer: Obj | null = null): boolean {
      const named = text(answer, "provider");
      return (named ?? clicked) === provider;
    },
  };
}
export const signInTracker = createSignInTracker();

// ---- The Services hub (SERVICES-2): a list of providers, a connected card opens its dashboard inside the tab. ----

export type CardTone = "neutral" | "green" | "amber";

/** The chip of a provider card, one true state: Signed in, Service unreachable, Waiting for approval (a sign-in flow
 * runs), Sign-in expired, or Not signed in. */
export function cardChip(p: AccountProvider): { label: string; tone: CardTone } {
  if (p.signedIn) return p.signIn === "unreachable" ? { label: "Service unreachable", tone: "amber" } : { label: "Signed in", tone: "green" };
  if (p.flow?.state === "pending") return { label: "Waiting for approval", tone: "amber" };
  if (p.signIn === "expired") return { label: "Sign-in expired", tone: "amber" };
  return { label: "Not signed in", tone: "neutral" };
}

/** One short line under a card that is not signed in: the app holds its own sign-in, apart from the website's. */
export const SEPARATE_SIGN_IN_TEXT = "The app signs in on its own: being signed in on the website does not sign in the app.";

/** The list's empty text (Kotlin, AccountScreens.kt:131-134). */
export const NO_PROVIDERS_TEXT = "No service has registered an account provider yet. A plugin adds one by publishing an account.provider/<id> endpoint";
/** Kotlin's sign-out confirm (AccountScreens.kt:233-240). */
export const signOutConfirm = (name: string) => ({ title: `Sign out of ${name}?`, body: "Plugins that use this account stop working until you sign in again." });

/** One page of a service's dashboard: a service the daemon lists with the `section` that draws it. */
export interface ServicePage {
  id: string;
  name: string;
  section: string;
}

/** The dashboard pages of the account document: its `services` that name a `section`. */
export function servicePages(data: Obj | null): ServicePage[] {
  const list = data && Array.isArray(data.services) ? data.services : [];
  return list.flatMap((raw) => {
    const s = obj(raw);
    const id = text(s, "id");
    const section = text(s, "section");
    return id && section ? [{ id, name: text(s, "name") ?? id, section }] : [];
  });
}

/** The list, or one connected provider's dashboard. A provider that is no longer connected (signed out elsewhere) sends the view back to the list. */
export function hubView(providers: AccountProvider[], openId: string | null): { kind: "list" } | { kind: "dashboard"; provider: AccountProvider } {
  const p = openId ? providers.find((x) => x.id === openId) : undefined;
  return p && p.signedIn ? { kind: "dashboard", provider: p } : { kind: "list" };
}

/** Views that used to be their own tab and now live inside Services (an old saved layout or link names them). */
const MERGED_INTO_SERVICES: Record<string, string> = { sites: "services", "infantry.sites": "infantry.account" };
export const mergedTabTarget = (id: string): string | null => MERGED_INTO_SERVICES[id] ?? null;

/**
 * Whether a card may open the approval page. Only a Sign in click of THIS mounted card does, and never when signed in:
 * a stale answer left in the shared answer store by a sign-in that finished long ago must not open a page when the tab is shown again.
 */
export function shouldOpenApproval(o: { clickedHere: boolean; signedIn: boolean; uri: string | null; alreadyOpened: string | null }): boolean {
  return o.clickedHere && !o.signedIn && !!o.uri && o.uri !== o.alreadyOpened;
}
