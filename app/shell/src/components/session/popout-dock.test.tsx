// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// MIN-TASKBAR-1: under BattalionOS (any version: host.os set) the in-app dock is never drawn; standalone it is.
import { describe, expect, it, vi } from "vitest";
import { createElement } from "react";
import { renderToString } from "react-dom/server";

const hostMock = vi.hoisted(() => ({ host: { os: null as unknown } }));
vi.mock("@/host", () => hostMock);
vi.mock("@/daemon/daemon", () => ({ useDaemon: () => null, daemonStore: { getState: () => ({}), subscribe: () => () => {} } }));
vi.mock("@/daemon/agents", () => ({ useAgent: () => null }));
vi.mock("@/state/ui-state", () => ({ useUi: () => ({ popouts: [] }) }));
vi.mock("./chat-popout", () => ({ ChatPopout: () => null }));
vi.mock("./use-assistant", () => ({ useAssistantAsk: () => null, useAssistantId: () => null }));

describe("PopoutDock under BattalionOS", () => {
  it("renders nothing and never binds the local dock when host.os is set", async () => {
    hostMock.host.os = { appId: "infantry-ai" };
    const { PopoutDock } = await import("./popout-host");
    const el = PopoutDock();
    expect(el).toBeNull();
    expect(renderToString(createElement(PopoutDock))).toBe("");
  });

  it("standalone it is the local dock component", async () => {
    hostMock.host.os = null;
    const { PopoutDock } = await import("./popout-host");
    expect(PopoutDock()).not.toBeNull();
  });
});
