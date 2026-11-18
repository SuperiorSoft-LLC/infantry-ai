// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Window layout: title bar, sidebar | session | right panel, status bar.
// Wide: side panels sit inline. Narrow: they become overlay drawers over the session view.
import { useEffect, type ReactNode } from "react";
import { useNarrow, useUi } from "@/state/ui-state";
import { AgentSidebar } from "@/components/agents/agent-sidebar";
import { SessionView } from "@/components/session/session-view";
import { RightPanel } from "@/components/usage/right-panel";
import { DialogHost } from "@/components/dialogs/dialog-host";
import { TitleBar } from "./title-bar";
import { StatusBar } from "./status-bar";
import { usePluginsLoaded } from "@/plugins/use-plugins";

function Drawer({ side, onClose, children }: { side: "left" | "right"; onClose: () => void; children: ReactNode }) {
  return (
    <div className="absolute inset-0 z-30 flex" style={{ justifyContent: side === "left" ? "flex-start" : "flex-end" }}>
      <button type="button" aria-label="Close panel" className="absolute inset-0 bg-black/50" onClick={onClose} />
      <div className="relative h-full shadow-2xl">{children}</div>
    </div>
  );
}

export function AppShell() {
  const ui = useUi();
  const narrowLeft = useNarrow(900);
  const narrowRight = useNarrow(1280);
  const { set } = ui;
  usePluginsLoaded();

  // Crossing into a narrow width collapses that panel; the title-bar toggles reopen it as a drawer.
  useEffect(() => {
    if (narrowLeft) set("leftOpen", false);
  }, [narrowLeft, set]);
  useEffect(() => {
    if (narrowRight) set("rightOpen", false);
  }, [narrowRight, set]);

  return (
    <div className="flex h-full flex-col">
      <TitleBar />
      <div className="relative flex min-h-0 flex-1">
        {ui.leftOpen && !narrowLeft && <AgentSidebar />}
        <SessionView />
        {ui.rightOpen && !narrowRight && <RightPanel />}

        {ui.leftOpen && narrowLeft && (
          <Drawer side="left" onClose={() => ui.toggle("leftOpen")}>
            <AgentSidebar />
          </Drawer>
        )}
        {ui.rightOpen && narrowRight && (
          <Drawer side="right" onClose={() => ui.toggle("rightOpen")}>
            <RightPanel />
          </Drawer>
        )}
      </div>
      <StatusBar />
      <DialogHost />
    </div>
  );
}
