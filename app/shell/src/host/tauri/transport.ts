// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The Tauri transport: the socket is opened from Rust (tauri-plugin-websocket), so it sends no browser Origin and a token
// rides a plain `Authorization: Bearer` header, as any non-browser client does (CONTRACT §7).
import WebSocket from "@tauri-apps/plugin-websocket";
import { hop, type TransportFactory } from "@infantry/client";

export function tauriTransport(url: string, token?: string | null): TransportFactory {
  return () => {
    let ws: WebSocket | null = null;
    let closed = false;
    return {
      open(h) {
        const headers: Record<string, string> = token ? { Authorization: `Bearer ${token}` } : {};
        hop("tauri.connect.start");
        WebSocket.connect(url, { headers })
          .then((socket) => {
            hop("tauri.connect.open");
            if (closed) {
              void socket.disconnect().catch(() => {});
              return;
            }
            ws = socket;
            socket.addListener((msg) => {
              // The plugin delivers Text / Binary / Ping / Pong / Close messages, or a string when the stream errored.
              if (typeof msg === "string") return end(String(msg));
              if (msg.type === "Text") {
                hop("tauri.recv", { bytes: msg.data.length });
                h.onText(msg.data);
              }
              else if (msg.type === "Close") end(msg.data?.reason || `closed (${msg.data?.code ?? "no code"})`);
            });
            h.onOpen();
          })
          .catch((e) => end(e instanceof Error ? e.message : String(e)));
        function end(reason: string) {
          if (closed) return;
          closed = true;
          ws = null;
          h.onClose(reason);
        }
      },
      send(text) {
        hop("tauri.send", { type: /"type":"([^"]*)"/.exec(text)?.[1], bytes: text.length });
        void ws?.send(text).catch(() => {});
      },
      close() {
        closed = true;
        void ws?.disconnect().catch(() => {});
        ws = null;
      },
    };
  };
}
