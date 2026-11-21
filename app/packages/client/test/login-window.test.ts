// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The login window: the declared address carries the code, the watcher submits it once and closes the window, and the link seam
// picks the system opener in the app and window.open on the web.
import { afterEach, describe, expect, it, vi } from "vitest";
import { browserHost, externalOpener, friendlyLoginError, isExternalOnlyHost, loginCodeFromUrl, loginWatchList, watchLoginWindow, type LoginWindowHandlers } from "../src";

const DECLARED = ["https://platform.claude.com/oauth/code/callback", "https://console.anthropic.com/oauth/code/callback"];

describe("loginCodeFromUrl", () => {
  it("reads the code (and state) off a declared callback address", () => {
    expect(loginCodeFromUrl("https://platform.claude.com/oauth/code/callback?code=abc&state=xyz", DECLARED)).toBe("abc#xyz");
    expect(loginCodeFromUrl("https://console.anthropic.com/oauth/code/callback/?code=abc", DECLARED)).toBe("abc");
  });
  it("ignores other pages, look-alikes, plain http, a missing code and an undeclared harness", () => {
    expect(loginCodeFromUrl("https://platform.claude.com/oauth/authorize?code=abc", DECLARED)).toBeNull();
    expect(loginCodeFromUrl("https://platform.claude.com/oauth/code/callback-evil?code=abc", DECLARED)).toBeNull();
    expect(loginCodeFromUrl("https://evil.example/oauth/code/callback?code=abc", DECLARED)).toBeNull();
    expect(loginCodeFromUrl("http://platform.claude.com/oauth/code/callback?code=abc", DECLARED)).toBeNull();
    expect(loginCodeFromUrl("https://platform.claude.com/oauth/code/callback", DECLARED)).toBeNull();
    expect(loginCodeFromUrl("https://platform.claude.com/oauth/code/callback?code=abc", [])).toBeNull();
    expect(loginCodeFromUrl("https://platform.claude.com/oauth/code/callback?code=abc", undefined)).toBeNull();
    expect(loginCodeFromUrl("nonsense", DECLARED)).toBeNull();
  });
});

function fakeHost(opens = true) {
  const log = { handlers: null as LoginWindowHandlers | null, closed: 0, external: [] as string[], opened: [] as Array<[string, readonly string[]]> };
  return {
    log,
    openLoginWindow: async (url: string, watch: readonly string[], h: LoginWindowHandlers) => {
      log.opened.push([url, watch]);
      log.handlers = h;
      return opens ? { close: () => void log.closed++ } : null;
    },
    openExternal: async (url: string) => void log.external.push(url),
  };
}

describe("watchLoginWindow", () => {
  it("submits the code once, closes the window and gives the declared prefixes to the host", async () => {
    const h = fakeHost();
    const got: string[] = [];
    await watchLoginWindow(h, "https://claude.ai/login", DECLARED, (c) => got.push(c));
    expect(h.log.opened).toEqual([["https://claude.ai/login", DECLARED]]);
    h.log.handlers!.onUrl("https://platform.claude.com/oauth/authorize?x=1");
    expect(got).toEqual([]);
    h.log.handlers!.onUrl("https://platform.claude.com/oauth/code/callback?code=abc&state=s");
    h.log.handlers!.onUrl("https://platform.claude.com/oauth/code/callback?code=abc&state=s");
    expect(got).toEqual(["abc#s"]);
    expect(h.log.closed).toBe(1);
    expect(h.log.external).toEqual([]);
  });
  it("opens the system browser when no window opens, and when the host throws", async () => {
    const blocked = fakeHost(false);
    await watchLoginWindow(blocked, "https://claude.ai/login", DECLARED, () => undefined);
    expect(blocked.log.external).toEqual(["https://claude.ai/login"]);
    const broken = fakeHost();
    broken.openLoginWindow = async () => {
      throw new Error("no window");
    };
    await watchLoginWindow(broken, "https://claude.ai/login", DECLARED, () => undefined);
    expect(broken.log.external).toEqual(["https://claude.ai/login"]);
  });
});

describe("external-only hosts and friendly errors", () => {
  const GOOGLE = ["accounts.google.com"];
  it("a Google page closes the window, opens the same login page in the system browser and shows the hint once", async () => {
    const h = fakeHost();
    const got: string[] = [];
    let hinted = 0;
    await watchLoginWindow(h, "https://claude.ai/login", DECLARED, (c) => got.push(c), undefined, GOOGLE, () => hinted++);
    expect(h.log.opened[0]![1]).toEqual([...DECLARED, "https://accounts.google.com"]);
    h.log.handlers!.onUrl("https://accounts.google.com/o/oauth2/v2/auth?x=1");
    h.log.handlers!.onUrl("https://accounts.google.com/o/oauth2/v2/auth?x=2");
    expect(h.log.external).toEqual(["https://claude.ai/login"]);
    expect(h.log.closed).toBe(1);
    expect(hinted).toBe(1);
    expect(got).toEqual([]);
  });
  it("matches the exact host only, and never without a declaration", () => {
    expect(isExternalOnlyHost("https://accounts.google.com/x", GOOGLE)).toBe(true);
    expect(isExternalOnlyHost("https://accounts.google.com.evil.example/x", GOOGLE)).toBe(false);
    expect(isExternalOnlyHost("https://accounts.google.com/x", undefined)).toBe(false);
    expect(loginWatchList(undefined, undefined)).toEqual([]);
  });
  it("never shows the daemon's frame text, and drops a refusal when the login is already over", () => {
    expect(friendlyLoginError("secret_login refused: no sign-in is running; start it again", true)).toBeNull();
    const shown = friendlyLoginError("secret_login refused: no sign-in is running; start it again", false)!;
    expect(shown).not.toContain("secret_login");
    expect(friendlyLoginError("secret_login refused: paste the code the sign-in page shows", false)).toBe("paste the code the sign-in page shows");
  });
});

describe("openExternal", () => {
  afterEach(() => vi.unstubAllGlobals());
  it("uses the system opener in the app and the page's window.open elsewhere", async () => {
    const open = vi.fn();
    vi.stubGlobal("window", { open });
    const system = vi.fn(async (_url: string) => undefined);
    await externalOpener(() => true, system)("https://a.example/");
    expect(system).toHaveBeenCalledWith("https://a.example/");
    expect(open).not.toHaveBeenCalled();
    await externalOpener(() => false, system)("https://b.example/");
    expect(system).toHaveBeenCalledTimes(1);
    expect(open).toHaveBeenCalledWith("https://b.example/", "_blank", "noopener,noreferrer");
    await browserHost().openExternal("https://c.example/");
    expect(open).toHaveBeenLastCalledWith("https://c.example/", "_blank", "noopener,noreferrer");
  });
});
