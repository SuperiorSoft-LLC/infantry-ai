// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// BOS-10c: under the OS the Desktop button calls os_show_desktop (it no longer closes the window) and the badge calls os_badge
// with one count and no id; neither warns in the console when the OS answers.
import { beforeEach, describe, expect, it, vi } from "vitest";

const invoke = vi.fn();
vi.mock("@tauri-apps/api/core", () => ({ invoke: (...a: unknown[]) => invoke(...a) }));
vi.mock("@tauri-apps/api/event", () => ({ listen: vi.fn() }));

import { badgeCount } from "../../../shell/src/host/battalion/os";
import { battalionHost } from "../../../shell/src/host/battalion";

beforeEach(() => {
  invoke.mockReset();
});

describe("os_show_desktop", () => {
  it("the Desktop button asks the OS to show the desktop and does not close the window", async () => {
    invoke.mockResolvedValue(undefined);
    await battalionHost("infantry-ai").os!.showDesktop();
    expect(invoke).toHaveBeenCalledTimes(1);
    expect(invoke).toHaveBeenCalledWith("os_show_desktop");
    expect(invoke).not.toHaveBeenCalledWith("os_close_app_window", expect.anything());
  });
  it("a refusal reaches the caller as an Error with the OS text", async () => {
    invoke.mockImplementation(() => Promise.reject("'os_show_desktop' is for an app window only"));
    const err = await battalionHost("infantry-ai").os!.showDesktop().then(() => null, (e: unknown) => e);
    expect(err).toBeInstanceOf(Error);
    expect((err as Error).message).toMatch(/app window only/);
  });
});

describe("os_badge", () => {
  it("sends one count and no id (the OS takes the app from the window), with no console warning", async () => {
    invoke.mockResolvedValue(undefined);
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
    await battalionHost("infantry-ai").os!.badge(2, 1);
    await battalionHost("infantry-ai").os!.badge(0, 0);
    expect(invoke.mock.calls).toEqual([["os_badge", { count: 3 }], ["os_badge", { count: 0 }]]);
    expect(warn).not.toHaveBeenCalled();
    warn.mockRestore();
  });
  it("counts working and waiting agents, never negative or fractional", () => {
    expect(badgeCount(2, 3)).toBe(5);
    expect(badgeCount(-1, 2.9)).toBe(2);
    expect(badgeCount(Number.NaN, 0)).toBe(0);
  });
});
