// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// AND-10: the page-only app on a phone pairs with the PC like the phone app (remote_save / remote_forget / remote_check), in the page.
import { describe, expect, it } from "vitest";
import type { FrameLink } from "../../../shell/src/host/battalion/frame";
import { battalionRemoteHost, loadRemote, REMOTE_KEY, remoteCheck, type KeyStore, type Probes } from "../../../shell/src/host/battalion/remote";

function memStore(): KeyStore & { m: Map<string, string> } {
  const m = new Map<string, string>();
  return { m, getItem: (k) => m.get(k) ?? null, setItem: (k, v) => void m.set(k, v), removeItem: (k) => void m.delete(k) };
}

/** A desktop that answers every os_* command with `true`. */
function desktop(): FrameLink {
  const parent = {};
  let deliver: (d: unknown, s: unknown) => void = () => {};
  return {
    parent,
    post: (m) => queueMicrotask(() => deliver({ bos: 1, id: (m as { id: string }).id, ok: true, result: true }, parent)),
    listen: (cb) => ((deliver = cb), () => {}),
  };
}

const probes = (health: boolean, socket: boolean): Probes => ({
  health: () => (health ? Promise.resolve() : Promise.reject(new Error("no route"))),
  socket: async () => socket,
});

describe("the remote page host", () => {
  it("is a phone with no pairing until one is saved, then opens the saved PC with its token", async () => {
    const s = memStore();
    const h = battalionRemoteHost("infantry-ai", desktop(), s, probes(true, true));
    expect(h.kind).toBe("phone");
    expect(await h.endpoint()).toMatchObject({ url: "", token: null, mode: "remote" });
    expect(await h.call("remote_save", { url: "http://10.0.2.2:18991", token: "t0k", name: "PC" })).toBe("ws://10.0.2.2:18991/ws");
    expect(await h.endpoint()).toMatchObject({ url: "ws://10.0.2.2:18991/ws", token: "t0k", name: "PC", mode: "remote" });
    expect(await h.call("remote_check")).toBe("ok");
    await h.call("remote_forget");
    expect(s.m.has(REMOTE_KEY)).toBe(false);
  });

  it("still sends os_* commands to the desktop and refuses the standalone app's own commands", async () => {
    const h = battalionRemoteHost("infantry-ai", desktop(), memStore(), probes(true, true));
    expect(await h.call("os_badge", { count: 1 })).toBe(true);
    await expect(h.call("update_check")).rejects.toThrow(/not available under BattalionOS/);
  });

  it("refuses a pairing with no address", async () => {
    const h = battalionRemoteHost("infantry-ai", desktop(), memStore(), probes(true, true));
    await expect(h.call("remote_save", { url: " " })).rejects.toThrow(/no daemon address/);
  });
});

describe("remote_check in the page", () => {
  const saved = { url: "ws://pc:1/ws", token: "x", name: null };
  it("follows the phone app's rule", async () => {
    expect(await remoteCheck(null, probes(true, true))).toBe("unreachable");
    expect(await remoteCheck(saved, probes(false, true))).toBe("unreachable");
    expect(await remoteCheck(saved, probes(true, false))).toBe("refused");
    expect(await remoteCheck(saved, probes(true, true))).toBe("ok");
  });

  it("reads a damaged saved pairing as none", () => {
    const s = memStore();
    s.setItem(REMOTE_KEY, "{not json");
    expect(loadRemote(s)).toBeNull();
    s.setItem(REMOTE_KEY, JSON.stringify({ url: "" }));
    expect(loadRemote(s)).toBeNull();
  });
});
