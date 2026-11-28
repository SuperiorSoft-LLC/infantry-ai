// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import { describe, expect, it } from "vitest";
import { noticeSoundOf, osNoticeAllowed } from "../src";

const push = (level?: string) => ({ type: "notification_push", data: JSON.stringify({ title: "t", level }) });
const mail = { type: "mail", data: JSON.stringify({ id: "m1" }) };

describe("notice sounds", () => {
  it("plays nothing by default", () => {
    const unset = () => undefined;
    expect(noticeSoundOf(mail, unset)).toBeNull();
    for (const l of ["info", "success", "warning", "error", "urgent"]) expect(noticeSoundOf(push(l), unset)).toBeNull();
  });
  it("plays the chosen sound for the matching notice only", () => {
    const s: Record<string, unknown> = { "notify.commsSound": "chime", "notify.levelSound.urgent": "alarm" };
    const read = (k: string) => s[k];
    expect(noticeSoundOf(mail, read)).toBe("chime");
    expect(noticeSoundOf(push("urgent"), read)).toBe("alarm");
    expect(noticeSoundOf(push("info"), read)).toBeNull();
    expect(noticeSoundOf({ type: "ticket", data: "{}" }, read)).toBeNull();
  });
  it("ignores none and unknown names", () => {
    expect(noticeSoundOf(mail, () => "none")).toBeNull();
    expect(noticeSoundOf(mail, () => "../x.wav")).toBeNull();
  });
  it("gates OS notices on focus only when notify.background is on", () => {
    expect(osNoticeAllowed(() => undefined, true)).toBe(true);
    expect(osNoticeAllowed(() => false, true)).toBe(true);
    expect(osNoticeAllowed(() => true, true)).toBe(false);
    expect(osNoticeAllowed(() => true, false)).toBe(true);
  });
});
