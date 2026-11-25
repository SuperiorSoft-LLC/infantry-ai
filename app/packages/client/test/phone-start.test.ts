// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import { describe, expect, it } from "vitest";
import { connectReasonText, decidePhoneStart, lostConnectionText } from "../src";

describe("PHONE-1: what a phone opens on", () => {
  const url = "ws://100.64.0.1:18990/ws";
  it("no pairing: the connect screen at once", () => {
    expect(decidePhoneStart({ mode: "remote", url: "", check: null })).toEqual({ open: "connect", why: "no-pairing" });
    expect(decidePhoneStart({ mode: "remote", check: null })).toEqual({ open: "connect", why: "no-pairing" });
  });
  it("a saved pairing that answers opens the app", () => {
    expect(decidePhoneStart({ mode: "remote", url, check: "ok" })).toEqual({ open: "app" });
  });
  it("unreachable, refused token, unreadable token: the connect screen with the reason", () => {
    expect(decidePhoneStart({ mode: "remote", url, check: "unreachable" })).toEqual({ open: "connect", why: "unreachable" });
    expect(decidePhoneStart({ mode: "remote", url, check: null })).toEqual({ open: "connect", why: "unreachable" });
    expect(decidePhoneStart({ mode: "remote", url, check: "refused" })).toEqual({ open: "connect", why: "refused" });
    expect(decidePhoneStart({ mode: "remote", url, tokenError: "keystore", check: "ok" })).toEqual({ open: "connect", why: "unreadable-token" });
  });
  it("a missing mode is decided the same (the caller knows it is a phone), never as a desktop", () => {
    expect(decidePhoneStart({ url, check: "unreachable" })).toEqual({ open: "connect", why: "unreachable" });
    expect(decidePhoneStart({ url: "", check: null })).toEqual({ open: "connect", why: "no-pairing" });
  });
  it("the texts name the PC, never a daemon or agent tools to start or install on the phone", () => {
    for (const why of ["no-pairing", "unreachable", "refused", "unreadable-token", "asked"] as const) {
      const t = connectReasonText(why, "Anthony's PC");
      expect(t).not.toMatch(/daemon|install|agent tool|Node/i);
      expect(t.length).toBeGreaterThan(20);
    }
    expect(connectReasonText("unreachable", "Anthony's PC")).toContain("Anthony's PC");
    expect(connectReasonText("unreachable", null)).toContain("your PC");
    expect(lostConnectionText(null, "closed (1006)")).not.toMatch(/daemon/i);
  });
});
