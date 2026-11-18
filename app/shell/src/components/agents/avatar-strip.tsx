// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Title-bar strip of agent avatars plus the Assistant pill.
import { Bot } from "lucide-react";
import { useAgentList } from "@/daemon/agents";
import { useUi } from "@/state/ui-state";
import { useRolesLoaded } from "@/components/knowledge/use-knowledge";
import { Tooltip, TooltipContent, TooltipTrigger } from "@infantry/ui";
import { AgentAvatar } from "./agent-avatar";

/** Avatars the title bar has room for. */
const STRIP_SIZE = 7;

export function AvatarStrip() {
  const ui = useUi();
  useRolesLoaded();
  // The running agents first, then the rest, as many as the strip shows.
  const all = useAgentList();
  const list = [...all.filter((a) => a.group === "running"), ...all.filter((a) => a.group !== "running")].slice(0, STRIP_SIZE);
  return (
    <div className="flex items-center gap-1.5">
      {list.map((a) => (
        <Tooltip key={a.id}>
          <TooltipTrigger asChild>
            <button
              type="button"
              aria-label={`${a.name} (${a.sessionId})`}
              onClick={() => ui.set("selectedAgentId", a.id)}
              className="rounded-full focus-visible:ring-3 focus-visible:ring-ring/50 focus-visible:outline-none"
            >
              <AgentAvatar agent={a} size="xs" showStatus={false} live={a.group === "running"} ring={a.id === ui.selectedAgentId} />
            </button>
          </TooltipTrigger>
          <TooltipContent>
            {a.name} · {a.statusLine}
          </TooltipContent>
        </Tooltip>
      ))}
      <button
        type="button"
        className="ml-1 inline-flex h-7 items-center gap-1.5 rounded-full border border-danger/70 px-2.5 text-xs font-medium whitespace-nowrap hover:bg-danger/10"
      >
        <Bot className="size-3.5 text-danger" /> Assistant
      </button>
    </div>
  );
}
