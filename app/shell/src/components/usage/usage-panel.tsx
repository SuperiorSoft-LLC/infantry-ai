// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Usage tab: the pacing rule, then one card per plan account (live from the daemon's usage_report).
import { useEffect, useState } from "react";
import { Gauge, RefreshCw, X } from "lucide-react";
import { meterName, ruleLine, unmeteredHarnesses, updatedLine } from "@infantry/client";
import { EmptyState, IconButton, PanelCard, SectionLabel, cn } from "@infantry/ui";
import { PacingCard } from "./pacing-card";
import { daemonNote } from "@/daemon/daemon";
import { useLauncher } from "@/components/dialogs/new-agent-dialog";
import { useNow, useUsage } from "./use-usage";

/** How long the first usage report may take before the panel says the daemon is not answering. */
const USAGE_WAIT_MS = 8_000;

export function UsageMeters({ grid }: { grid?: boolean }) {
  const report = useUsage((s) => s.report);
  const ensureLoaded = useUsage((s) => s.ensureLoaded);
  const now = useNow();
  const { harnesses } = useLauncher();
  useEffect(() => ensureLoaded(), [ensureLoaded]);
  const [waited, setWaited] = useState(false);
  useEffect(() => {
    if (report) return;
    const id = setTimeout(() => setWaited(true), USAGE_WAIT_MS);
    return () => clearTimeout(id);
  }, [report]);
  if (!report && waited)
    return (
      <EmptyState
        icon={<Gauge className="size-5" />}
        title="No usage report from the daemon"
        hint={daemonNote ?? "The daemon did not answer usage_get. It may be older than this app (restart it), or this build does not serve usage yet."}
      />
    );
  if (!report) return <EmptyState icon={<Gauge className="size-5" />} title="Reading usage" hint="Waiting for the daemon's first usage report." />;
  // Harnesses with no plan-limit meter still get a card that says why (their `usageNote` on the `providers` row).
  const unmetered = unmeteredHarnesses(harnesses, report.meters);
  if (report.meters.length === 0 && unmetered.length === 0)
    return <EmptyState icon={<Gauge className="size-5" />} title="No plan accounts" hint="No live usage source answered and no ticket worker is running." />;
  return (
    <div className={grid ? "grid grid-cols-[repeat(auto-fill,minmax(300px,1fr))] gap-3" : "space-y-3"}>
      {report.meters.map((m) => (
        <PacingCard key={m.meter} m={m} now={now} />
      ))}
      {unmetered.map((h) => (
        <PanelCard key={h.id} title={meterName(h.id)} action={<span className="text-xs text-muted-foreground">no plan limit read</span>}>
          <p className="text-xs leading-5 text-muted-foreground">{h.usageNote}</p>
        </PanelCard>
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
