// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// BOS-17: the page in a frame of the OS window calls the OS by messages (the same app commands, the same results); in a window of its own
// it keeps the direct commands. A page draws no title bar, desktop or taskbar of its own under the OS.
import { afterEach, describe, expect, it, vi } from "vitest";
import { battalionFrameHost, frameAppId, frameCaller, type FrameLink } from "../../../shell/src/host/battalion/frame";
import { osNoticeOf, toastOfFrame } from "../src/toasts";

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
  it("only with a parent window and a valid app id in the flag (Tauri internals do not matter)", () => {
    expect(frameAppId({ framed: true, search: "?bos-app=infantry-ai" })).toBe("infantry-ai");
    expect(frameAppId({ framed: true, search: "?x=1&bos-app=infantry-ai" })).toBe("infantry-ai");
    expect(frameAppId({ framed: false, search: "?bos-app=infantry-ai" })).toBeNull();
    // BOS-26: Windows gives the frame the Tauri internals of the OS window; being framed still decides it (the env no longer carries a Tauri flag).
    expect(frameAppId({ framed: true, tauri: true, search: "?bos-app=infantry-ai" } as never)).toBe("infantry-ai");
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

describe("BOS-25: the native calls of the page, asked of the OS by name", () => {
  it("pickFolder is os_pick_folder with the title; cancelled is null", async () => {
    const d = desktop({ os_pick_folder: "C:\\work\\proj" });
    expect(await battalionFrameHost("infantry-ai", d.link).pickFolder("Project folder")).toBe("C:\\work\\proj");
    expect(d.sent.map((s) => [s.cmd, s.args])).toEqual([["os_pick_folder", { title: "Project folder" }]]);
    expect(await battalionFrameHost("infantry-ai", desktop({ os_pick_folder: null }).link).pickFolder("x")).toBeNull();
  });
  it("a refused folder dialog (no files.user permission) is an error the caller can show", async () => {
    await expect(battalionFrameHost("infantry-ai", desktop({}).link).pickFolder("x")).rejects.toThrow(/not available/);
  });
  it("openExternal is os_open_external with the url, and a refusal rejects (callers keep their catch)", async () => {
    const d = desktop({ os_open_external: null });
    await battalionFrameHost("infantry-ai", d.link).openExternal("https://example.com/a");
    expect(d.sent.map((s) => [s.cmd, s.args])).toEqual([["os_open_external", { url: "https://example.com/a" }]]);
    await expect(battalionFrameHost("infantry-ai", desktop({}).link).openExternal("file:///c:/x")).rejects.toThrow();
  });
  it("the sign-in window: the OS opens it; its addresses and its end come back to the page's handlers; close asks the OS", async () => {
    const d = desktop({ os_login_window_open: null, os_login_window_close: null });
    const h = battalionFrameHost("infantry-ai", d.link);
    const urls: string[] = [];
    let closed = 0;
    const win = await h.openLoginWindow("https://claude.ai/login", ["https://claude.ai/"], { onUrl: (u) => void urls.push(u), onClosed: () => void closed++ });
    expect(win).not.toBeNull();
    expect(d.sent.map((s) => [s.cmd, s.args])).toEqual([["os_login_window_open", { url: "https://claude.ai/login" }]]);
    d.deliver({ bos: 1, event: "os_login_url", url: "https://claude.ai/code?x=1" }, d.parent);
    d.deliver({ bos: 1, event: "os_login_url", url: "https://claude.ai/code?x=1" }, { name: "another window" });
    expect(urls).toEqual(["https://claude.ai/code?x=1"]); // only the desktop's events count
    d.deliver({ bos: 1, event: "os_login_closed" }, d.parent);
    expect(closed).toBe(1);
    win!.close();
    await Promise.resolve();
    expect(d.sent.at(-1)?.cmd).toBe("os_login_window_close");
  });
  it("a refused sign-in window rejects and later events reach nobody", async () => {
    const d = desktop({});
    const urls: string[] = [];
    await expect(battalionFrameHost("infantry-ai", d.link).openLoginWindow("https://x.test/", [], { onUrl: (u) => void urls.push(u), onClosed: () => {} })).rejects.toThrow(/not available/);
    d.deliver({ bos: 1, event: "os_login_url", url: "https://x.test/y" }, d.parent);
    expect(urls).toEqual([]);
  });
  it("the app's own window commands never leave the page; os_* commands go to the desktop", async () => {
    const d = desktop({ os_window_info: { label: "app-infantry-ai", app: "infantry-ai" } });
    const h = battalionFrameHost("infantry-ai", d.link);
    for (const c of ["update_check", "update_install", "cli_path_set", "ui_runner_set", "pairing_set", "remote_save", "hops_report", "perf_report"]) await expect(h.call(c)).rejects.toThrow(/not available under BattalionOS/);
    expect(d.sent).toEqual([]);
    expect(await h.call("os_window_info")).toMatchObject({ app: "infantry-ai" });
  });
});

// BOS-26: on Windows wry injects the Tauri init script into sub-frames, so the page in the OS frame sees `__TAURI_INTERNALS__`.
// The host the page picks must still be the frame link, never the Tauri window host.
describe("host picked by a page in an OS frame that has Tauri internals", () => {
  afterEach(() => (vi.unstubAllGlobals(), vi.resetModules()));
  async function pick(framed: boolean) {
    const posted: unknown[] = [];
    const win: Record<string, unknown> = {
      location: { search: "?bos-app=infantry-ai" },
      __TAURI_INTERNALS__: { metadata: { currentWindow: { label: "main" } }, invoke: () => Promise.reject(new Error("must not be called")) },
      addEventListener: () => {},
      removeEventListener: () => {},
    };
    win.parent = framed ? { postMessage: (m: unknown) => posted.push(m) } : win;
    vi.stubGlobal("window", win);
    vi.resetModules();
    const m = await import("../../../shell/src/host/index");
    return { m, posted };
  }
  it("framed: the frame host, isTauri false, and no os_ command is invoked directly", async () => {
    const { m, posted } = await pick(true);
    expect(m.isTauri).toBe(false);
    expect(m.host.os?.appId).toBe("infantry-ai");
    void m.host.endpoint().catch(() => undefined);
    expect(posted).toHaveLength(1);
    expect(posted[0]).toMatchObject({ bos: 1, cmd: "os_app_status" });
    await expect(m.host.call("start_runner")).rejects.toThrow(/not available under BattalionOS/);
  });
  it("not framed (a Tauri window of its own): still the Tauri side, not the frame host", async () => {
    const { m } = await pick(false);
    expect(m.isTauri).toBe(true);
  });
});

describe("BOS-NOTIFY-1: OS notifications under BattalionOS", () => {
  const frame = { type: "notification_push", requestId: null, data: JSON.stringify({ title: "Ticket done", body: "#7", channels: ["toast", "os"], ticketId: "t 7" }) } as never;

  it("a notice is os_notify with the key as route and a clean tag", async () => {
    const d = desktop({ os_notify: null });
    const h = battalionFrameHost("infantry-ai", d.link);
    const n = osNoticeOf(frame)!;
    await h.notify!.show(n);
    expect(d.sent.map((s) => [s.cmd, s.args])).toEqual([["os_notify", { title: "Ticket done", text: "#7", tag: "ticket:t_7", route: "ticket:t 7" }]]);
  });
  it("an older BattalionOS refuses: no error, and the in-app toast still comes from the frame", async () => {
    const d = desktop({});
    const h = battalionFrameHost("infantry-ai", d.link);
    await expect(h.notify!.show(osNoticeOf(frame)!)).resolves.toBeUndefined();
    expect(d.sent.map((s) => s.cmd)).toEqual(["os_notify"]);
    expect(toastOfFrame(frame)).toMatchObject({ text: "Ticket done", detail: "#7" });
  });
  it("a click (os_notify_click from the desktop) hands the route back; another window's message does not count", () => {
    const d = desktop({});
    const h = battalionFrameHost("infantry-ai", d.link);
    const keys: string[] = [];
    const off = h.notify!.onClick((k) => keys.push(k));
    d.deliver({ bos: 1, event: "os_notify_click", tag: "ticket:t_7", route: "ticket:t 7" }, d.parent);
    d.deliver({ bos: 1, event: "os_notify_click", route: "session:x" }, { name: "a stranger" });
    off();
    d.deliver({ bos: 1, event: "os_notify_click", route: "session:y" }, d.parent);
    expect(keys).toEqual(["ticket:t 7"]);
  });
});
