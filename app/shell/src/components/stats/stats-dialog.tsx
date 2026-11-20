// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The large Stats modal (the Stats button at the top right): one modal, tabs inside. The Session tab is the selected
// session's numbers; the Pace tab is the pacing history and forecast. Other stat views join as further tabs.
import { AppDialog, Tabs, TabsContent, TabsList, TabsTrigger } from "@infantry/ui";
import { SessionStatsList } from "@/components/session/session-actions";
import { useAgent } from "@/daemon/agents";
import { useUi } from "@/state/ui-state";
import { PaceView } from "./pace-view";

/** The tabs of the Stats modal, in order. */
export const STATS_TABS = [
  { id: "session", label: "Session" },
  { id: "pace", label: "Pace" },
] as const;

function SessionStats() {
  const ui = useUi();
  const agent = useAgent(ui.selectedAgentId);
  if (!agent) return <p className="text-sm text-muted-foreground">Pick a session in the sidebar: its turns, tools, tokens and cost show here.</p>;
  return (
    <div className="max-w-xl rounded-lg border bg-card p-3">
      <p className="mb-2 text-xs text-muted-foreground">
        {agent.name} · {agent.id}
      </p>
      <SessionStatsList key={agent.id} sessionId={agent.id} />
    </div>
  );
}

export function StatsDialog({ onClose }: { onClose: () => void }) {
  return (
    <AppDialog open onOpenChange={(o) => !o && onClose()} title="Stats" description="Session numbers and the pacing history of every plan window" wide="full">
      <Tabs defaultValue="pace" className="min-h-[60vh]">
        <TabsList variant="line">
          {STATS_TABS.map((t) => (
            <TabsTrigger key={t.id} value={t.id}>
              {t.label}
            </TabsTrigger>
          ))}
        </TabsList>
        <TabsContent value="session" className="pt-2">
          <SessionStats />
        </TabsContent>
        <TabsContent value="pace" className="pt-2">
          <PaceView />
        </TabsContent>
      </Tabs>
    </AppDialog>
  );
}
