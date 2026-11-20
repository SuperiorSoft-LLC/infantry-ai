// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The bootstrap store (INSTALLER-1): it reads the `bootstrap` part of `boot_progress`, follows a change, and leaves a daemon
// that reports none alone.
import { describe, expect, it } from "vitest";
import { bootstrapOf, createBootstrapStore } from "../src/stores/bootstrap";
import type { Envelope } from "../src/wire";

function link() {
  const sent: Envelope[] = [];
  const taps = new Set<(e: Envelope) => void>();
  return {
    sent,
    sendFrame: (e: Envelope) => (sent.push(e), true),
    onFrame: (l: (e: Envelope) => void) => (taps.add(l), () => void taps.delete(l)),
    push: (e: Envelope) => taps.forEach((t) => t(e)),
  };
}

const report = (bootstrap: unknown) => JSON.stringify({ ready: true, current: "ready", label: "Ready", steps: [], bootstrap });

describe("bootstrapOf", () => {
  it("reads the plugin, the mode and the error", () => {
    expect(bootstrapOf(report({ plugin: "Boot_Plugin", active: true, error: null }))).toEqual({ plugin: "Boot_Plugin", active: true, error: null });
    expect(bootstrapOf(report({ plugin: "Boot_Plugin", active: false, error: "failed: no" }))).toEqual({ plugin: "Boot_Plugin", active: false, error: "failed: no" });
    expect(bootstrapOf(report({ active: false }))).toEqual({ plugin: null, active: false, error: null });
  });

  it("is null for a report with none, and for anything that is not a report", () => {
    expect(bootstrapOf(JSON.stringify({ ready: true }))).toBeNull();
    expect(bootstrapOf("not json")).toBeNull();
    expect(bootstrapOf(report("nope"))).toBeNull();
  });
});

describe("the bootstrap store", () => {
  it("asks for the report and follows every change of it", () => {
    const l = link();
    const s = createBootstrapStore(l);
    expect(s.getState().info).toBeNull();
    s.getState().refresh();
    expect(l.sent[0]).toMatchObject({ type: "boot_progress", data: "{}" });
    l.push({ type: "boot_progress", data: report({ plugin: "P", active: true, error: null }) });
    expect(s.getState().info?.active).toBe(true);
    l.push({ type: "boot_progress", data: report({ plugin: "P", active: false, error: null }) });
    expect(s.getState().info?.active).toBe(false);
  });

  it("ignores other frames and a report with no bootstrap part", () => {
    const l = link();
    const s = createBootstrapStore(l);
    l.push({ type: "plugins_changed", data: "{}" });
    l.push({ type: "boot_progress", data: JSON.stringify({ ready: false }) });
    expect(s.getState().info).toBeNull();
  });
});
