// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Per-provider pacing card: usage, workers running, a spaced "Free slot" chip, Type % / Override.
import { Minus } from "lucide-react";
import type { PacingProvider } from "@/mock/types";
import { Button } from "@infantry/ui";
import { Chip } from "@infantry/ui";
import { PanelCard } from "@infantry/ui";

export function PacingCard({ p }: { p: PacingProvider }) {
  return (
    <PanelCard
      title={p.name}
      action={
        p.percent !== undefined ? (
          <span className="flex items-baseline gap-2 text-xs">
            <span className="text-[15px] font-semibold">{p.percent}%</span>
            <span className="text-muted-foreground">{p.projected}</span>
          </span>
        ) : (
          <Minus className="size-4 text-muted-foreground" aria-hidden />
        )
      }
    >
      <div className="space-y-2.5 text-xs">
        <p className="leading-5 text-muted-foreground">
          {p.detail}
          {p.updated && <> · {p.updated}</>}
        </p>
        <div className="flex flex-wrap items-center gap-2">
          <span>
            {p.workers} workers running · {p.limit}
          </span>
          {p.freeSlot && <Chip tone="green">Free slot</Chip>}
        </div>
        {p.warning && <p className={p.percent !== undefined ? "leading-5 text-danger" : "leading-5 text-muted-foreground"}>{p.warning}</p>}
        <div className="flex gap-2 pt-1">
          <Button variant="outline" size="sm">
            {p.setLabel}
          </Button>
          <Button variant="outline" size="sm">
            Override
          </Button>
        </div>
      </div>
    </PanelCard>
  );
}
