// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Session header: avatar, name, session id, role/status chips, provider / model / effort / context-limit chips, runs,
// tokens, and the session
// controls (interrupt, restart, switch provider/model, stats). The automation count (turns saved, wakes; the hover
// lists wakes by reason) comes from the daemon's `timeline_item` stats.
import type { Agent } from "@/mock/types";
import { AgentAvatar } from "@/components/agents/agent-avatar";
import { Chip } from "@infantry/ui";
import { useDaemon } from "@/daemon/daemon";
import { useAutomationStats } from "@/daemon/use-timeline";
import { ExternalLink } from "lucide-react";
import { IconButton } from "@infantry/ui";
import { useUi } from "@/state/ui-state";

const tokens = (n: number) => (n >= 1000 ? `${Math.round(n / 1000)}k` : String(n));

export function SessionHeader({ agent }: { agent: Agent }) {
  const effort = useDaemon((s) => s.sessions[agent.id]?.record.effort);
  const rec = useDaemon((s) => s.sessions[agent.id]?.record);
  const limit = rec?.contextLimit;
  const auto = useAutomationStats(agent.id);
  const ui = useUi();
  return (
    <div className="flex min-w-0 max-w-full flex-wrap items-center gap-x-4 gap-y-2 px-4 pt-3 pb-2 [&_span]:max-w-full [&_span]:break-all">
      <AgentAvatar agent={agent} size="md" />
      <div className="min-w-0 flex-1 basis-48">
        <div className="flex flex-wrap items-center gap-2">
          <h1 className="text-lg font-bold">{agent.name}</h1>
          <span className="font-mono text-xs text-muted-foreground">{agent.sessionId}</span>
          <Chip tone="red">{agent.roleId}</Chip>
          <Chip tone={agent.status === "working" ? "blue" : "neutral"}>{agent.status}</Chip>
        </div>
        <div className="truncate font-mono text-xs text-muted-foreground">{agent.statusLine}</div>
      </div>
      <div className="flex min-w-0 max-w-full flex-wrap items-center gap-1.5">
        <Chip tone="red">{agent.provider}</Chip>
        <Chip tone="green">{agent.model}</Chip>
        {effort && <Chip>effort {effort}</Chip>}
        {limit != null && (
          <span title={`Max context: ${limit ? limit.toLocaleString() : "off"} tokens, from ${rec?.contextLimitSource ?? "the harness"}. ${rec?.contextLimitHow ?? ""}`}>
            <Chip>
              {limit ? `context ${tokens(limit)}` : "context off"}
              {rec?.contextLimitSource ? ` \u00b7 ${rec.contextLimitSource}` : ""}
              {rec?.contextLimitSupport === "unsupported" ? " \u00b7 not applied" : ""}
            </Chip>
          </span>
        )}
        <Chip>runs {agent.runs}</Chip>
        {auto && (auto.turnsSaved > 0 || auto.wakes > 0) && (
          <span title={`Automation: wakes by reason ${Object.entries(auto.byReason).map(([k, v]) => `${k} ${v}`).join(", ") || "none"}`}>
            <Chip tone="green">
              turns saved {auto.turnsSaved} · wakes {auto.wakes}
            </Chip>
          </span>
        )}
        {agent.tokensKnown ? (
          <Chip tone="blue">
            {agent.tokensIn} / {agent.tokensOut}
          </Chip>
        ) : null /* PHONE-SESSION-UI-1: no chip until real counts exist (developer text confused users). */}
        <IconButton label="Pop out this chat" onClick={() => ui.popOut(agent.id)}>
          <ExternalLink />
        </IconButton>
      </div>
    </div>
  );
}
