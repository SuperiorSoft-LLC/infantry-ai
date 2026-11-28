// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// SESSION-NAME-1: the card shows the session's display name; blank falls back to the role name, the role stays.
import { describe, expect, it } from "vitest";
import { applyToView, Frame, type Envelope, type SessionView } from "@infantry/client";
import { COMPACTING_LABEL, toAgent } from "./agents";

const view = (displayName?: string | null): SessionView =>
  ({
    record: { id: "s-1234567890", provider: "claude", workingDir: "/w", spawnedAt: 0, lastHeartbeat: 0, state: "IDLE", role: "pm", displayName },
    items: [], cursor: 0, status: null, usage: {}, lastLine: "", activityAt: 0, activityLog: [],
  }) as unknown as SessionView;

describe("toAgent display name", () => {
  it("shows the display name and keeps the role", () => {
    const a = toAgent(view("PM - releases"), {}, {});
    expect(a.name).toBe("PM - releases");
    expect(a.roleId).toBe("pm");
  });
  it("falls back to the role name when blank", () => {
    expect(toAgent(view(""), {}, {}).name).toBe("Pm");
    expect(toAgent(view(null), {}, {}).name).toBe("Pm");
  });
});

// STUCK-RUNNING-1: an idle session with background tasks reads idle, never Running.
describe("toAgent background at rest", () => {
  const bgView = (state: string, turnActive: boolean) =>
    ({
      record: { id: "s-1", provider: "claude", workingDir: "/w", spawnedAt: 0, lastHeartbeat: 0, state, turnActive, backgroundTasks: [{ id: "b1", label: "sleep 600", startedAt: 0 }, { id: "m1", label: "Monitor", startedAt: 0 }] },
      items: [], cursor: 0, status: null, usage: {}, lastLine: "", activityAt: 0, activityLog: [],
    }) as unknown as SessionView;
  it("idle with tasks: status idle, line 'idle · 2 background'", () => {
    const a = toAgent(bgView("IDLE", false), {}, {});
    expect(a.status).toBe("idle");
    expect(a.statusLine).toBe("idle · 2 background");
    expect(a.backgroundTasks?.length).toBe(2);
  });
  it("a running turn still reads working", () => {
    expect(toAgent(bgView("BUSY", true), {}, {}).status).toBe("working");
  });
});

// PARITY-AUDIT-1 #18: the compacting status shows on the card line and clears on its end or the turn's finish.
describe("toAgent compacting", () => {
  const base = () =>
    ({
      record: { id: "s-c", provider: "claude", workingDir: "/w", spawnedAt: 0, lastHeartbeat: 0, state: "BUSY", turnActive: true },
      items: [], cursor: 0, status: null, usage: {}, lastLine: "", activityAt: 0, activityLog: [],
    }) as unknown as SessionView;
  const frame = (type: string, data: unknown, sequence: number) => ({ type, data: JSON.stringify(data), sequence, sessionId: "s-c" }) as unknown as Envelope;
  it("shows while compacting and clears on the end status", () => {
    let v = applyToView(base(), [frame(Frame.STATUS, { state: "BUSY", detail: "", compacting: true }, 1)]);
    expect(toAgent(v, {}, {}).statusLine).toBe(COMPACTING_LABEL);
    v = applyToView(v, [frame(Frame.STATUS, { state: "BUSY", detail: "", tool: "Bash", activity: "ls" }, 2)]);
    expect(toAgent(v, {}, {}).statusLine).toBe(COMPACTING_LABEL);
    v = applyToView(v, [frame(Frame.STATUS, { state: "BUSY", detail: "", compacting: false }, 3)]);
    expect(toAgent(v, {}, {}).statusLine).not.toBe(COMPACTING_LABEL);
  });
  it("a finished turn clears it", () => {
    let v = applyToView(base(), [frame(Frame.STATUS, { state: "BUSY", detail: "", compacting: true }, 1)]);
    v = applyToView(v, [frame(Frame.FINISHED, { outcome: "COMPLETED" }, 2)]);
    expect(toAgent(v, {}, {}).statusLine).not.toBe(COMPACTING_LABEL);
  });
});
