// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The shared request helper of the knowledge, roles and library stores: a frame sent with its own request id, the ids in
// flight remembered, so an `error` answer to one of them (`<type> refused: ...`) is this store's refusal and no other's.
import type { Envelope } from "../wire";

export interface FrameLink {
  sendFrame(env: Envelope): boolean;
  onFrame(listener: (env: Envelope) => void): () => void;
}

export interface Requester {
  /** Sends [type]; false (and [onDown] called) when the link is down. */
  send(type: string, data: string): boolean;
  /** True when [env] answers a request of this store; the id is forgotten. */
  settle(env: Envelope): boolean;
}

export function requester(link: FrameLink, prefix: string, onDown: (why: string) => void): Requester {
  let counter = 0;
  const inflight = new Set<string>();
  return {
    send(type, data) {
      counter += 1;
      const requestId = `${prefix}:${Date.now().toString(36)}-${counter.toString(36)}`;
      inflight.add(requestId);
      if (link.sendFrame({ type, data, requestId })) return true;
      inflight.delete(requestId);
      onDown(`${type} not sent: the daemon link is down`);
      return false;
    },
    settle(env) {
      const rid = env.requestId;
      if (!rid || !inflight.has(rid)) return false;
      inflight.delete(rid);
      return true;
    },
  };
}
