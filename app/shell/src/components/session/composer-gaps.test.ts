// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import { describe, expect, it } from "vitest";
import { attachLimitBytes, sendNowBlocked } from "./composer-gaps";

const MB = 1024 * 1024;

describe("composer attachment limits", () => {
  it("defaults to Kotlin's 10 MB images and 20 MB files", () => {
    expect(attachLimitBytes(true, 10, 20)).toBe(10 * MB);
    expect(attachLimitBytes(false, 10, 20)).toBe(20 * MB);
  });
  it("follows the settings, and falls back on an invalid value", () => {
    expect(attachLimitBytes(true, 3, 20)).toBe(3 * MB);
    expect(attachLimitBytes(false, 10, 50)).toBe(50 * MB);
    expect(attachLimitBytes(false, 10, 0)).toBe(20 * MB);
    expect(attachLimitBytes(true, 2.5, 20)).toBe(10 * MB);
  });
});

describe("Send now (mid-turn force)", () => {
  it("is greyed only when the harness reports it cannot take input mid-turn and a turn runs", () => {
    expect(sendNowBlocked(false, true)).toMatch(/cannot take a message mid-turn/);
    expect(sendNowBlocked(false, false)).toBeNull();
    expect(sendNowBlocked(true, true)).toBeNull();
  });
  it("unknown capability keeps today's behaviour", () => {
    expect(sendNowBlocked(undefined, true)).toBeNull();
    expect(sendNowBlocked(null, true)).toBeNull();
  });
});

describe("chat.composer.actions slot", () => {
  it("draws nothing when no plugin fills it", async () => {
    const { createElement } = await import("react");
    const { renderToString } = await import("react-dom/server");
    const { PluginSlot } = await import("@/plugins/web-plugins");
    expect(renderToString(createElement(PluginSlot, { fills: [], frame: "badge" }))).toBe("");
  });
});
