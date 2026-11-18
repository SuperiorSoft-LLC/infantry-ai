// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// One provider's plan limits: each limit is a label, percent, segmented bar, reset time and pace.
import { Minus } from "lucide-react";
import type { ProviderUsage } from "@/mock/types";
import { PanelCard } from "@infantry/ui";
import { SegmentedBar } from "@infantry/ui";

export function PlanLimitsCard({ usage }: { usage: ProviderUsage }) {
  return (
    <PanelCard title={usage.name} action={!usage.signedIn && <Minus className="size-4 text-muted-foreground" aria-hidden />}>
      {usage.limits.length === 0 ? (
        <p className="text-xs text-muted-foreground">{usage.note ?? "no usage data"}</p>
      ) : (
        <div className="space-y-4">
          {usage.limits.map((l) => (
            <div key={l.label} className="space-y-2">
              <div className="flex items-baseline justify-between gap-3 text-[13px]">
                <span>{l.label}</span>
                <span className="font-semibold">{l.percent}%</span>
              </div>
              <SegmentedBar percent={l.percent} />
              <div className="flex items-baseline justify-between gap-3 text-[11px]">
                <span className="text-muted-foreground">{l.resets}</span>
                <span className="text-ok">{l.underPace}</span>
              </div>
            </div>
          ))}
        </div>
      )}
    </PanelCard>
  );
}
