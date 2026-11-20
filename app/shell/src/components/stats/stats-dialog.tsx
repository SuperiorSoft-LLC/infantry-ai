// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The large Stats modal (the Stats button at the top right): one modal, tabs inside. Session and Tokens by day/week are
// the Kotlin Stats plugin's two views (stats-views.tsx); Pace is the pacing history and forecast.
import { AppDialog, Tabs, TabsContent, TabsList, TabsTrigger } from "@infantry/ui";
import { useAgent } from "@/daemon/agents";
import { useUi } from "@/state/ui-state";
import { PaceView } from "./pace-view";
import { SessionStatsView, UsageMeterView } from "./stats-views";

/** The tabs of the Stats modal, in order. */
export const STATS_TABS = [
  { id: "session", label: "Session" },
  { id: "tokens", label: "Tokens by day/week" },
  { id: "pace", label: "Pace" },
] as const;

export function StatsDialog({ onClose }: { onClose: () => void }) {
  const ui = useUi();
  const agent = useAgent(ui.selectedAgentId);
  return (
    <AppDialog
      open
      onOpenChange={(o) => !o && onClose()}
      title="Stats"
      description={agent ? `${agent.name} · ${agent.id}` : "Session numbers, tokens by day or week, and the pacing history of every plan window"}
      wide="full"
    >
      <Tabs defaultValue="session" className="min-h-[60vh]">
        <TabsList variant="line">
          {STATS_TABS.map((t) => (
            <TabsTrigger key={t.id} value={t.id}>
              {t.label}
            </TabsTrigger>
          ))}
        </TabsList>
        <TabsContent value="session" className="pt-2">
          <SessionStatsView sessionId={agent?.id ?? null} />
        </TabsContent>
        <TabsContent value="tokens" className="pt-2">
          <UsageMeterView />
        </TabsContent>
        <TabsContent value="pace" className="pt-2">
          <PaceView />
        </TabsContent>
      </Tabs>
    </AppDialog>
  );
}
