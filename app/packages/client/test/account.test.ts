// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import { describe, expect, it } from "vitest";
import { createQueryStore, type Envelope } from "../src";
import type { FrameLink } from "../src/stores/link";
import { accountQuerySpec, accountProviders, flowView, signInBody, createSignInTracker, signInUri, signOutBody, planNotice } from "../src/account";

const one = (extra: Record<string, unknown>) => accountProviders({ providers: [{ id: "ss", name: "SuperiorSoft", ...extra }] })[0];

describe("account model", () => {
  it("signed out: no flow, a plain card", () => {
    const p = one({ signedIn: false });
    expect(p).toMatchObject({ id: "ss", signedIn: false, email: null, plan: null });
    expect(flowView(p)).toBeNull();
    expect(signInBody("ss")).toBe('{"provider":"ss"}');
  });

  it("pending: shows the user code", () => {
    const v = flowView(one({ signedIn: false, flow: { id: "f", state: "pending", userCode: "ABCD-1234", verificationUri: "https://x/y" } }));
    expect(v).toMatchObject({ pending: true, code: "ABCD-1234", tone: "amber" });
  });

  it("denied and expired are shown, not hidden", () => {
    expect(flowView(one({ flow: { state: "denied" } }))).toMatchObject({ label: "Denied", tone: "red", pending: false });
    expect(flowView(one({ flow: { state: "expired" } }))?.label).toBe("Expired");
  });

  it("error carries its message", () => {
    expect(flowView(one({ flow: { state: "error", error: "token endpoint down" } }))?.detail).toBe("token endpoint down");
  });

  it("signed in: masked email and plan, flow hidden", () => {
    const p = one({ signedIn: true, user: { email: "a***@x.com" }, plan: { name: "Pro" }, flow: { state: "signed_in" } });
    expect(p).toMatchObject({ signedIn: true, email: "a***@x.com", plan: "Pro" });
    expect(flowView(p)).toBeNull();
  });

  it("opens the complete uri first", () => {
    expect(signInUri({ verificationUri: "https://a", verification_uri_complete: "https://b" })).toBe("https://b");
    expect(signInUri({ verificationUri: "https://a" })).toBe("https://a");
    expect(signInUri({})).toBeNull();
  });
});

describe("account store", () => {
  it("sign-out flips the card at once: the answer makes the store read account_status again", () => {
    const sent: Envelope[] = [];
    const ls = new Set<(e: Envelope) => void>();
    const link: FrameLink = { sendFrame: (e) => (sent.push(e), true), onFrame: (l) => (ls.add(l), () => ls.delete(l)) };
    const push = (e: Envelope) => ls.forEach((l) => l(e));
    const store = createQueryStore(link, accountQuerySpec);
    const view = () => accountProviders(store.getState().data)[0];
    store.getState().refresh();
    push({ type: "account_status", data: JSON.stringify({ providers: [{ id: "ss", signedIn: true, user: { email: "a***@x.com" }, plan: { status: "active" } }] }), requestId: sent[0].requestId });
    expect(view()).toMatchObject({ signedIn: true, email: "a***@x.com", plan: "active" });
    expect(store.getState().send("account_sign_out", signOutBody("ss"))).toBe(true);
    const out = sent[sent.length - 1];
    push({ type: "account_sign_out", data: "{}", requestId: out.requestId });
    const reread = sent[sent.length - 1];
    expect(reread.type).toBe("account_status");
    push({ type: "account_status", data: JSON.stringify({ providers: [{ id: "ss", signedIn: false }] }), requestId: reread.requestId });
    expect(view().signedIn).toBe(false);
  });
});

describe("sign-in answer per provider card", () => {
  it("with 2 providers only the clicked card opens the page and shows the refusal", () => {
    const t = createSignInTracker();
    const opened: string[] = [];
    const answer = { verification_uri_complete: "https://x/y" };
    t.start("b");
    for (const id of ["a", "b"]) if (t.owns(id, answer) && signInUri(answer)) opened.push(id);
    expect(opened).toEqual(["b"]);
    expect(t.owns("a")).toBe(false);
    expect(t.owns("b")).toBe(true);
    expect(t.owns("a", { provider: "a" })).toBe(true);
  });
});

describe("plan notice (ticket ffb0b44b)", () => {
  it("a signed-in account with no plan says so and carries the plans page", () => {
    const p = one({ signedIn: true, signIn: "signed_in", billingUrl: "https://x.test/plans" });
    expect(planNotice(p)).toBe("This account has no active plan.");
    expect(p.billingUrl).toBe("https://x.test/plans");
  });
  it("an active plan has no notice; canceled and past due do", () => {
    expect(planNotice(one({ signedIn: true, plan: { name: "Basic", status: "active" } }))).toBeNull();
    expect(planNotice(one({ signedIn: true, plan: { name: "Basic" } }))).toBeNull();
    expect(planNotice(one({ signedIn: true, plan: { status: "past_due" } }))).toBe("Your plan is past due.");
  });
  it("signed out or unreachable: no notice", () => {
    expect(planNotice(one({ signedIn: false }))).toBeNull();
    expect(planNotice(one({ signedIn: true, signIn: "unreachable" }))).toBeNull();
  });
});
