// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Usage tab: the pacing rule, then one card per plan account (live from the daemon's usage_report).
import { useEffect } from "react";
import { Gauge, RefreshCw, X } from "lucide-react";
import { ruleLine, updatedLine } from "@infantry/client";
import { EmptyState, IconButton, SectionLabel, cn } from "@infantry/ui";
import { PacingCard } from "./pacing-card";
import { useNow, useUsage } from "./use-usage";

export function UsageMeters({ grid }: { grid?: boolean }) {
  const report = useUsage((s) => s.report);
  const ensureLoaded = useUsage((s) => s.ensureLoaded);
  const now = useNow();
  useEffect(() => ensureLoaded(), [ensureLoaded]);
  if (!report) return <EmptyState icon={<Gauge className="size-5" />} title="Reading usage" hint="Waiting for the daemon's first usage report." />;
  if (report.meters.length === 0) return <EmptyState icon={<Gauge className="size-5" />} title="No plan accounts" hint="No live usage source answered and no ticket worker is running." />;
  return (
    <div className={grid ? "grid grid-cols-[repeat(auto-fill,minmax(300px,1fr))] gap-3" : "space-y-3"}>
      {report.meters.map((m) => (
        <PacingCard key={m.meter} m={m} now={now} />
      ))}
    </div>
  );
}

export function UsageRefusal() {
  const refusal = useUsage((s) => s.refusal);
  const clear = useUsage((s) => s.clearRefusal);
  if (!refusal) return null;
  return (
    <p role="alert" className="flex items-start justify-between gap-2 rounded-md border border-danger/50 bg-danger/10 px-2 py-1.5 text-xs text-danger">
      <span>{refusal}</span>
      <IconButton label="Dismiss" size="icon-xs" variant="ghost" onClick={clear}>
        <X />
      </IconButton>
    </p>
  );
}

export function RefreshUsage() {
  const refreshing = useUsage((s) => s.refreshing);
  const refresh = useUsage((s) => s.refresh);
  return (
    <IconButton label="Refresh usage" size="icon-xs" variant="outline" className="rounded-full" onClick={refresh} disabled={refreshing}>
      <RefreshCw className={cn(refreshing && "animate-spin")} />
    </IconButton>
  );
}

export function UsagePanel() {
  const report = useUsage((s) => s.report);
  const receivedAt = useUsage((s) => s.receivedAt);
  const now = useNow();
  const updated = updatedLine(report?.meters.find((m) => m.updatedAt != null)?.updatedAt ?? receivedAt, now);
  return (
    <div className="space-y-3">
      <SectionLabel
        action={
          <span className="flex items-center gap-2">
            {updated}
            <RefreshUsage />
          </span>
        }
      >
        Pacing
      </SectionLabel>
      <p className="px-1 text-xs leading-5 text-muted-foreground">{ruleLine(report?.rule)}</p>
      <UsageRefusal />
      <UsageMeters />
    </div>
  );
}
