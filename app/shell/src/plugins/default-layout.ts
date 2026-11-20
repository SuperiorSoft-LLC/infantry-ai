// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// WEBUI-5: the default layout, the one provider the shell ships. It reproduces the app as it always was: the centre tabs and
// the right-panel tabs, in their usual order. A component id is a declared tab/panel id, or a shell view's own id when no
// plugin declares it. The shell asks `layoutProvider()` where things go; a layout plugin swaps it with `setLayoutProvider`.
// A component the layout does not place (a newly installed plugin's tab) goes after the placed ones, never missing.
import { createLayoutProvider, type LayoutDocument, type LayoutProvider } from "@infantry/ui-plugin-api/layout";

const ids = (...list: string[]) => list.map((id) => ({ id }));

export const DEFAULT_LAYOUT: LayoutDocument = {
  id: "default",
  name: "Default",
  regions: [
    {
      id: "center",
      components: ids(
        "infantry.cli", "mail", "infantry.pods", "infantry.roles", "infantry.profiles", "infantry.triggers", "infantry.calendar",
        "infantry.epics", "infantry.notes", "skills", "prompts", "scripts", "infantry.run", "infantry.git", "processes", "health",
        "infantry.settings.plugins", "infantry.account", "infantry.sites", "infantry.settings",
      ),
    },
    {
      id: "right",
      components: ids("infantry.attention", "infantry.usage", "infantry.prompts", "infantry.changes", "infantry.mcp", "infantry.review", "infantry.skills"),
    },
  ],
};

let current: LayoutProvider = createLayoutProvider(DEFAULT_LAYOUT);

/** The layout the shell draws now. */
export const layoutProvider = (): LayoutProvider => current;

/** A layout plugin's entry: replaces the layout. Call before the shell renders (or re-render after). */
export function setLayoutProvider(p: LayoutProvider): void {
  current = p;
}
