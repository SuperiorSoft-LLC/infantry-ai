// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Left sidebar: counters, board, search, role filter, the Waiting on you / Ready to check / Running / Quiet groups,
// New agent.
import { useCallback, useEffect } from "react";
import { Search, UserPlus, SearchX, Unplug } from "lucide-react";
import { useAgentList } from "@/daemon/agents";
import { useDaemon } from "@/daemon/daemon";
import { useRolesLoaded } from "@/components/knowledge/use-knowledge";
import { useUi } from "@/state/ui-state";
import { Input } from "@infantry/ui";
import { Button } from "@infantry/ui";
import { ScrollArea } from "@infantry/ui";
import { EmptyState } from "@infantry/ui";
import { KeyHint } from "@infantry/ui";
import { SidebarCounters } from "./sidebar-counters";
import { RoleFilterChips } from "./role-filter-chips";
import { AgentGroup } from "./agent-group";
import { AGENT_SEARCH_ID } from "@/components/layout/use-shortcuts";

export function AgentSidebar() {
  const ui = useUi();
  useRolesLoaded();
  const agents = useAgentList();
  const link = useDaemon((s) => s.connection.state);
  const select = useCallback((id: string) => ui.set("selectedAgentId", id), [ui.set]);
  // Open the newest session when nothing (or a session that is gone) is selected.
  useEffect(() => {
    if (agents.length > 0 && !agents.some((a) => a.id === ui.selectedAgentId)) ui.set("selectedAgentId", agents[0].id);
  }, [agents, ui.selectedAgentId, ui.set]);
  const q = ui.search.trim().toLowerCase();
  const visible = agents.filter(
    (a) =>
      (ui.roleFilter === "all" || a.roleId === ui.roleFilter) &&
      (!q || `${a.name} ${a.roleId} ${a.ticketTitle ?? ""} ${a.sessionId}`.toLowerCase().includes(q)),
  );
  const waiting = visible.filter((a) => a.status === "needs-input");
  const review = visible.filter((a) => a.status === "review");
  const running = visible.filter((a) => a.status === "working");
  const quiet = visible.filter((a) => a.status !== "needs-input" && a.status !== "review" && a.status !== "working");

  return (
    <aside aria-label="Agents" className="flex h-full w-[268px] shrink-0 flex-col border-r bg-background">
      <div className="space-y-3 p-2.5 pb-2">
        <SidebarCounters agents={agents} onBoard={() => ui.set("sessionTab", "board")} />
        <div className="relative">
          <Search className="pointer-events-none absolute top-1/2 left-2.5 size-3.5 -translate-y-1/2 text-muted-foreground" />
          <Input
            value={ui.search}
            onChange={(e) => ui.set("search", e.target.value)}
            placeholder="Search agents..."
            aria-label="Search agents"
            id={AGENT_SEARCH_ID}
            className="h-10 bg-card pr-10 pl-8"
          />
          <KeyHint className="absolute top-1/2 right-3 -translate-y-1/2">^K</KeyHint>
        </div>
        <RoleFilterChips value={ui.roleFilter} onChange={(id) => ui.set("roleFilter", id)} />
      </div>

      <ScrollArea className="min-h-0 flex-1">
        <div className="space-y-2 px-2.5 pb-3">
          {agents.length === 0 ? (
            <EmptyState
              icon={<Unplug className="size-6" />}
              title={link === "open" ? "No sessions yet" : "Not connected"}
              hint={link === "open" ? "Start a new agent to see it here." : "Waiting for the daemon..."}
            />
          ) : visible.length === 0 ? (
            <EmptyState icon={<SearchX className="size-6" />} title="No agents match" hint="Clear the search or pick another role." />
          ) : (
            <>
              <AgentGroup title="Waiting on you" tone="warn" agents={waiting} selectedId={ui.selectedAgentId} onSelect={select} />
              <AgentGroup title="Ready to check" tone="info" agents={review} selectedId={ui.selectedAgentId} onSelect={select} />
              <AgentGroup title="Running" tone="ok" agents={running} selectedId={ui.selectedAgentId} onSelect={select} />
              <AgentGroup title="Quiet" tone="muted" agents={quiet} selectedId={ui.selectedAgentId} onSelect={select} />
            </>
          )}
        </div>
      </ScrollArea>

      <div className="border-t p-2">
        <Button variant="ghost" className="w-full text-danger hover:text-danger" onClick={() => ui.set("dialog", "new-agent")}>
          <UserPlus /> New agent
        </Button>
      </div>
    </aside>
  );
}
