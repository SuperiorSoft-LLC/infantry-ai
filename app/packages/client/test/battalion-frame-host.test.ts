// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// BOS-17: the page in a frame of the OS window calls the OS by messages (the same app commands, the same results); in a window of its own
// it keeps the direct commands. A page draws no title bar, desktop or taskbar of its own under the OS.
import { describe, expect, it } from "vitest";
import { battalionFrameHost, frameAppId, frameCaller, type FrameLink } from "../../../shell/src/host/battalion/frame";

/** A desktop that answers like the shell: `answers[cmd]` or a refusal; every request is recorded. */
function desktop(answers: Record<string, unknown>) {
  const parent = { name: "the desktop" };
  const sent: { id: string; cmd: string; args?: unknown }[] = [];
  let deliver: (data: unknown, source: unknown) => void = () => {};
  const link: FrameLink = {
    parent,
    post: (m) => {
      const r = m as { id: string; cmd: string; args?: unknown };
      sent.push(r);
      queueMicrotask(() => deliver(cmd(r), parent));
    },
    listen: (cb) => ((deliver = cb), () => {}),
  };
  const cmd = (r: { id: string; cmd: string }) => (r.cmd in answers ? { bos: 1, id: r.id, ok: true, result: answers[r.cmd] } : { bos: 1, id: r.id, ok: false, error: `'${r.cmd}' is not available to an app's page` });
  return { link, sent, parent, deliver: (d: unknown, s: unknown) => deliver(d, s) };
}

describe("is this page in an OS frame", () => {
  it("only with a parent window, a valid app id in the flag, and no Tauri window", () => {
    expect(frameAppId({ framed: true, search: "?bos-app=infantry-ai" })).toBe("infantry-ai");
    expect(frameAppId({ framed: true, search: "?x=1&bos-app=infantry-ai" })).toBe("infantry-ai");
    expect(frameAppId({ framed: false, search: "?bos-app=infantry-ai" })).toBeNull();
    expect(frameAppId({ framed: true, tauri: true, search: "?bos-app=infantry-ai" })).toBeNull();
    for (const s of ["", "?bos-app=", "?bos-app=A", "?bos-app=x", "?bos-app=a/b", "?other=infantry-ai"]) expect(frameAppId({ framed: true, search: s }), s).toBeNull();
  });
});

describe("the frame host", () => {
  it("draws no frame buttons and carries the os commands", () => {
    const h = battalionFrameHost("infantry-ai", desktop({}).link);
    expect(h.window).toBeNull();
    expect(h.embedded).toBeNull();
    expect(h.os?.appId).toBe("infantry-ai");
  });
  it("the endpoint is the app's own socket from os_app_status, asked without an id", async () => {
    const d = desktop({ os_app_status: { id: "infantry-ai", state: "running", url: "http://127.0.0.1:4242/" } });
    const e = await battalionFrameHost("infantry-ai", d.link).endpoint();
    expect(e).toMatchObject({ url: "ws://127.0.0.1:4242/ws", mode: "daemon", token: null });
    expect(d.sent).toMatchObject([{ cmd: "os_app_status" }]);
    expect(JSON.stringify(d.sent[0])).not.toContain("infantry-ai"); // the desktop knows the app from the frame
  });
  it("says why when the app is not running", async () => {
    const d = desktop({ os_app_status: { id: "infantry-ai", state: "failed", url: null, lastError: "port busy" } });
    await expect(battalionFrameHost("infantry-ai", d.link).endpoint()).rejects.toThrow(/port busy/);
  });
  it("Desktop is os_show_desktop and the badge is the working + needs-input count", async () => {
    const d = desktop({ os_show_desktop: null, os_badge: null });
    const h = battalionFrameHost("infantry-ai", d.link);
    await h.os!.showDesktop();
    await h.os!.badge(2, 3);
    expect(d.sent.map((s) => [s.cmd, s.args])).toEqual([["os_show_desktop", undefined], ["os_badge", { count: 5 }]]);
  });
  it("a command the desktop refuses comes back as an error, not a hang", async () => {
    const d = desktop({});
    await expect(battalionFrameHost("infantry-ai", d.link).os!.showDesktop()).rejects.toThrow(/not available/);
  });
});

describe("answers", () => {
  it("only the parent window may answer", async () => {
    const parent = {};
    let deliver: (d: unknown, s: unknown) => void = () => {};
    const link: FrameLink = { parent, post: () => {}, listen: (cb) => ((deliver = cb), () => {}) };
    const call = frameCaller(link, 30);
    const p = call("os_window_info");
    deliver({ bos: 1, id: "r1", ok: true, result: { app: "evil" } }, { name: "some other window" });
    await expect(p).rejects.toThrow(/did not answer/);
    const q = call("os_window_info");
    deliver({ bos: 1, id: "r2", ok: true, result: { app: "infantry-ai" } }, parent);
    await expect(q).resolves.toEqual({ app: "infantry-ai" });
  });
});
