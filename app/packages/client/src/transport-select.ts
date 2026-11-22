// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// PERF: which socket the Tauri window uses to reach the daemon. `direct` is the webview's own WebSocket (no per-frame IPC
// hop, as in a browser); `plugin` is tauri-plugin-websocket (every frame a plugin IPC message), kept as the fallback.
import type { Transport, TransportFactory } from "./connection";

export type TransportChoice = "direct" | "plugin";

/** The setting value (`INFANTRY_UI_TRANSPORT`, handed over by the app's `endpoint`): anything but `plugin` is direct. */
export function parseTransportSetting(v: string | null | undefined): TransportChoice {
  return v?.trim().toLowerCase() === "plugin" ? "plugin" : "direct";
}

/**
 * The first choice for [url] from the page. A page served over https (Android's `https://tauri.localhost`) may not open a
 * cleartext `ws:` socket (mixed content), so it keeps the plugin; a `wss:` url or an http page goes direct by default.
 */
export function chooseTransport(setting: TransportChoice, url: string, pageProtocol: string): TransportChoice {
  if (setting === "plugin") return "plugin";
  if (pageProtocol === "https:" && /^ws:/i.test(url)) return "plugin";
  return "direct";
}

/**
 * Direct first, and the plugin when the direct socket fails to open (the daemon refused the Origin, a CSP block): a
 * transport that closes before it ever opened is replaced, once, by the fallback for the rest of this factory's life.
 */
export function withFallback(direct: TransportFactory, plugin: TransportFactory, onFallback?: (reason: string) => void): TransportFactory {
  let useFallback = false;
  return () => {
    if (useFallback) return plugin();
    const t = direct();
    let opened = false;
    let inner: Transport | null = null;
    return {
      open(h) {
        t.open({
          onOpen: () => {
            opened = true;
            h.onOpen();
          },
          onText: h.onText,
          onClose: (reason) => {
            if (!opened && !useFallback) {
              useFallback = true;
              onFallback?.(reason);
              inner = plugin();
              inner.open(h);
              return;
            }
            h.onClose(reason);
          },
        });
      },
      send: (text) => (inner ?? t).send(text),
      close: () => (inner ?? t).close(),
    };
  };
}
