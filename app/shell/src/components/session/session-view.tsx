// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Centre column: header, tab bar, then either the live CLI transcript + composer or the selected tab's mock.
import { useEffect } from "react";
import { useUi } from "@/state/ui-state";
import { useAgent } from "@/daemon/agents";
import { TabContent } from "@/components/tabs/tab-content";
import { removedTabTarget } from "@infantry/client";
import { useTabs } from "@/plugins/catalog";
import { SessionHeader } from "./session-header";
import { SessionTabBar } from "./session-tab-bar";
import { FullChatPanel } from "./chat-panel";
import { SessionDialogs } from "./session-actions";

export function SessionView() {
  const ui = useUi();
  const agent = useAgent(ui.selectedAgentId);
  const tabs = useTabs("center");
  const { sessionTab, set } = ui;

  // A tab whose plugin was disabled goes away: fall back to the CLI view.
  // A saved view or link naming a removed tab (Settings, Prompts) goes where it lives now: the cog's dialog, the right panel.
  useEffect(() => {
    const to = removedTabTarget(sessionTab);
    if (!to) return;
    if (to.kind === "dialog") set("dialog", to.dialog);
    else {
      set("rightTab", to.tab);
      set("rightOpen", true);
    }
    set("sessionTab", "cli");
  }, [sessionTab, set]);
  useEffect(() => {
    if (!removedTabTarget(sessionTab) && !tabs.some((t) => t.id === sessionTab)) set("sessionTab", "cli");
  }, [sessionTab, tabs, set]);

  return (
    <main className="flex min-w-0 flex-1 flex-col overflow-x-hidden bg-background">
      {agent && !ui.chatMax && <SessionHeader agent={agent} />}
      {!ui.chatMax && <SessionTabBar tabs={tabs} value={ui.sessionTab} onChange={(id) => ui.set("sessionTab", id)} />}

      {ui.sessionTab === "cli" ? (
        <FullChatPanel sessionId={agent?.id ?? null} />
      ) : (
        <TabContent tab={ui.sessionTab} />
      )}
      <SessionDialogs />
    </main>
  );
}
