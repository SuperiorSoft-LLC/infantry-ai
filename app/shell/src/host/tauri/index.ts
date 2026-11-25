// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// HC-1: the Tauri app as this window's [Host] (desktop and phone). The only folder of the app that imports `@tauri-apps`
// (packages/client/test/tauri-import-guard.test.ts): everything else asks the host.
import { invoke } from "@tauri-apps/api/core";
import { getVersion } from "@tauri-apps/api/app";
import { listen } from "@tauri-apps/api/event";
import { getCurrentWindow } from "@tauri-apps/api/window";
import { open } from "@tauri-apps/plugin-dialog";
import { openUrl } from "@tauri-apps/plugin-opener";
import { browserHost, browserTransport, chooseTransport, daemonGetRefusal, parseTransportSetting, withFallback, externalOpener, type Host, type HostEndpoint, type HostWindow, type QrScanner } from "@infantry/client";
import { tauriTransport } from "./transport";
import { embeddedRunner } from "./embedded";

const message = (e: unknown) => (e instanceof Error ? e.message : typeof e === "string" ? e : (JSON.stringify(e) ?? String(e)));

/** The desktop window's frame buttons (window decorations are off; capabilities/default.json grants these three). */
const frame: HostWindow = {
  minimize: () => void getCurrentWindow().minimize(),
  toggleMaximize: () => void getCurrentWindow().toggleMaximize(),
  close: () => void getCurrentWindow().close(),
};

/** The phone's camera (tauri-plugin-barcode-scanner, mobile only, so loaded on first use). */
const scanner: QrScanner = {
  async scan() {
    const bs = await import("@tauri-apps/plugin-barcode-scanner");
    let perm = await bs.checkPermissions();
    if (perm !== "granted") perm = await bs.requestPermissions();
    if (perm !== "granted") throw new Error("Camera access was refused. Allow the camera in the phone's app settings, or paste the pairing link below.");
    try {
      const r = await bs.scan({ formats: [bs.Format.QRCode], windowed: true });
      return r?.content ?? null;
    } catch (e) {
      // The plugin rejects with a plain string or object, not an Error.
      const msg = message(e);
      if (/cancel/i.test(msg)) return null;
      throw new Error(`The camera could not start (${msg}). Paste the pairing link below instead.`);
    } finally {
      await bs.cancel().catch(() => undefined);
    }
  },
  async cancel() {
    const bs = await import("@tauri-apps/plugin-barcode-scanner");
    await bs.cancel().catch(() => undefined);
  },
};

const browser = browserHost();

export function tauriHost(): Host {
  // A phone build connects to a remote daemon and runs none; its `endpoint` says mode `remote` (mobile.rs), which settles
  // it before the first render. Until then the WebView's platform is the guess.
  let setting = parseTransportSetting(null);
  let phone = /Android|iPhone|iPad|iPod/i.test(navigator.userAgent);
  return {
    get kind() {
      return phone ? "phone" : "desktop";
    },
    get window() {
      return phone ? null : frame;
    },
    get embedded() {
      return phone ? null : embeddedRunner;
    },
    get scanner() {
      return phone ? scanner : null;
    },
    version: () => getVersion(),
    async endpoint() {
      const e = await invoke<HostEndpoint>("endpoint");
      // A missing mode never turns a phone into a desktop: only an answer that names another mode does.
      phone = e.mode ? e.mode === "remote" : phone;
      setting = parseTransportSetting(e.transport);
      return e;
    },
    // PERF: the webview's own WebSocket (no plugin IPC message per frame); the plugin when the setting says so, on an https
    // page (cleartext ws is mixed content there), or when the direct socket cannot open.
    transport: (url, token) =>
      chooseTransport(setting, url, location.protocol) === "plugin"
        ? tauriTransport(url, token)
        : withFallback(browserTransport(url, token), tauriTransport(url, token), (r) => console.warn(`direct daemon socket failed (${r}); using the plugin`)),
    call: <T>(command: string, args?: Record<string, unknown>) => invoke<T>(command, args),
    listen: <T>(event: string, handler: (payload: T) => void) => listen<T>(event, (e) => handler(e.payload)),
    async pickFolder(title) {
      const f = await open({ directory: true, multiple: false, title });
      return typeof f === "string" ? f : null;
    },
    async daemonGet(path, at) {
      // The app's own process reads it from the daemon this window is connected to, with its token (src-tauri
      // daemon_http.rs): the page itself may load no daemon origin (tauri.conf.json CSP).
      const refused = daemonGetRefusal(path);
      if (refused) throw new Error(refused);
      try {
        return await invoke<ArrayBuffer>("daemon_get", { url: at.url, token: at.token, path });
      } catch (e) {
        throw new Error(message(e));
      }
    },
    reload: () => location.reload(),
    // The system browser on the desktop; the phone keeps the page's own tab.
    openExternal: externalOpener(() => !phone, openUrl),
    async openLoginWindow(url, watch, handlers) {
      // Desktop only: the app opens a window of its own (src-tauri login_window.rs) and tells us the watched addresses it
      // reaches. Anywhere else the page opens a popup and the caller keeps its paste field.
      if (phone) return browser.openLoginWindow(url, watch, handlers);
      const off: Array<() => void> = [];
      const done = () => off.splice(0).forEach((f) => f());
      off.push(await listen<string>("login-window-url", (e) => handlers.onUrl(e.payload)));
      off.push(
        await listen("login-window-closed", () => {
          done();
          handlers.onClosed();
        }),
      );
      try {
        await invoke("login_window_open", { url });
      } catch (e) {
        done();
        throw new Error(message(e));
      }
      return { close: () => void invoke("login_window_close").catch(() => undefined) };
    },
  };
}
