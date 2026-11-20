// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// One pace chart: the lines of a PaceChartSpec drawn as SVG. The model (client/stores/pace.ts) decides every number;
// this only maps time and value to the box. Hover or tap reads the exact value and time in words.
import { useRef, useState } from "react";
import { paceFmt, paceNearest, paceTime, paceTooltip, paceY, type PaceChartSpec, type PaceLine, type PaceMetric } from "@infantry/client";

const W = 1000;
const H = 100;

function xy(sp: PaceChartSpec, [t, v]: [number, number]): string {
  const span = Math.max(sp.t1 - sp.t0, 1);
  return `${(((t - sp.t0) / span) * W).toFixed(1)},${((1 - paceY(sp, v)) * H).toFixed(1)}`;
}

function path(line: PaceLine, sp: PaceChartSpec): string {
  return line.pts.map((p, i) => `${i ? "L" : "M"}${xy(sp, p)}`).join(" ");
}

/** The shaded band between the two forecast landings: out along one line, back along the other. */
function band(cur: PaceLine, rec: PaceLine, sp: PaceChartSpec): string {
  return `M${xy(sp, cur.pts[0])} L${xy(sp, cur.pts[1])} L${xy(sp, rec.pts[1])} L${xy(sp, rec.pts[0])} Z`;
}

const yPct = (sp: PaceChartSpec, v: number) => `${(1 - paceY(sp, v)) * 100}%`;

function tickLabel(metric: PaceMetric, v: number): string {
  if (metric === "pace" && v === 0) return "0 even";
  if (metric === "burn" && v === 0) return "0 idle";
  if (metric === "used" && v === 100) return "100 cap";
  return paceFmt(v) + (metric === "used" ? "%" : "");
}

export function PaceChart({ sp, metric, height, label }: { sp: PaceChartSpec; metric: PaceMetric; height: number; label: string }) {
  const box = useRef<HTMLDivElement>(null);
  const [hit, setHit] = useState<[number, number] | null>(null);
  if (sp.empty) return <p className="py-6 text-center text-xs text-muted-foreground">{sp.emptyText}</p>;
  const span = Math.max(sp.t1 - sp.t0, 1);
  const withDate = span > 20 * 3_600_000;
  const at = (e: React.PointerEvent) => {
    const r = box.current?.getBoundingClientRect();
    if (!r || r.width === 0) return;
    setHit(paceNearest(sp, sp.t0 + ((e.clientX - r.left) / r.width) * span));
  };
  return (
    <div>
      <div className="flex gap-2">
        <div className="relative w-14 shrink-0 text-right text-[10px] text-muted-foreground" style={{ height }}>
          {sp.ticks.map((v) => (
            <span key={v} className="absolute right-0 -translate-y-1/2 whitespace-nowrap" style={{ top: yPct(sp, v) }}>
              {tickLabel(metric, v)}
            </span>
          ))}
        </div>
        <div
          ref={box}
          role="img"
          aria-label={label}
          className="relative min-w-0 flex-1 touch-none rounded border bg-background"
          style={{ height }}
          onPointerMove={at}
          onPointerDown={at}
          onPointerLeave={() => setHit(null)}
        >
          <svg viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" className="absolute inset-0 size-full overflow-visible">
            {sp.ticks.map((v) => (
              <line key={v} x1={0} x2={W} y1={(1 - paceY(sp, v)) * H} y2={(1 - paceY(sp, v)) * H} className="stroke-border" strokeWidth={1} vectorEffect="non-scaling-stroke" />
            ))}
            {sp.zeroLine && <line x1={0} x2={W} y1={(1 - paceY(sp, 0)) * H} y2={(1 - paceY(sp, 0)) * H} className="stroke-foreground" strokeWidth={2} vectorEffect="non-scaling-stroke" />}
            {sp.capLine !== null && (
              <line x1={0} x2={W} y1={(1 - paceY(sp, sp.capLine)) * H} y2={(1 - paceY(sp, sp.capLine)) * H} className="stroke-danger" strokeWidth={1.5} strokeDasharray="6 4" vectorEffect="non-scaling-stroke" />
            )}
            {sp.hasBand && sp.currentForecast && sp.recentForecast && (
              <path d={band(sp.currentForecast, sp.recentForecast, sp)} className="fill-info/15" />
            )}
            {sp.currentForecast && <path d={path(sp.currentForecast, sp)} fill="none" className="stroke-danger" strokeWidth={2} strokeDasharray="8 6" vectorEffect="non-scaling-stroke" />}
            {sp.recentForecast && <path d={path(sp.recentForecast, sp)} fill="none" className="stroke-info" strokeWidth={2} strokeDasharray="2 5" vectorEffect="non-scaling-stroke" />}
            {sp.actual.map((l, i) => (
              <path key={i} d={path(l, sp)} fill="none" className="stroke-foreground" strokeWidth={2.25} strokeLinejoin="round" vectorEffect="non-scaling-stroke" />
            ))}
          </svg>
          {hit && (
            <span
              className="pointer-events-none absolute size-2.5 -translate-x-1/2 -translate-y-1/2 rounded-full border-2 border-background bg-foreground"
              style={{ left: `${((hit[0] - sp.t0) / span) * 100}%`, top: yPct(sp, hit[1]) }}
            />
          )}
        </div>
      </div>
      <div className="ml-16 mt-1 flex justify-between text-[10px] text-muted-foreground">
        <span>{paceTime(sp.t0, withDate)}</span>
        <span>{paceTime(sp.t1, withDate)}</span>
      </div>
      <p className="ml-16 mt-1 min-h-4 text-xs">{hit ? paceTooltip(metric, hit[0], hit[1]) : <span className="text-muted-foreground">Hover or tap the chart for an exact value and time.</span>}</p>
    </div>
  );
}

export function PaceLegend({ sp }: { sp: PaceChartSpec }) {
  if (sp.empty) return null;
  const item = (cls: string, dash: string | undefined, text: string) => (
    <span className="inline-flex items-center gap-1.5">
      <svg width="22" height="6" aria-hidden>
        <line x1="0" x2="22" y1="3" y2="3" className={cls} strokeWidth="2" strokeDasharray={dash} />
      </svg>
      {text}
    </span>
  );
  return (
    <div className="mt-1 flex flex-wrap items-center gap-x-4 gap-y-1 text-[10px] text-muted-foreground">
      {item("stroke-foreground", undefined, "actual")}
      {sp.currentForecast && item("stroke-danger", "8 6", "dashed: at the current rate")}
      {sp.recentForecast && item("stroke-info", "2 5", "dotted: at the recent rate")}
      {sp.zeroLine && <span>heavy line: even pace</span>}
      {sp.capLine !== null && <span>dashed top line: 100% cap</span>}
    </div>
  );
}
