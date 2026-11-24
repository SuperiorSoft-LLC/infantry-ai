// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// UI-DECLUTTER-1: centre tabs that no longer exist because the same thing is reachable elsewhere. An old saved view, link or
// plugin declaration that still names one is sent to where it lives now (the cog's Settings dialog, the right panel's Prompts).
export type RemovedTabTarget = { kind: "dialog"; dialog: "settings" } | { kind: "right"; tab: string };

const REMOVED: Record<string, RemovedTabTarget> = {
  settings: { kind: "dialog", dialog: "settings" },
  "infantry.settings": { kind: "dialog", dialog: "settings" },
  prompts: { kind: "right", tab: "prompts" },
  "infantry.prompts": { kind: "right", tab: "prompts" },
};

/** Where a removed centre tab id now lives, or null for any other id. */
export const removedTabTarget = (id: string): RemovedTabTarget | null => REMOVED[id] ?? null;
