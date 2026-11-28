// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// FLOAT-ADOPT-1: under BattalionOS a session pop-out is an OS float window (os_win_open kind float), not the inline box.
import { afterEach, describe, expect, it, vi } from "vitest";
import { createElement } from "react";
import { renderToString } from "react-dom/server";
import { createWindows, setPopoutWindows } from "@infantry/client";

const hostMock = vi.hoisted(() => ({ host: { os: null as unknown } }));
vi.mock("@/host", () => hostMock);
vi.mock("@/daemon/daemon", () => ({ useDaemon: () => null, daemonStore: { getState: () => ({}), subscribe: () => () => {} } }));
vi.mock("@/daemon/agents", () => ({ useAgent: () => ({ name: "Bob", sessionId: "s-1" }) }));
vi.mock("@/state/ui-state", () => ({ useUi: () => ({ popouts: ["s-1"], assistantOpen: false, closePopout: () => {} }) }));
vi.mock("./chat-popout", () => ({ ChatPopout: () => "INLINE-BOX", savedFloatBounds: () => ({ x: 10, y: 20, w: 420, h: 560 }) }));
vi.mock("./use-assistant", () => ({ useAssistantAsk: () => null, useAssistantId: () => null }));

function fakeOs(refuse = false) {
  let emit: (e: unknown) => void = () => {};
  const calls: [string, Record<string, unknown>][] = [];
  const ws = createWindows({
    name: "os",
    onEvent: (fn) => ((emit = fn as (e: unknown) => void), () => {}),
    async call(cmd, args = {}) {
      calls.push([cmd, args]);
      if (refuse) throw new Error("unknown command");
      return { wid: "w-1", kind: "float", state: "normal", bounds: { x: 10, y: 20, w: 420, h: 560 }, restored: false };
    },
  });
  return { ws, calls, emit: (e: unknown) => emit(e) };
}

afterEach(() => setPopoutWindows(null));

describe("FLOAT-ADOPT-1", () => {
  it("under the OS popOut opens kind float keyed session.<id> with the pop-out route; no inline box renders", async () => {
    hostMock.host.os = { appId: "infantry-ai" };
    const os = fakeOs();
    setPopoutWindows(os.ws);
    const { openOsFloat, PopoutHost } = await import("./popout-host");
    expect(renderToString(createElement(PopoutHost))).not.toContain("INLINE-BOX");
    const m = await openOsFloat("s-1", "Bob · s-1", () => {}, { pathname: "/ui/index.html", search: "?bos-app=infantry-ai" });
    expect(m).toBe("os");
    expect(os.calls[0]).toEqual(["os_win_open", { key: "session.s-1", kind: "float", title: "Bob · s-1", route: "/ui/index.html?bos-app=infantry-ai&popout=session%3As-1", bounds: { x: 10, y: 20, w: 420, h: 560 } }]);
  });

  it("a close event from the OS clears the popout state", async () => {
    hostMock.host.os = { appId: "infantry-ai" };
    const os = fakeOs();
    setPopoutWindows(os.ws);
    const { openOsFloat } = await import("./popout-host");
    const closed = vi.fn();
    await openOsFloat("s-1", "t", closed, { pathname: "/", search: "" });
    os.emit({ change: "closed", wid: "w-1" });
    expect(closed).toHaveBeenCalledTimes(1);
  });

  it("an old BOS without os_win falls back to the inline box", async () => {
    hostMock.host.os = { appId: "infantry-ai" };
    setPopoutWindows(fakeOs(true).ws);
    const { openOsFloat } = await import("./popout-host");
    expect(await openOsFloat("s-1", "t", () => {}, { pathname: "/", search: "" })).toBe("inline");
  });

  it("standalone stays inline", async () => {
    hostMock.host.os = null;
    const { PopoutHost } = await import("./popout-host");
    expect(renderToString(createElement(PopoutHost))).toContain("INLINE-BOX");
  });
});
