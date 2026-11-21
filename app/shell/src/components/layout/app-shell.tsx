// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Window layout: title bar, sidebar | session | right panel, status bar.
// Wide: side panels sit inline. Narrow: they become overlay drawers over the session view.
import { GlobalWorkersHost } from "@/components/agents/global-workers";
import { useEffect, useRef, type ReactNode } from "react";
import { panelsToClose, type Layout } from "@infantry/client";
import { useLayout, useUi } from "@/state/ui-state";
import { AgentSidebar } from "@/components/agents/agent-sidebar";
import { SessionView } from "@/components/session/session-view";
import { RightPanel } from "@/components/usage/right-panel";
import { DialogHost } from "@/components/dialogs/dialog-host";
import type { ShellProps } from "@infantry/ui-plugin-api/contract";
import { TitleBar } from "./title-bar";
import { StatusBar } from "./status-bar";
import { usePluginsLoaded } from "@/plugins/use-plugins";
import { useShortcuts } from "./use-shortcuts";
import { ToastHost } from "./toast-host";
import { PopoutHost } from "@/components/session/popout-host";
import { useAutoSetup } from "@/components/setup/setup-wizard";

function Drawer({ side, onClose, children }: { side: "left" | "right"; onClose: () => void; children: ReactNode }) {
  return (
    <div className="absolute inset-0 z-30 flex" style={{ justifyContent: side === "left" ? "flex-start" : "flex-end" }}>
      <button type="button" aria-label="Close panel" className="absolute inset-0 bg-black/50" onClick={onClose} />
      <div className="relative h-full max-w-[92vw] overflow-hidden shadow-2xl">{children}</div>
    </div>
  );
}

export function AppShell({ headerActions, statusLeading }: Pick<ShellProps, "headerActions" | "statusLeading"> = {}) {
  const ui = useUi();
  const layout = useLayout();
  const narrowLeft = !layout.leftInline;
  const narrowRight = !layout.rightInline;
  const { set } = ui;
  usePluginsLoaded();
  useShortcuts();
  useAutoSetup();

  // Crossing into a width where a panel becomes a drawer collapses it; the title-bar toggles reopen it (layout.ts).
  const prev = useRef<Layout | null>(null);
  useEffect(() => {
    const close = panelsToClose(prev.current, layout);
    prev.current = layout;
    if (close.left) set("leftOpen", false);
    if (close.right) set("rightOpen", false);
  }, [layout, set]);
  // A phone or tablet: picking a session in the agents drawer closes it, so the session shows at once.
  const picked = useRef(ui.selectedAgentId);
  useEffect(() => {
    if (picked.current !== ui.selectedAgentId && layout.closeDrawerOnPick) set("leftOpen", false);
    picked.current = ui.selectedAgentId;
  }, [ui.selectedAgentId, layout.closeDrawerOnPick, set]);

  return (
    <div className="flex h-full flex-col" data-layout={layout.kind}>
      <TitleBar headerActions={headerActions} />
      <div className="relative flex min-h-0 flex-1">
        {ui.leftOpen && !narrowLeft && <AgentSidebar />}
        <SessionView />
        {ui.rightOpen && !narrowRight && <RightPanel />}

        {ui.leftOpen && narrowLeft && (
          <Drawer side="left" onClose={() => ui.toggle("leftOpen")}>
            <AgentSidebar />
          </Drawer>
        )}
        <PopoutHost />
        {ui.rightOpen && narrowRight && (
          <Drawer side="right" onClose={() => ui.toggle("rightOpen")}>
            <RightPanel />
          </Drawer>
        )}
      </div>
      <StatusBar leading={statusLeading} />
      <ToastHost />
      <DialogHost />
      <GlobalWorkersHost />
    </div>
  );
}
