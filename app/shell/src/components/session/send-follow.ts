// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// CHAT-ORDER-2: the owner's own send is an explicit "go to latest". The transcript draws a pending send as its last row;
// a send that appears while the reader is scrolled up must still bring the view to it. Agent output does not.

/** True when [nextKeys] holds a pending send key that [prevKeys] did not: the owner just sent. */
export function sentSince(prevKeys: ReadonlySet<string>, nextKeys: readonly string[]): boolean {
  return nextKeys.some((k) => !prevKeys.has(k));
}

// CHAT-ORDER-3: an acknowledged send stays drawn ("sent") until its TURN echo settles it in the store; filtering out
// the accepted state left a window, ack to echo (a whole queued turn when the harness is busy), where it showed nowhere.
/** The pending sends the transcript draws for [sessionId]: every unsettled one, sending, accepted or failed. */
export function shownPending<P extends { sessionId: string }>(pending: Record<string, P>, sessionId: string): P[] {
  return Object.values(pending).filter((p) => p.sessionId === sessionId);
}
