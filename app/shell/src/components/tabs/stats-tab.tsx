// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Stats: KPI tiles plus a simple per-day bar strip.
import { stats } from "@/mock/data";
import { PanelCard } from "@infantry/ui";
import { TabPage } from "./tab-page";

const week = [12, 18, 9, 22, 30, 14, 6];
const days = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];

export function StatsTab() {
  const max = Math.max(...week);
  return (
    <TabPage title="Stats" description="This week, all agents">
      <div className="space-y-3">
        <div className="grid grid-cols-[repeat(auto-fill,minmax(180px,1fr))] gap-3">
          {stats.map((s) => (
            <PanelCard key={s.label}>
              <div className="text-xs text-muted-foreground">{s.label}</div>
              <div className="mt-1 flex items-baseline gap-2">
                <span className="text-2xl font-semibold">{s.value}</span>
                <span className="text-xs text-ok">{s.delta}</span>
              </div>
            </PanelCard>
          ))}
        </div>
        <PanelCard title="Tickets done per day">
          <div className="flex h-40 items-end gap-3" role="img" aria-label="Tickets done per day this week">
            {week.map((v, i) => (
              <div key={days[i]} className="flex flex-1 flex-col items-center gap-1">
                <span className="text-[11px] text-muted-foreground">{v}</span>
                <div className="w-full rounded-t bg-danger/80" style={{ height: `${(v / max) * 100}%` }} />
                <span className="text-[11px] text-muted-foreground">{days[i]}</span>
              </div>
            ))}
          </div>
        </PanelCard>
      </div>
    </TabPage>
  );
}
