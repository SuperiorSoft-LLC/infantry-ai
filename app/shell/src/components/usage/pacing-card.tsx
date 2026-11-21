// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// One plan account: % used and where it lands at reset on an animated meter, pace and reset, workers against the
// pacing allowance with a Free slot chip, the override line, every window of the plan, and Set % / Override.
import { useState } from "react";
import { meterLines, meterName, overrideLine, paceWords, resetLine, workersLine, type MeterUsage } from "@infantry/client";
import { Button, Chip, MeterBar, PanelCard } from "@infantry/ui";
import { OverrideDialog, TypePercentDialog } from "./usage-dialogs";
import { useUsage } from "./use-usage";

/** The even-pace position: the elapsed share of the window (percent minus the delta). */
const pacePoint = (percent: number | null, delta: number | null): number | null => (percent == null || delta == null ? null : percent - delta);

export function PacingCard({ m, now }: { m: MeterUsage; now: number }) {
  const [dialog, setDialog] = useState<"type" | "override" | null>(null);
  const clearOverride = useUsage((s) => s.clearOverride);
  const limits = useUsage((s) => s.limits);
  const pace = paceWords(m.paceDelta);
  const ahead = (m.paceDelta ?? 0) > 0;
  const free = m.allowance == null ? null : Math.max(0, m.allowance - m.running);
  const over = overrideLine(m, now);
  const lines = meterLines(m, limits, now);
  const extra = m.windows.filter((w) => w.label !== m.window);
  return (
    <PanelCard
      title={meterName(m.meter)}
      action={
        <span className="flex items-baseline gap-2 text-xs">
          {m.percent != null ? (
            <>
              <span className="text-[15px] font-semibold tabular-nums">{Math.round(m.percent)}%</span>
              {m.window && <span className="text-muted-foreground">{m.window}</span>}
            </>
          ) : (
            <span className="text-muted-foreground">no reading</span>
          )}
        </span>
      }
    >
      <div className="space-y-3 text-xs">
        <div className="space-y-1.5">
          <MeterBar value={m.percent} projected={m.projectedPercent} pace={pacePoint(m.percent, m.paceDelta)} label={`${meterName(m.meter)} usage`} />
          {(lines.headline || pace) && (
            <div className="flex items-baseline justify-between gap-3 text-[11px]">
              <span className="text-muted-foreground">{lines.headline}</span>
              {pace && <span className={ahead ? "text-warn" : "text-ok"}>{pace}</span>}
            </div>
          )}
          {lines.detail && <p className="text-[11px] text-muted-foreground">{lines.detail}</p>}
          {lines.note && (
            <p className={m.percent != null ? "text-[11px] leading-4 text-warn" : "text-[11px] leading-4 text-muted-foreground"} role="status">
              {lines.note}
            </p>
          )}
        </div>

        <div className="flex flex-wrap items-center gap-2">
          <span className="tabular-nums">{workersLine(m)}</span>
          {free == null || free > 0 ? <Chip tone="green">Free slot</Chip> : <Chip tone="amber">No free slot</Chip>}
        </div>

        {over && (
          <div className="flex items-start justify-between gap-2 rounded-md border border-info/40 bg-info/5 px-2 py-1.5 text-info">
            <span className="leading-5">{over}</span>
            <Button variant="ghost" size="xs" onClick={() => clearOverride(m.meter)}>
              Clear
            </Button>
          </div>
        )}
        {!over && m.reasoning && <p className="leading-5 text-muted-foreground">pacing: {m.reasoning}</p>}
        {lines.warning && <p className={m.percent != null ? "leading-5 text-danger" : "leading-5 text-muted-foreground"}>{lines.warning}</p>}

        {extra.length > 0 && (
          <div className="space-y-2 border-t pt-2">
            {extra.map((w) => (
              <div key={w.label} className="space-y-1">
                <div className="flex items-baseline justify-between gap-3 text-[11px]">
                  <span>{w.label}</span>
                  <span className="font-medium tabular-nums">{w.percent == null ? "?" : `${Math.round(w.percent)}%`}</span>
                </div>
                <MeterBar size="sm" value={w.percent} pace={pacePoint(w.percent, w.paceDelta)} label={w.label} />
                <div className="flex justify-between gap-3 text-[10px] text-muted-foreground">
                  <span>{resetLine(w.resetsAt)}</span>
                  <span>{paceWords(w.paceDelta)}</span>
                </div>
              </div>
            ))}
          </div>
        )}

        <div className="flex gap-2 pt-1">
          <Button variant="outline" size="sm" onClick={() => setDialog("type")}>
            Set %
          </Button>
          <Button variant="outline" size="sm" onClick={() => setDialog("override")}>
            Override
          </Button>
        </div>
      </div>
      <TypePercentDialog meter={m} open={dialog === "type"} onClose={() => setDialog(null)} />
      <OverrideDialog meter={m} open={dialog === "override"} onClose={() => setDialog(null)} />
    </PanelCard>
  );
}
