// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Pace: the pacing rule and the plan-account cards at full width (reuses the usage components).
import { ruleLine } from "@infantry/client";
import { PanelCard } from "@infantry/ui";
import { RefreshUsage, UsageMeters, UsageRefusal } from "@/components/usage/usage-panel";
import { useUsage } from "@/components/usage/use-usage";
import { TabPage } from "./tab-page";

export function PaceTab() {
  const rule = useUsage((s) => s.report?.rule);
  return (
    <TabPage title="Pace" description="How many ticket workers run, by plan usage" actions={<RefreshUsage />}>
      <div className="space-y-3">
        <PanelCard title="Rule">
          <p className="text-[13px]">{ruleLine(rule)}</p>
        </PanelCard>
        <UsageRefusal />
        <UsageMeters grid />
      </div>
    </TabPage>
  );
}
