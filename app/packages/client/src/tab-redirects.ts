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

// BOS-24: under BattalionOS (window `app-<id>`) the OS lists, installs and updates apps in its own Apps list and Armory. Infantry AI's
// own Apps tab (a screen for its own plugins) gets no tab there, so the two are never shown side by side. The Plugins screen stays.
const HIDDEN_UNDER_OS = new Set(["infantry.installer"]);

/** Whether tab [id] is left out of the tab bar while the window is an app of BattalionOS. */
export const hiddenUnderOs = (id: string): boolean => HIDDEN_UNDER_OS.has(id);

/** Where a removed centre tab id now lives, or null for any other id. */
export const removedTabTarget = (id: string): RemovedTabTarget | null => REMOVED[id] ?? null;
