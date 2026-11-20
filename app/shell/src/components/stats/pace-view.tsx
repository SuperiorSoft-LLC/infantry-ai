// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The Pace tab of the Stats modal: the pacing rule and plan-account cards, then one chart card per provider window
// (history of percent used, pace delta, forecast to reset, momentum, burn rate) and what drives each meter (usage fit).
// Everything it says comes from the daemon's `pace_report` / `usage_fit`; the numbers are the Kotlin Pace plugin's.
import { useMemo, useState } from "react";
import { TrendingUp } from "lucide-react";
import {
  PACE_METRIC_LABEL, PACE_METRIC_TOGGLE, PACE_RANGES, paceCaption, paceScalePresets, paceShowsForecast, paceSpec, paceTrendLabel, parsePaceScale, ruleLine,
  type PaceMetric, type PaceRange, type PaceScale, type PaceSeries,
} from "@infantry/client";
import { Button, Chip, EmptyState, Input, PanelCard, ToggleGroup, ToggleGroupItem } from "@infantry/ui";
import { RefreshUsage, UsageMeters, UsageRefusal } from "@/components/usage/usage-panel";
import { useNow, useUsage } from "@/components/usage/use-usage";
import { PaceChart, PaceLegend } from "./pace-chart";
import { UsageFitSection } from "./usage-fit";
import { useKeepPaceFresh, usePace } from "./use-pace";

const TREND_TONE = { RISING: "red", FALLING: "green", FLAT: "neutral", UNKNOWN: "neutral" } as const;

function Segmented<T extends string>({ label, value, options, onChange }: { label: string; value: T; options: { id: T; label: string }[]; onChange: (v: T) => void }) {
  return (
    <div className="flex items-center gap-1.5">
      <span className="text-[11px] text-muted-foreground">{label}</span>
      <ToggleGroup type="single" variant="outline" size="sm" value={value} onValueChange={(v) => v && onChange(v as T)}>
        {options.map((o) => (
          <ToggleGroupItem key={o.id} value={o.id} aria-label={`${label} ${o.label}`}>
            {o.label}
          </ToggleGroupItem>
        ))}
      </ToggleGroup>
    </div>
  );
}

