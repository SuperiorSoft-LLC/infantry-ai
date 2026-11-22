// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The harness install store: `harness_install_status` {} -> `harness_install_status` (the daemon's install report). An install
// (`harness_install {ids, update, runtime}`) runs on the daemon; its progress is the report's `busy` and `log`, re-read while busy.
import type { HarnessInstallReport } from "../setup-wizard";
import { createQueryStore, type QueryStore } from "./query";
import type { FrameLink } from "./link";

export type HarnessInstallStore = QueryStore<HarnessInstallReport | null>;

export function createHarnessInstallStore(link: FrameLink): HarnessInstallStore {
  return createQueryStore<HarnessInstallReport | null>(link, {
    prefix: "hinst",
    request: "harness_install_status",
    answers: ["harness_install_status"],
    empty: null,
    parse: (raw) => {
      const r = raw as HarnessInstallReport | null;
      return r && Array.isArray(r.harnesses) ? { ...r, log: r.log ?? [], busy: !!r.busy } : null;
    },
    // An install's answer means "read again": the wizard polls only while the report says busy, so without this re-read it never
    // saw the install start (or a fast failure) and sat on "Installing it for you now" (Windows Sandbox run 6).
    rereads: ["harness_install_result"],
    own: ["harness_install refused"],
  });
}

/** Starts an install on the daemon: [ids] empty = every missing one; [runtime] = only Node.js. */
export function installHarnesses(store: HarnessInstallStore, ids: string[], opts: { update?: boolean; runtime?: boolean } = {}): boolean {
  const sent = store.getState().send("harness_install", JSON.stringify({ ids, update: !!opts.update, runtime: !!opts.runtime }));
  // The daemon's answer comes at once but its next status can take seconds: show the install as running now, so the progress view and
  // the 1.5 s poll start with the click. The next status read corrects it (a refusal because one already runs leaves busy true, right).
  const data = store.getState().data;
  if (sent && data && !data.busy) store.setState({ data: { ...data, busy: true } });
  return sent;
}

/** Removes the app's private copies of [ids] (only where the user has their own); the daemon refuses when it is the only copy. */
export function removePrivateCopies(store: HarnessInstallStore, ids: string[]): boolean {
  const sent = store.getState().send("harness_install", JSON.stringify({ remove: ids }));
  if (sent) store.getState().refresh();
  return sent;
}
