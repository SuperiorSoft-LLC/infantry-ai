// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// BOS-26: the start splash (index.html) and what it says. No React, no daemon import: a page that cannot start must still be able to say why.

/** Shows `text` on the splash (the `#splash-msg` line of index.html). */
export function splashMessage(text: string, doc: Pick<Document, "getElementById"> = document): void {
  const m = doc.getElementById("splash-msg");
  if (m) {
    m.textContent = text;
    m.hidden = false;
  }
}

/** A start that rejected: its own message replaces the "Still connecting" line, and the timer that would write it is stopped. */
export function startFailed(err: unknown, timer?: ReturnType<typeof setTimeout>, doc?: Pick<Document, "getElementById">): void {
  if (timer !== undefined) clearTimeout(timer);
  splashMessage(`Could not start: ${err instanceof Error ? err.message : typeof err === "string" ? err : (JSON.stringify(err) ?? String(err))}`, doc);
}