function PaceCard({ s, now }: { s: PaceSeries; now: number }) {
  const [metric, setMetric] = useState<PaceMetric>("pace");
  const [range, setRange] = useState<PaceRange>("window");
  const [scale, setScale] = useState<PaceScale>("fit");
  const [custom, setCustom] = useState("");
  const [customOpen, setCustomOpen] = useState(false);
  const spec = useMemo(() => paceSpec(s, metric, range, scale, now), [s, metric, range, scale, now]);
  const burn = useMemo(() => paceSpec(s, "burn", range, "fit", now), [s, range, now]);
  const parsed = parsePaceScale(custom);
  const presets = paceScalePresets(metric);
  return (
    <PanelCard
      title={`${s.provider} · ${s.window}`}
      action={<Chip tone={TREND_TONE[s.trend]}>{paceTrendLabel(s.trend)}</Chip>}
      className="min-w-0"
    >
      <p className="-mt-1 mb-2 text-xs text-muted-foreground">{paceCaption(s)}</p>
      <div className="mb-2 flex flex-wrap items-center gap-x-4 gap-y-2">
        <Segmented label="Show" value={metric} onChange={(m) => { setMetric(m); setScale("fit"); }} options={PACE_METRIC_TOGGLE.map((m) => ({ id: m, label: PACE_METRIC_LABEL[m] }))} />
        <Segmented label="Range" value={range} onChange={setRange} options={PACE_RANGES.map((r) => ({ id: r.id, label: r.label }))} />
        <div className="flex flex-wrap items-center gap-1.5">
          <span className="text-[11px] text-muted-foreground">Scale</span>
          <ToggleGroup type="single" variant="outline" size="sm" value={scale === "fit" ? "fit" : presets.includes(scale) ? String(scale) : "custom"} onValueChange={(v) => {
            if (!v) return;
            if (v === "fit") { setScale("fit"); setCustomOpen(false); }
            else if (v === "custom") setCustomOpen((o) => !o);
            else setScale(Number(v));
          }}>
            {presets.map((p) => (
              <ToggleGroupItem key={p} value={String(p)} aria-label={`Scale ${metric === "used" ? `${p}%` : `plus or minus ${p}`}`}>
                {metric === "used" ? `${p}%` : `±${p}`}
              </ToggleGroupItem>
            ))}
            <ToggleGroupItem value="fit">Fit</ToggleGroupItem>
            <ToggleGroupItem value="custom">Custom</ToggleGroupItem>
          </ToggleGroup>
          {scale !== "fit" && (
            <Button variant="ghost" size="sm" onClick={() => { setScale("fit"); setCustomOpen(false); }}>
              Reset to default
            </Button>
          )}
          {customOpen && (
            <span className="flex items-center gap-1.5">
              <Input value={custom} onChange={(e) => setCustom(e.target.value)} placeholder="e.g. 120" aria-label="Custom scale maximum" className="h-8 w-24" />
              <Button size="sm" disabled={parsed === null} onClick={() => parsed !== null && (setScale(parsed), setCustomOpen(false))}>
                Set
              </Button>
            </span>
          )}
        </div>
      </div>
      {spec.clipped && <p role="status" className="mb-1 text-xs text-warn">{spec.clipNote}</p>}
      <PaceChart sp={spec} metric={metric} height={220} label={`${s.provider} ${s.window} ${PACE_METRIC_LABEL[metric]} chart, ${spec.actual.reduce((n, l) => n + l.pts.length, 0)} readings, momentum ${paceTrendLabel(s.trend)}`} />
      <PaceLegend sp={spec} />
      <p className="mt-2 text-xs">
        <span className="text-muted-foreground">Forecast: </span>
        {s.forecast.sentence || "not enough data yet"}
      </p>
      {s.forecast.ready && !paceShowsForecast(range) && (
        <p className="text-[11px] text-muted-foreground">The dashed forecast lines are drawn in the 7d and Window ranges, where the reset fits on the chart.</p>
      )}
      <div className="mt-3 border-t pt-2">
        <h4 className="text-xs font-semibold">Burn rate</h4>
        <p className="mb-1 text-[11px] text-muted-foreground">
          Percent used gained per hour over a rolling window. The heavy line is 0: nothing being used. Idle, this sits on 0 while the pace delta above drifts down.
        </p>
        <PaceChart sp={burn} metric="burn" height={110} label={`${s.provider} ${s.window} burn rate chart`} />
      </div>
    </PanelCard>
  );
}

export function PaceView() {
  useKeepPaceFresh();
  const rule = useUsage((s) => s.report?.rule);
  const report = usePace((s) => s.report);
  const refusal = usePace((s) => s.refusal);
  const now = useNow(60_000);
  const providers = useMemo(() => [...new Set((report?.series ?? []).map((s) => s.provider))], [report]);
  return (
    <div className="space-y-3" data-testid="pace-view">
      <div className="flex items-start justify-between gap-3">
        <p className="text-xs text-muted-foreground">
          One card per provider window, from that provider's own meter. Pace delta = points ahead (+) or behind (−) an even burn through the window; the heavy line is even
          pace. Momentum is the slope of where you are projected to land at reset over a short look-back. Forecast = a projection, not a promise.
        </p>
        <RefreshUsage />
      </div>
      <PanelCard title="Rule">
        <p className="text-[13px]">{ruleLine(rule)}</p>
      </PanelCard>
      <UsageRefusal />
      <UsageMeters grid />
      {!report && !refusal && <EmptyState icon={<TrendingUp className="size-5" />} title="Reading the pacing history" hint="Waiting for the daemon's pace report." />}
      {!report && refusal && <EmptyState icon={<TrendingUp className="size-5" />} title="No pace report from the daemon" hint={refusal} />}
      {report && report.series.length === 0 && (
        <EmptyState icon={<TrendingUp className="size-5" />} title="No pacing history recorded yet" hint="The daemon samples the plan meters about every minute; the first charts appear after a few readings." />
      )}
      {report && report.series.length > 0 && (
        <div className="grid grid-cols-[repeat(auto-fit,minmax(420px,1fr))] gap-3">
          {report.series.map((s) => (
            <PaceCard key={`${s.provider}/${s.window}`} s={s} now={Math.max(now, report.generatedAt)} />
          ))}
        </div>
      )}
      {providers.map((p) => (
        <UsageFitSection key={p} provider={p} />
      ))}
    </div>
  );
}
