// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Pace: the pacing rule and provider cards at full width (reuses the usage components).
import { pacing, pacingRule, providerUsage } from "@/mock/data";
import { Button } from "@infantry/ui";
import { PanelCard } from "@infantry/ui";
import { PacingCard } from "@/components/usage/pacing-card";
import { PlanLimitsCard } from "@/components/usage/plan-limits-card";
import { TabPage } from "./tab-page";

export function PaceTab() {
  return (
    <TabPage
      title="Pace"
      description="How many ticket workers run, by provider usage"
      actions={
        <Button variant="outline" size="sm">
          Edit rule
        </Button>
      }
    >
      <div className="space-y-3">
        <PanelCard title="Rule">
          <p className="text-[13px]">{pacingRule}</p>
        </PanelCard>
        <div className="grid grid-cols-[repeat(auto-fill,minmax(300px,1fr))] gap-3">
          {providerUsage.map((u) => (
            <PlanLimitsCard key={u.id} usage={u} />
          ))}
          {pacing.map((p) => (
            <PacingCard key={p.id} p={p} />
          ))}
        </div>
      </div>
    </TabPage>
  );
}
