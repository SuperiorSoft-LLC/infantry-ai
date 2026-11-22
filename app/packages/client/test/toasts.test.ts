// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import { describe, expect, it } from "vitest";
import { devicePushOf, TOASTS_SHOWN, createToasts, toastOfFrame } from "../src";

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
