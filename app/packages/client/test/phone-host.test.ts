// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// PHONE-1: a phone build never becomes the desktop path, also when the app's endpoint answer names no mode.
import { describe, expect, it, vi } from "vitest";

let answer: Record<string, unknown> = {};
vi.mock("@tauri-apps/api/core", () => ({ invoke: async () => answer }));
vi.mock("@tauri-apps/api/app", () => ({ getVersion: async () => "0.8.1" }));
vi.mock("@tauri-apps/api/event", () => ({ listen: async () => () => {} }));
vi.mock("@tauri-apps/api/window", () => ({ getCurrentWindow: () => ({}) }));
vi.mock("@tauri-apps/plugin-dialog", () => ({ open: async () => null }));
vi.mock("@tauri-apps/plugin-opener", () => ({ openUrl: async () => {} }));

const { tauriHost } = await import("../../../shell/src/host/tauri");

describe("the phone host", () => {
  it("a missing mode keeps a phone a phone: no embedded runner, no desktop window, a scanner", async () => {
    vi.stubGlobal("navigator", { userAgent: "Mozilla/5.0 (Linux; Android 14; SM-F936B) AppleWebKit/537.36" });
    vi.stubGlobal("location", { protocol: "http:" });
    const h = tauriHost();
    answer = { url: "ws://100.64.0.1:18990/ws", token: null, stress: false };
    await h.endpoint();
    expect(h.kind).toBe("phone");
    expect(h.embedded).toBeNull();
    expect(h.window).toBeNull();
    expect(h.scanner).not.toBeNull();
  });
  it("mode remote is a phone; another named mode on a desktop WebView stays a desktop", async () => {
    vi.stubGlobal("navigator", { userAgent: "Mozilla/5.0 (Windows NT 10.0; Win64; x64)" });
    const d = tauriHost();
    answer = { url: "ws://127.0.0.1:1/ws", token: null, stress: false, mode: "daemon" };
    await d.endpoint();
    expect(d.kind).toBe("desktop");
    answer = { url: "", token: null, stress: false, mode: "remote" };
    await d.endpoint();
    expect(d.kind).toBe("phone");
  });
});
