// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Stats: the selected session's numbers (`session_stats`). The Kotlin tab's tokens-by-day/week view needs a client
// store for `usage_meter` (docs/rust-port/PARITY.md).
import { BarChart3 } from "lucide-react";
import { EmptyState } from "@infantry/ui";
import { useUi } from "@/state/ui-state";
import { useAgent } from "@/daemon/agents";
import { SessionStatsList } from "@/components/session/session-actions";
import { TabPage } from "./tab-page";

export function StatsTab() {
  const ui = useUi();
  const agent = useAgent(ui.selectedAgentId);
  return (
    <TabPage title="Stats" description={agent ? `${agent.name} · ${agent.id}` : "Pick a session in the sidebar"}>
      {agent ? (
        <div className="max-w-xl rounded-lg border bg-card p-3">
          <SessionStatsList key={agent.id} sessionId={agent.id} />
        </div>
      ) : (
        <EmptyState icon={<BarChart3 className="size-6" />} title="No session selected" hint="Stats show the selected session's turns, tools, tokens and cost." />
      )}
    </TabPage>
  );
}
