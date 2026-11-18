// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Session header: avatar, name, session id, role/status chips, provider/model chips, runs, tokens, and the session
// controls (interrupt, restart, switch provider/model, stats).
import type { Agent } from "@/mock/types";
import { AgentAvatar } from "@/components/agents/agent-avatar";
import { Chip } from "@infantry/ui";
import { SessionActionButtons } from "./session-actions";

export function SessionHeader({ agent }: { agent: Agent }) {
  return (
    <div className="flex flex-wrap items-center gap-x-4 gap-y-2 px-4 pt-3 pb-2">
      <AgentAvatar agent={agent} size="md" />
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2">
          <h1 className="text-lg font-bold">{agent.name}</h1>
          <span className="font-mono text-xs text-muted-foreground">{agent.sessionId}</span>
          <Chip tone="red">{agent.roleId}</Chip>
          <Chip tone={agent.status === "working" ? "blue" : "neutral"}>{agent.status}</Chip>
        </div>
        <div className="truncate font-mono text-xs text-muted-foreground">{agent.statusLine}</div>
      </div>
      <div className="flex flex-wrap items-center gap-1.5">
        <Chip>{agent.provider}</Chip>
        <Chip tone="red">{agent.provider}</Chip>
        <Chip tone="green">{agent.model}</Chip>
        <Chip tone="blue">
          {agent.provider} · {agent.model}
        </Chip>
        <Chip>runs {agent.runs}</Chip>
        <Chip tone="blue">
          {agent.tokensIn} / {agent.tokensOut}
        </Chip>
        <SessionActionButtons sessionId={agent.id} busy={agent.status === "working"} />
      </div>
    </div>
  );
}
