// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import { describe, expect, it } from "vitest";
import { layoutFor, pairingLink, panelsToClose, parsePairingLink, socketUrl } from "../src";

describe("responsive layout", () => {
  it("a phone gets drawers, snapping lanes, close-on-pick and 44px targets; a desktop keeps both panels inline", () => {
    const phone = layoutFor(390);
    expect(phone).toMatchObject({ kind: "phone", leftInline: false, rightInline: false, laneSnap: true, closeDrawerOnPick: true, tapTarget: 44 });
    expect(layoutFor(1024)).toMatchObject({ kind: "tablet", leftInline: true, rightInline: false, laneSnap: false });
    expect(layoutFor(1600)).toMatchObject({ kind: "desktop", leftInline: true, rightInline: true, closeDrawerOnPick: false, tapTarget: 28 });
    expect(layoutFor(1600, true).tapTarget).toBe(44);
  });

  it("collapses a panel only when crossing into its drawer width, never on widening", () => {
    expect(panelsToClose(null, layoutFor(390))).toEqual({ left: true, right: true });
    expect(panelsToClose(layoutFor(1600), layoutFor(1024))).toEqual({ left: false, right: true });
    expect(panelsToClose(layoutFor(390), layoutFor(380))).toEqual({ left: false, right: false });
    expect(panelsToClose(layoutFor(390), layoutFor(1600))).toEqual({ left: false, right: false });
  });
});

describe("pairing link", () => {
  it("reads the Kotlin link format and round-trips it", () => {
    const r = parsePairingLink("infantry://connect?url=ws%3A%2F%2F100.67.51.8%3A18990%2Fws&token=s3cr%26t&name=Desk");
    expect(r).toEqual({ ok: true, pairing: { url: "ws://100.67.51.8:18990/ws", token: "s3cr&t", name: "Desk" } });
    if (r.ok) expect(parsePairingLink(pairingLink(r.pairing))).toEqual(r);
    expect(parsePairingLink("infantry://connect?url=http%3A%2F%2Fbox%3A9000")).toMatchObject({ ok: true, pairing: { url: "ws://box:9000/ws", token: null } });
  });

  it("refuses other links without repeating the input", () => {
    const bad = parsePairingLink("infantry://other?url=ws%3A%2F%2Fh&token=SECRET");
    expect(bad.ok).toBe(false);
    expect(JSON.stringify(bad)).not.toContain("SECRET");
    expect(parsePairingLink("infantry://connect?token=x").ok).toBe(false);
    expect(socketUrl("https://box.example/")).toBe("wss://box.example/ws");
  });
});
