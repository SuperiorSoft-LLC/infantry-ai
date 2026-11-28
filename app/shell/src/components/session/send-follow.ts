// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// CHAT-ORDER-2: the owner's own send is an explicit "go to latest". The transcript draws a pending send as its last row;
// a send that appears while the reader is scrolled up must still bring the view to it. Agent output does not.

/** True when [nextKeys] holds a pending send key that [prevKeys] did not: the owner just sent. */
export function sentSince(prevKeys: ReadonlySet<string>, nextKeys: readonly string[]): boolean {
  return nextKeys.some((k) => !prevKeys.has(k));
}
