// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// DESK-3: what a layout plugin may read of the agents (HostApi.useAgentActivity, HostApi.AgentAvatar). It reads the app's own
// session store through the same Agent view model the sidebar counters use: Working = the Global Workers set, Needs input =
// the sidebar's "needs-input" status, here across every project. No new daemon frames.
import { useMemo } from "react";
import type { ActiveAgent, AgentActivity } from "@infantry/ui-plugin-api/contract";
import { useAgent, useAllAgentList } from "@/daemon/agents";
import { AgentAvatar } from "@/components/agents/agent-avatar";
import { useRolesLoaded } from "@/components/knowledge/use-knowledge";

export function useAgentActivity(): AgentActivity {
  const all = useAllAgentList();
  return useMemo(() => {
    const agents: ActiveAgent[] = [];
    for (const a of all) if (a.status === "needs-input") agents.push({ id: a.id, name: a.name, status: "needs-input", statusLine: a.statusLine });
    const needsInput = agents.length;
    for (const a of all) if (a.status === "working") agents.push({ id: a.id, name: a.name, status: "working", statusLine: a.statusLine });
    return { working: agents.length - needsInput, needsInput, agents };
  }, [all]);
}

export function HostAgentAvatar({ agentId, size = "xs" }: { agentId: string; size?: "xs" | "sm" }) {
  useRolesLoaded();
  const agent = useAgent(agentId);
  return agent ? <AgentAvatar agent={agent} size={size} showStatus={false} live={agent.status === "working"} /> : null;
}
