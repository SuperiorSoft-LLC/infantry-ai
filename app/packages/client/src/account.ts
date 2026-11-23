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

export interface AccountProvider {
  id: string;
  name: string;
  description: string | null;
  signedIn: boolean;
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
