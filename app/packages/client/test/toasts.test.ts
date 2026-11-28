// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import { describe, expect, it } from "vitest";
import { devicePushOf, TOASTS_SHOWN, createToasts, toastOfFrame, createOsNotifier, type OsNotice, type Envelope } from "../src";

describe("toasts", () => {
  it("stacks newest first, bumps a repeat, caps the stack and expires", () => {
    const timers: (() => void)[] = [];
    const s = createToasts((fn) => timers.push(fn));
    s.getState().push({ tone: "info", text: "a" });
    s.getState().push({ tone: "info", text: "a" });
    expect(s.getState().toasts).toMatchObject([{ text: "a", count: 2 }]);
    for (let i = 0; i < TOASTS_SHOWN + 2; i += 1) s.getState().push({ tone: "error", text: `e${i}` });
    expect(s.getState().toasts).toHaveLength(TOASTS_SHOWN);
    expect(s.getState().toasts[0].text).toBe(`e${TOASTS_SHOWN + 1}`);
    timers.forEach((t) => t());
    expect(s.getState().toasts).toEqual([]);
  });

  it("maps owned refusals and pushed notifications only", () => {
    expect(toastOfFrame({ type: "error", data: "no such ticket\nmore", requestId: "agent-menu:1" })).toEqual({ tone: "error", text: "no such ticket" });
    expect(toastOfFrame({ type: "error", data: "x", requestId: "attn:1" })).toBeNull();
    expect(toastOfFrame({ type: "error", data: "x" })).toBeNull();
    expect(toastOfFrame({ type: "notification_push", data: '{"id":"n","title":"Built","level":"success"}' })).toEqual({ tone: "ok", text: "Built", detail: undefined });
  });
  it("toasts a refused ticket start, naming the cause", () => {
    const t = (start: object, requestId = "ui:start:1") => ({ type: "ticket", requestId, data: JSON.stringify({ id: "t1", start }) });
    expect(toastOfFrame(t({ ok: false, reason: "start_refused", detail: "runner e2e-box is offline\nmore" }))).toEqual({ tone: "error", text: "runner e2e-box is offline" });
    expect(toastOfFrame(t({ ok: false, reason: "held" }))).toEqual({ tone: "warn", text: "The ticket was not started (held)" });
    expect(toastOfFrame(t({ ok: true, reason: "started" }))).toBeNull();
    expect(toastOfFrame(t({ ok: false, reason: "start_refused", detail: "x" }, "board:1"))).toBeNull();
  });
  it("reads a device push for this device only", () => {
    const env = (o: object) => ({ type: "notify_push", data: JSON.stringify({ id: "n1", title: "Done", body: "", level: "milestone", target: "all", at: 1, ...o }) });
    expect(devicePushOf(env({}), "desktop")).toEqual({ id: "n1", toast: { tone: "ok", text: "Done", detail: undefined }, sound: undefined });
    expect(devicePushOf(env({ target: "android" }), "desktop")).toBeNull();
    expect(devicePushOf(env({ soundType: "audio/ogg", soundData: "AAA=" }), "desktop")?.sound).toBe("data:audio/ogg;base64,AAA=");
    expect(devicePushOf({ type: "notify", data: "{}" }, "desktop")).toBeNull();
  });
});

describe("createOsNotifier (PARITY-AUDIT-1 #5)", () => {
  const push = (n: object): Envelope => ({ type: "notification_push", data: JSON.stringify(n) }) as Envelope;
  it("calls the host seam once for an OS-flagged notice, never for one without, and collapses repeats within 60 s", () => {
    const shown: OsNotice[] = [];
    let t = 0;
    const os = createOsNotifier((n) => shown.push(n), () => t);
    os.frame(push({ title: "Ticket done", channels: ["inApp", "os"], ticketId: "t1" }));
    expect(shown.length).toBe(1);
    expect(shown[0]).toMatchObject({ key: "ticket:t1", title: "Ticket done" });
    os.frame(push({ title: "fyi", channels: ["inApp"], ticketId: "t2" }));
    os.frame(push({ title: "fyi" }));
    expect(shown.length).toBe(1);
    t = 30_000;
    os.frame(push({ title: "Ticket done again", channels: ["inApp", "os"], ticketId: "t1" }));
    expect(shown.length).toBe(1);
    os.frame(push({ title: "Needs input", channels: ["os"], sessionId: "s1" }));
    expect(shown.length).toBe(2);
    t = 61_000;
    os.frame(push({ title: "Ticket failed", channels: ["os"], ticketId: "t1" }));
    expect(shown.length).toBe(3);
  });
  it("setting off: the daemon leaves `os` out of the channels, so nothing is raised", () => {
    const shown: OsNotice[] = [];
    createOsNotifier((n) => shown.push(n)).frame(push({ title: "Ticket done", channels: ["inApp"], ticketId: "t1" }));
    expect(shown).toEqual([]);
  });
});
