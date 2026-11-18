// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import { describe, expect, it } from "vitest";
import { desktopPairingLink, parsePairingLink, qrPath, updateNotice, type UpdateInfo } from "../src";

const base: UpdateInfo = { available: true, current: "0.1.0", version: "0.2.0", notes: null, date: null, channel: "nightly", canInstall: true };

describe("update notice", () => {
  it("says an update is there and never installs; an unsigned build says it cannot install", () => {
    expect(updateNotice(base)).toMatchObject({ id: "app-update-0.2.0", title: "Infantry AI 0.2.0 is available", level: "info" });
    expect(updateNotice(base)?.body).toContain("Settings > About");
    expect(updateNotice({ ...base, canInstall: false })?.body).toContain("cannot install");
    expect(updateNotice({ ...base, available: false })).toBeNull();
  });
});

describe("desktop pairing", () => {
  it("makes the link the phone's connect screen reads, and a QR path", () => {
    const link = desktopPairingLink("100.67.51.8", 18990, "t".repeat(64), "DESK");
    expect(parsePairingLink(link)).toEqual({ ok: true, pairing: { url: "ws://100.67.51.8:18990/ws", token: "t".repeat(64), name: "DESK" } });
    expect(qrPath(2, (r, c) => r === c)).toBe("M0 0h1v1h-1zM1 1h1v1h-1z");
  });
});
