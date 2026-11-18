// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// One agent in the sidebar. Selected = amber border + scrolling stripes (SelectedStripes); a busy PM card is striped (SelectedStripes.kt).
import { memo } from "react";
import { cn } from "@infantry/ui";
import type { Agent } from "@/mock/types";
import { AgentAvatar } from "./agent-avatar";
import { ProgressLine, SelectedStripes } from "@infantry/ui";

/** Memoised: a card re-renders only when its own session (or the selection) changed. */
export const AgentCard = memo(function AgentCard({ agent, selected, onSelect }: { agent: Agent; selected: boolean; onSelect: (id: string) => void }) {
  const striped = agent.roleId === "pm" && agent.status === "working";
  const compact = agent.group === "quiet";
  return (
    <button
      type="button"
      onClick={() => onSelect(agent.id)}
      aria-pressed={selected}
      className={cn(
        "group relative w-full rounded-lg border-2 bg-card p-3 text-left transition-colors hover:border-info/40",
        "focus-visible:ring-3 focus-visible:ring-ring/50 focus-visible:outline-none",
        selected ? "border-warn" : "border-info/25",
        compact && "border",
        striped && "stripes border-info/40",
      )}
    >
      <SelectedStripes active={selected} />
      <div className="relative flex items-start gap-3">
        <AgentAvatar agent={agent} size="md" className="mt-4" />
        <div className="min-w-0 flex-1 space-y-1">
          <div className="flex items-baseline justify-between gap-2">
            <span className="truncate text-[15px] font-semibold">{agent.name}</span>
            <span className="shrink-0 text-xs text-muted-foreground">{agent.age}</span>
          </div>

          {agent.ticketId && !compact && (
            <div className="truncate font-mono text-[11px] text-info">
              {agent.ticketId} <span className="font-sans">{agent.ticketTitle}</span>
            </div>
          )}

          {agent.heldBy ? (
            <div className="truncate text-xs font-medium text-warn" title="Running in the other app: resume it there, or stop it there first">
              {agent.statusLine}
            </div>
          ) : (
            !compact && !striped && <div className="truncate text-xs text-muted-foreground">{agent.statusLine}</div>
          )}
          {agent.progress !== undefined && !compact && <ProgressLine value={agent.progress} className="my-1.5" />}

          <div className="flex min-w-0 items-center gap-2 text-xs">
            <span className={cn("shrink-0 font-medium", agent.roleId === "pm" ? "text-warn" : "text-info")}>{agent.roleId}</span>
            <span className="shrink-0 font-mono text-[10px] text-muted-foreground">{agent.sessionId}</span>
            {agent.projectName && <span className="shrink-0 truncate text-foreground/80">{agent.projectName}</span>}
            <span className="truncate text-muted-foreground">{agent.lastLine}</span>
          </div>

          {agent.ticketTitle && !compact && (
            <div className="truncate pt-1 text-xs">
              <span className="text-muted-foreground">on: </span>
              {agent.ticketTitle}
            </div>
          )}
        </div>
      </div>
    </button>
  );
});
