// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Pace: the pacing rule and the plan-account cards at full width (reuses the usage components).
import { ruleLine } from "@infantry/client";
import { PanelCard } from "@infantry/ui";
import { RefreshUsage, UsageMeters, UsageRefusal } from "@/components/usage/usage-panel";
import { useUsage } from "@/components/usage/use-usage";
import { TabPage } from "./tab-page";

/** The pacing rule and the plan-account cards: the Pace tab's body, also the Stats modal's Pace tab. */
export function PaceBody() {
  const rule = useUsage((s) => s.report?.rule);
  return (
    <div className="space-y-3">
      <PanelCard title="Rule">
        <p className="text-[13px]">{ruleLine(rule)}</p>
      </PanelCard>
      <UsageRefusal />
      <UsageMeters grid />
    </div>
  );
}

export function PaceTab() {
  return (
    <TabPage title="Pace" description="How many ticket workers run, by plan usage" actions={<RefreshUsage />}>
      <PaceBody />
    </TabPage>
  );
}
