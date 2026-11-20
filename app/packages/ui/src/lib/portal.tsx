// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Where the kit's floating parts (dialogs, selects, menus, tooltips) render: document.body, unless an ancestor names
// another element with <PortalContainer>. The app gives each web UI plugin its own portal root (WEBUI-1), so a plugin's
// dialog sits inside the plugin's style scope like the rest of it.
import { createContext, useContext, type ReactNode } from "react";

const PortalContainerContext = createContext<HTMLElement | null>(null);

/** Renders [children] with the kit's portals going into [container] (null: document.body). */
export function PortalContainer({ container, children }: { container: HTMLElement | null; children: ReactNode }) {
  return <PortalContainerContext value={container}>{children}</PortalContainerContext>;
}

/** The element the nearest <PortalContainer> names, or undefined (Radix's default: document.body). */
export function usePortalContainer(): HTMLElement | undefined {
  return useContext(PortalContainerContext) ?? undefined;
}
