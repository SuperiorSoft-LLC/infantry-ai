// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import { describe, expect, it } from "vitest";
import { accountProviders, flowView, signInBody, signInUri } from "../src/account";

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
