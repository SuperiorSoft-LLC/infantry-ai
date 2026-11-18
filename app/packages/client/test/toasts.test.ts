// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import { describe, expect, it } from "vitest";
import { TOASTS_SHOWN, createToasts, toastOfFrame } from "../src";

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
});
