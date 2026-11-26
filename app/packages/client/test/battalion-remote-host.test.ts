// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// AND-10: the page-only app on a phone pairs with the PC like the phone app (remote_save / remote_forget / remote_check), in the page.
import { describe, expect, it } from "vitest";
import type { FrameLink } from "../../../shell/src/host/battalion/frame";
import { battalionRemoteHost, loadRemote, osScanner, REMOTE_KEY, remoteCheck, SCAN_WAIT_MS, type KeyStore, type Probes } from "../../../shell/src/host/battalion/remote";

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

/** A desktop that answers `os_scan_code` with [answer] (ok result or an error) and records what the page sent. */
function scanDesk(answer: { ok: true; result: unknown } | { ok: false; error: string }) {
  const parent = {};
  const sent: { id: string; cmd: string }[] = [];
  let deliver: (d: unknown, s: unknown) => void = () => {};
  const link: FrameLink = {
    parent,
    post: (m) => {
      const r = m as { id: string; cmd: string };
      sent.push(r);
      queueMicrotask(() => deliver({ bos: 1, id: r.id, ...answer }, parent));
    },
    listen: (cb) => ((deliver = cb), () => {}),
  };
  return { link, sent };
}

describe("PHONE-SCAN-1: the page-only app scans with BattalionOS's scanner", () => {
  it("has a scanner that calls os_scan_code and returns the text", async () => {
    const d = scanDesk({ ok: true, result: "infantry://connect?url=ws%3A%2F%2F10.0.2.2%3A18991%2Fws&token=t" });
    const h = battalionRemoteHost("infantry-ai", d.link, memStore(), probes(true, true));
    expect(h.scanner).not.toBeNull();
    expect(h.scanner?.ownScreen).toBe(true);
    expect(await h.scanner!.scan()).toContain("infantry://connect");
    expect(d.sent.map((s) => s.cmd)).toEqual(["os_scan_code"]);
    expect(d.sent[0].id.startsWith("s")).toBe(true);
  });
  it("a cancel is null, quietly", async () => {
    const d = scanDesk({ ok: true, result: null });
    expect(await osScanner(d.link).scan()).toBeNull();
  });
  it("a refusal carries the OS's own reason", async () => {
    const d = scanDesk({ ok: false, error: "app 'infantry-ai' has no 'scan.code' permission in its manifest, so 'os_scan_code' is refused" });
    await expect(osScanner(d.link).scan()).rejects.toThrow("scan.code");
  });
  it("waits far longer than an ordinary call", () => {
    expect(SCAN_WAIT_MS).toBeGreaterThanOrEqual(5 * 60_000);
  });
});
