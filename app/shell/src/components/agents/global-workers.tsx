// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Global Workers board: the Working counter opens a lanes modal (one live lane per project, holding that project's working
// agents as the sidebar's own AgentCard), and a card click stacks that session's chat modal (the one ChatPanel) over it.
// The lanes stay mounted under the chat, so closing the chat returns to them as they were; Escape closes the top modal only.
import { useMemo, useState } from "react";
import { AppDialog, Button } from "@infantry/ui";
import { workerLanes, type WorkerLane } from "@infantry/client";
import type { Agent } from "@/mock/types";
import { useAllAgentList, useAgent } from "@/daemon/agents";
import { useProjects } from "@/components/git/use-projects";
import { useUi } from "@/state/ui-state";
import { ChatPanel } from "@/components/session/chat-panel";
import { AgentCard } from "./agent-card";

export const NO_PROJECT = "No project";

/** The lanes of the working agents (the set the Working counter counts). */
export function useWorkerLanes(): { lanes: WorkerLane<Agent>[]; count: number } {
  const all = useAllAgentList();
  const projects = useProjects((s) => s.projects);
  const active = useProjects((s) => s.currentId);
  return useMemo(() => {
    const working = all.filter((a) => a.status === "working");
    return { lanes: workerLanes(working, projects, active), count: working.length };
  }, [all, projects, active]);
}

function LaneColumn({ lane, onOpen }: { lane: WorkerLane<Agent>; onOpen: (id: string) => void }) {
  return (
    <section
      data-testid={`global-workers-lane-${lane.projectId ?? "none"}`}
      aria-label={lane.name}
      className="flex min-h-0 w-full shrink-0 flex-col overflow-hidden rounded-lg border bg-background md:w-[300px]"
    >
      <header className="flex items-center gap-2 border-b bg-muted/40 px-3 py-2">
        <span className="min-w-0 flex-1 truncate text-xs font-semibold">{lane.name}</span>
        <span className="text-[11px] font-bold text-ok">{lane.agents.length}</span>
      </header>
      <div className="min-h-0 flex-1 space-y-2 overflow-y-auto p-2">
        {lane.agents.map((a) => (
          <AgentCard key={a.id} agent={a} selected={false} onSelect={onOpen} />
        ))}
      </div>
    </section>
  );
}

function WorkerChatDialog({ id, onClose }: { id: string; onClose: () => void }) {
  const ui = useUi();
  const agent = useAgent(id);
  const select = useProjects((s) => s.select);
  const active = useProjects((s) => s.currentId);
  if (!agent) return null;
  const openSession = () => {
    if (agent.projectId && agent.projectId !== active) select(agent.projectId);
    ui.set("selectedAgentId", agent.id);
    ui.set("workersOpen", false);
    onClose();
  };
  return (
    <AppDialog
      open
      onOpenChange={(o) => !o && onClose()}
      title={`${agent.roleId} · ${agent.projectName ?? NO_PROJECT}`}
      description={agent.name}
      wide="fill"
      footer={
        <>
          <Button variant="ghost" onClick={openSession}>Open session</Button>
          <Button onClick={onClose}>Close</Button>
        </>
      }
    >
      <div className="flex h-[min(60vh,640px)] min-h-0 flex-col" data-testid="global-workers-chat">
        <ChatPanel sessionId={agent.id} variant="popout" />
      </div>
    </AppDialog>
  );
}

export function GlobalWorkersHost() {
  const ui = useUi();
  const { lanes, count } = useWorkerLanes();
  const [chatId, setChatId] = useState<string | null>(null);
  if (!ui.workersOpen || !ui.globalWorkers) return null;
  const close = () => ui.set("workersOpen", false);
  return (
    <>
      <AppDialog
        open
        onOpenChange={(o) => !o && close()}
        title={`Working in every project (${count})`}
        description="One lane per project. Click an agent to open its chat."
        wide="fill"
        footer={<Button onClick={close}>Close</Button>}
      >
        {lanes.length === 0 ? (
          <div data-testid="global-workers-empty" className="grid h-full min-h-40 place-items-center text-sm text-muted-foreground">
            No agents are working right now
          </div>
        ) : (
          <div data-testid="global-workers-lanes" className="flex h-full min-h-0 flex-col gap-2.5 md:flex-row md:overflow-x-auto">
            {lanes.map((l) => (
              <LaneColumn key={l.projectId ?? "none"} lane={l} onOpen={setChatId} />
            ))}
          </div>
        )}
      </AppDialog>
      {chatId && <WorkerChatDialog id={chatId} onClose={() => setChatId(null)} />}
    </>
  );
}
