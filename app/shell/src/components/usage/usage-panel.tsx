// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Usage tab: Plan limits (per provider) then the Pacing section.
import { RefreshCw } from "lucide-react";
import { pacing, pacingRule, providerUsage } from "@/mock/data";
import { Button } from "@infantry/ui";
import { IconButton } from "@infantry/ui";
import { SectionLabel } from "@infantry/ui";
import { PlanLimitsCard } from "./plan-limits-card";
import { PacingCard } from "./pacing-card";

export function UsagePanel() {
  return (
    <div className="space-y-3">
      <SectionLabel
        action={
          <span className="flex items-center gap-2">
            updated 2m ago
            <IconButton label="Refresh usage" size="icon-xs" variant="outline" className="rounded-full">
              <RefreshCw />
            </IconButton>
          </span>
        }
      >
        Plan limits
      </SectionLabel>
      {providerUsage.map((u) => (
        <PlanLimitsCard key={u.id} usage={u} />
      ))}

      <div className="border-t pt-2">
        <SectionLabel
          action={
            <span className="flex gap-1">
              <Button variant="ghost" size="xs">
                Refresh
              </Button>
              <Button variant="ghost" size="xs">
                Edit rule
              </Button>
            </span>
          }
        >
          Pacing
        </SectionLabel>
        <p className="px-1 pb-3 text-xs leading-5">{pacingRule}</p>
        <div className="space-y-3">
          {pacing.map((p) => (
            <PacingCard key={p.id} p={p} />
          ))}
        </div>
      </div>
    </div>
  );
}
