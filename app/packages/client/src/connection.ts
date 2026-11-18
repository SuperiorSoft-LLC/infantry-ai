// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// One daemon link: a transport (browser WebSocket, or the Tauri plugin's Rust-side socket), reconnect with backoff,
// and the envelope codec. It knows nothing of sessions; the store does.
import { decodeEnvelope, encodeEnvelope, type Envelope } from "./wire";

/** The reconnect delays, in ms, the Kotlin client uses (the last repeats). Overridable per client. */
export const DEFAULT_BACKOFF_MS: readonly number[] = [250, 500, 1000, 2000, 4000, 8000];

export interface TransportHandlers {
  onOpen(): void;
  onText(text: string): void;
  onClose(reason: string): void;
}

/** A text-frame socket. `open` is called once per instance; a closed transport is thrown away. */
export interface Transport {
  open(h: TransportHandlers): void;
  send(text: string): void;
  close(): void;
}

export type TransportFactory = () => Transport;

/**
 * A browser client's bearer, sent as a WebSocket subprotocol (CONTRACT §7, WEB-1):
 * `infantry.bearer.<base64url(token), no padding>`, offered next to the plain `infantry` protocol.
 */
export function bearerSubprotocol(token: string): string {
  const bytes = new TextEncoder().encode(token);
  let bin = "";
  for (const b of bytes) bin += String.fromCharCode(b);
  return `infantry.bearer.${btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "")}`;
}

/** The browser's own WebSocket. Same-origin (the daemon or the dev proxy serves the page); a token rides the subprotocol. */
export function browserTransport(url: string, token?: string | null): TransportFactory {
  return () => {
    let ws: WebSocket | null = null;
    return {
      open(h) {
        ws = token ? new WebSocket(url, ["infantry", bearerSubprotocol(token)]) : new WebSocket(url);
        ws.onopen = () => h.onOpen();
        ws.onmessage = (m) => typeof m.data === "string" && h.onText(m.data);
        ws.onclose = (e) => h.onClose(e.reason || `closed (${e.code})`);
        ws.onerror = () => {
          /* onclose follows with the reason */
        };
      },
      send(text) {
        ws?.send(text);
      },
      close() {
        ws?.close();
      },
    };
  };
}

export type LinkState = "connecting" | "open" | "closed";

export interface ConnectionOptions {
  transport: TransportFactory;
  onEnvelope(env: Envelope): void;
  onState(state: LinkState, detail: { attempt: number; reason?: string }): void;
  backoffMs?: readonly number[];
}

export class DaemonConnection {
  private transport: Transport | null = null;
  private state: LinkState = "closed";
  private attempt = 0;
  private timer: ReturnType<typeof setTimeout> | null = null;
  private stopped = true;
  private counter = 0;
  private readonly backoff: readonly number[];

  constructor(private readonly opts: ConnectionOptions) {
    this.backoff = opts.backoffMs && opts.backoffMs.length > 0 ? opts.backoffMs : DEFAULT_BACKOFF_MS;
  }

  get linkState(): LinkState {
    return this.state;
  }

  start(): void {
    this.stopped = false;
    this.dial();
  }

  stop(): void {
    this.stopped = true;
    if (this.timer) clearTimeout(this.timer);
    this.timer = null;
    this.transport?.close();
    this.transport = null;
    this.setState("closed", "stopped");
  }

  /** Send one frame; false when the link is not open (the caller decides whether to hold it). */
  send(env: Envelope): boolean {
    if (this.state !== "open" || !this.transport) return false;
    this.transport.send(encodeEnvelope(env));
    return true;
  }

  /** A client message id for a prompt: unique per message, so a resend is answered `duplicate`, never run twice. */
  newClientId(): string {
    this.counter += 1;
    return `send:${Date.now().toString(36)}-${this.counter.toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
  }

  private dial(): void {
    if (this.stopped) return;
    this.setState("connecting");
    const t = this.opts.transport();
    this.transport = t;
    let closed = false;
    try {
      t.open({
        onOpen: () => {
          if (this.transport !== t) return;
          this.attempt = 0;
          this.setState("open");
        },
        onText: (text) => {
          if (this.transport !== t) return;
          const env = decodeEnvelope(text);
          if (env) this.opts.onEnvelope(env);
        },
        onClose: (reason) => {
          if (closed || this.transport !== t) return;
          closed = true;
          this.transport = null;
          this.setState("closed", reason);
          this.retry();
        },
      });
    } catch (e) {
      this.transport = null;
      this.setState("closed", e instanceof Error ? e.message : String(e));
      this.retry();
    }
  }

  private retry(): void {
    if (this.stopped) return;
    const delay = this.backoff[Math.min(this.attempt, this.backoff.length - 1)];
    this.attempt += 1;
    this.timer = setTimeout(() => {
      this.timer = null;
      this.dial();
    }, delay);
  }

  private setState(s: LinkState, reason?: string): void {
    this.state = s;
    this.opts.onState(s, { attempt: this.attempt, reason });
  }
}
