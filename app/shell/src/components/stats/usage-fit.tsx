// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// USAGE-FIT: what drives a plan meter, from the daemon's `usage_fit` (tokens per point of percent used, which token type
// explains the steps best, who spent the cache-read tokens). Read-only; says so honestly when there are too few steps.
import { useEffect } from "react";
import { meterName, type UsageFitWindow } from "@infantry/client";
import { PanelCard } from "@infantry/ui";
import { usePace } from "./use-pace";

const n0 = (v: number): string => Math.round(v).toLocaleString("en-US");
const pct = (v: number): string => `${Math.round(v * 100)}%`;

function Window({ w }: { w: UsageFitWindow }) {
  const title = `${w.label} (${w.kind})`;
  if (!w.enough || !w.fit) {
    return (
      <div>
        <h4 className="text-xs font-semibold">{title}</h4>
        <p className="text-xs text-muted-foreground">
          Not enough data yet: {w.steps} of the {w.minSteps} steps needed ({w.pointsTotal.toFixed(0)} points of percent seen). Each rise of the meter is one step; the fit appears after a few more.
        </p>
      </div>
    );
  }
  const f = w.fit;
  const driver = f.tokensPerPoint.filter((t) => t.value !== null);
  return (
    <div className="space-y-2">
      <h4 className="text-xs font-semibold">{title}</h4>
      <p className="text-xs">
        {w.steps} steps, {w.pointsTotal.toFixed(0)} points of percent. Best explained by <b>{f.best.label}</b> (R² {f.r2.toFixed(2)}, typical miss {f.residualSd.toFixed(2)} points).
        {driver.length > 0 && <> One point costs about {driver.map((t) => `${n0(t.value as number)} ${t.name}`).join(", ")}.</>}
        {f.backgroundShare !== null && f.backgroundPerHour !== null && (
          <> About {pct(f.backgroundShare)} of the usage is not in any request this daemon saw ({f.backgroundPerHour.toFixed(2)} points an hour of other use on the account).</>
        )}
      </p>
      {w.cacheReadPerPoint !== null && (
        <p className="text-[11px] text-muted-foreground">
          Over all steps: {n0(w.cacheReadPerPoint)} cache-read tokens{w.requestsPerPoint !== null && <> and {w.requestsPerPoint.toFixed(1)} requests</>} per point.
        </p>
      )}
      <table className="w-full text-xs">
        <thead className="text-left text-muted-foreground">
          <tr>
            <th className="py-0.5 pr-2 font-normal">Candidate fit</th>
            <th className="px-2 text-right font-normal">R²</th>
            <th className="pl-2 text-right font-normal">Adjusted</th>
          </tr>
        </thead>
        <tbody>
          {f.candidates.map((c) => (
            <tr key={c.key} className={c.key === f.best.key ? "font-semibold" : ""}>
              <td className="py-0.5 pr-2">{c.label}</td>
              <td className="px-2 text-right tabular-nums">{c.r2.toFixed(3)}</td>
              <td className="pl-2 text-right tabular-nums">{c.adjR2.toFixed(3)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {w.groups.length > 0 && (
        <table className="w-full text-xs">
          <thead className="text-left text-muted-foreground">
            <tr>
              <th className="py-0.5 pr-2 font-normal">Role · model</th>
              <th className="px-2 text-right font-normal">Share of cache-read</th>
              <th className="px-2 text-right font-normal">Requests in steps</th>
              <th className="pl-2 text-right font-normal">Requests in total</th>
            </tr>
          </thead>
          <tbody>
            {w.groups.map((g) => (
              <tr key={`${g.role}/${g.model}`}>
                <td className="py-0.5 pr-2">
                  {g.role} · {g.model}
                </td>
                <td className="px-2 text-right tabular-nums">{pct(g.share)}</td>
                <td className="px-2 text-right tabular-nums">{n0(g.requests)}</td>
                <td className="pl-2 text-right tabular-nums">{n0(g.totalRequests)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}

export function UsageFitSection({ provider }: { provider: string }) {
  const load = usePace((s) => s.loadFit);
  const fit = usePace((s) => s.fit[provider]);
  useEffect(() => load(provider), [load, provider]);
  if (!fit || fit.windows.length === 0) return null;
  return (
    <PanelCard title={`What drives ${meterName(provider)} usage`}>
      <div className="space-y-4">
        {fit.windows.map((w) => (
          <Window key={w.kind} w={w} />
        ))}
      </div>
    </PanelCard>
  );
}
