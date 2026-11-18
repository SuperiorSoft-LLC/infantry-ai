// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Centre column: header, tab bar, then either the live CLI transcript + composer or the selected tab's mock.
import { useEffect } from "react";
import { MessageCircle, Unplug } from "lucide-react";
import { automationLine, permissionsLine } from "@/mock/data";
import { useUi } from "@/state/ui-state";
import { EmptyState, IconButton } from "@infantry/ui";
import { useAgent } from "@/daemon/agents";
import { useDaemon } from "@/daemon/daemon";
import { TabContent } from "@/components/tabs/tab-content";
import { useTabs } from "@/plugins/catalog";
import { SessionHeader } from "./session-header";
import { SessionTabBar } from "./session-tab-bar";
import { Transcript } from "./transcript";
import { FloatingViewButtons } from "./floating-view-buttons";
import { Composer } from "./composer";
import { PromptCards } from "./prompt-cards";
import { SessionDialogs } from "./session-actions";

export function SessionView() {
  const ui = useUi();
  const agent = useAgent(ui.selectedAgentId);
  const link = useDaemon((s) => s.connection.state);
  const requestHistory = useDaemon((s) => s.requestHistory);
  const busy = agent?.status === "working";
  const tabs = useTabs("center");
  const { sessionTab, set } = ui;

  // A tab whose plugin was disabled goes away: fall back to the CLI view (the Board is not in the bar).
  useEffect(() => {
    if (sessionTab !== "board" && !tabs.some((t) => t.id === sessionTab)) set("sessionTab", "cli");
  }, [sessionTab, tabs, set]);

  // A session opened in the view brings its history, if the burst did not already ask for it.
  useEffect(() => {
    if (agent) requestHistory(agent.id);
  }, [agent?.id, requestHistory]); // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <main className="flex min-w-0 flex-1 flex-col bg-background">
      {agent && <SessionHeader agent={agent} />}
      <SessionTabBar tabs={tabs} value={ui.sessionTab} onChange={(id) => ui.set("sessionTab", id)} />

      {ui.sessionTab === "cli" ? (
        <>
          <div className="flex items-center gap-2 border-b px-4 py-1.5">
            <span className="min-w-0 flex-1 truncate font-mono text-[11px] text-muted-foreground">{automationLine}</span>
            <IconButton label="Open chat" variant="outline" className="rounded-full">
              <MessageCircle />
            </IconButton>
          </div>
          <div className="relative flex min-h-0 flex-1 flex-col">
            {agent ? (
              <Transcript agent={agent} sessionId={agent.id} />
            ) : (
              <div className="grid flex-1 place-items-center p-6">
                <EmptyState
                  icon={<Unplug className="size-7" />}
                  title={link === "open" ? "No session selected" : "Not connected to the daemon"}
                  hint={link === "open" ? "Send a prompt below to start a new session." : "The app reconnects on its own."}
                />
              </div>
            )}
            <FloatingViewButtons />
          </div>
          {agent && <PromptCards sessionId={agent.id} />}
          <div className="px-4 pt-2 pb-1.5 font-mono text-[11px] text-muted-foreground">
            permissions: {permissionsLine.mode} · {permissionsLine.agents} agents · {busy ? permissionsLine.state : "idle"}
          </div>
          <Composer sessionId={agent?.id ?? null} busy={busy} />
        </>
      ) : (
        <TabContent tab={ui.sessionTab} />
      )}
      <SessionDialogs />
    </main>
  );
}
