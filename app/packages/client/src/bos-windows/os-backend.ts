// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// VENDORED from BattalionOS battalion-os @ 957c38f (WM-5), crates/battalion-desktop/ui/kit/lib. Do not edit here: change it in BattalionOS and copy again.
// WM-5: `osBackend`, the window calls under BattalionOS. A page in an OS frame sends the bridge message
// `{bos: 1, id, cmd, args}` to its parent and gets `{bos: 1, id, ok, result | error}`; `os_win` events come the same way
// (WM-2). A page in its own `app-<id>`/`win-*` window passes its `invoke`/`listen` instead (tauriTransport).
import { parseWinEvent, type WinCall, type WindowsBackend } from "./backend";
import type { WinEvent } from "./types.gen";

export interface WinTransport {
  send(cmd: string, args: Record<string, unknown>): Promise<unknown>;
  /** Every message the OS sends this page. Returns the unsubscribe. */
  listen(fn: (message: unknown) => void): () => void;
}

interface MessageTarget {
  postMessage(message: unknown, targetOrigin: string): void;
}
interface MessageSource {
  addEventListener(type: "message", fn: (e: { source?: unknown; data: unknown }) => void): void;
  removeEventListener(type: "message", fn: (e: { source?: unknown; data: unknown }) => void): void;
}

/** The frame bridge: `parent` is the OS shell, `self` the page's own window. Only messages from `parent` count. */
export function frameTransport(parent: MessageTarget, self: MessageSource, opts: { timeoutMs?: number } = {}): WinTransport {
  let n = 0;
  const pending = new Map<string, { ok: (v: unknown) => void; fail: (e: Error) => void }>();
  const listeners = new Set<(m: unknown) => void>();
  const onMessage = (e: { source?: unknown; data: unknown }) => {
    if (e.source !== undefined && e.source !== parent) return;
    const d = e.data as { bos?: unknown; id?: unknown; ok?: unknown; result?: unknown; error?: unknown; event?: unknown } | null;
    if (!d || d.bos !== 1) return;
    if (d.event === undefined && typeof d.id === "string" && pending.has(d.id)) {
      const p = pending.get(d.id)!;
      pending.delete(d.id);
      if (d.ok === true) p.ok(d.result);
      else p.fail(new Error(typeof d.error === "string" ? d.error : "the OS refused the call"));
      return;
    }
    for (const l of listeners) l(d);
  };
  self.addEventListener("message", onMessage);
  return {
    send(cmd, args) {
      const id = `win-${++n}`;
      return new Promise((ok, fail) => {
        pending.set(id, { ok, fail });
        if (opts.timeoutMs)
          setTimeout(() => {
            if (pending.delete(id)) fail(new Error(`${cmd}: no answer from the OS`));
          }, opts.timeoutMs);
        parent.postMessage({ bos: 1, id, cmd, args }, "*");
      });
    },
    listen(fn) {
      listeners.add(fn);
      return () => listeners.delete(fn);
    },
  };
}

/** A page in its own OS window: Tauri's `invoke` and `listen("os://win")`, passed in so the kit imports no platform API. */
export function tauriTransport(
  invoke: (cmd: string, args: Record<string, unknown>) => Promise<unknown>,
  listen: (event: string, fn: (e: { payload: unknown }) => void) => () => void,
): WinTransport {
  return { send: invoke, listen: (fn) => listen("os://win", (e) => fn(e.payload)) };
}

export function osBackend(t: WinTransport): WindowsBackend {
  return {
    name: "os",
    call: (cmd: WinCall, args = {}) => t.send(cmd, args),
    onEvent(fn: (e: WinEvent) => void) {
      return t.listen((m) => {
        const e = parseWinEvent(m);
        if (e) fn(e);
      });
    },
  };
}
