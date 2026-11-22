// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// RR-14: the ONE way the app aims a frame at a runner. `local` (or no runner) sends the frame as it is; any other runner gets RR-13's
// owner-only `runner_frame {runner, frame, data}` and the INNER answer comes back, so every caller (stores, wizard, pickers) stays the same.
// `runnerLink` wraps a FrameLink that way (one wrapper, so the existing stores run against a runner unchanged); `runnerRequest` is the
// one-shot request/answer form. The secret values of a key set travel in the body only, as they do for `local`.
import type { Envelope } from "./wire";
import type { FrameLink } from "./stores/link";

export const LOCAL_RUNNER = "local";
export const RUNNER_FRAME = "runner_frame";

let counter = 0;

/** True for the embedded runner (the machine the app talks to): the frame goes out plain. */
export const isLocalRunner = (runner?: string | null): boolean => !runner || runner === LOCAL_RUNNER;

/** The envelope to send for [env] aimed at [runner]: unchanged for local, wrapped in `runner_frame` for any other. */
export function aimed(runner: string | null | undefined, env: Envelope): Envelope {
  if (isLocalRunner(runner)) return env;
  return { ...env, type: RUNNER_FRAME, data: JSON.stringify({ runner, frame: env.type, data: env.data }) };
}

/**
 * [link] aimed at [runner]. For local it is [link] itself. Otherwise frames go out wrapped, and only the answers (and refusals) to
 * requests made through this wrapper come back: the local daemon's own pushes (its `secret_state`, `providers`...) never reach a
 * store that is reading another machine. `ready` (the link came back) passes, so stores re-read.
 */
export function runnerLink(link: FrameLink, runner: string | null | undefined): FrameLink {
  if (isLocalRunner(runner)) return link;
  const mine = new Set<string>();
  return {
    sendFrame(env) {
      // A frame sent with no id (a store's plain read) gets one, so its answer is recognised as ours and no other frame is.
      const requestId = env.requestId ?? `rlink:${Date.now().toString(36)}-${(++counter).toString(36)}`;
      mine.add(requestId);
      return link.sendFrame(aimed(runner, { ...env, requestId }));
    },
    onFrame(listener) {
      return link.onFrame((env) => {
        if (env.type === "ready" || (env.requestId && mine.has(env.requestId))) listener(env);
      });
    },
  };
}



/**
 * One request aimed at [runner]: resolves with the answer frame (for a runner, the runner's own answer type and body), rejects with
 * the daemon's refusal text (`runner_frame refused: ...` names the runner and why) or when the link is down or nothing answers.
 */
export function runnerRequest(link: FrameLink, runner: string | null | undefined, frame: string, data = "{}", timeoutMs = 30_000): Promise<Envelope> {
  return new Promise((resolve, reject) => {
    counter += 1;
    const requestId = `rreq:${Date.now().toString(36)}-${counter.toString(36)}`;
    const done = (fn: () => void) => {
      clearTimeout(timer);
      off();
      fn();
    };
    const off = link.onFrame((env) => {
      if (env.requestId !== requestId) return;
      if (env.type === "error") done(() => reject(new Error(env.data)));
      else done(() => resolve(env));
    });
    const timer = setTimeout(() => done(() => reject(new Error(`${frame}: no answer from runner ${runner || LOCAL_RUNNER}`))), timeoutMs);
    if (!link.sendFrame(aimed(runner, { type: frame, data, requestId }))) done(() => reject(new Error(`${frame} not sent: the daemon link is down`)));
  });
}
