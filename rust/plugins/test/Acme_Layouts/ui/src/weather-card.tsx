// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// A 5-day forecast: one tile per day (day, icon, high/low, short text). Wraps to fewer columns on a phone.
import { Cloud, CloudLightning, CloudRain, CloudSun, Snowflake, Sun, type LucideIcon } from "lucide-react";

const ICONS: Record<string, LucideIcon> = { sun: Sun, partly: CloudSun, cloud: Cloud, rain: CloudRain, storm: CloudLightning, snow: Snowflake };
const str = (v: unknown) => (typeof v === "string" ? v : typeof v === "number" ? String(v) : "");

export function WeatherCard({ body }: { body: Record<string, unknown> }) {
  const days = (Array.isArray(body.days) ? body.days : []).filter((d): d is Record<string, unknown> => !!d && typeof d === "object").slice(0, 7);
  const unit = str(body.unit);
  return (
    <div className="rounded-lg border bg-card p-3 text-[13px]" data-card="acme-weather">
      <div className="text-[10px] tracking-wide text-muted-foreground uppercase">Forecast</div>
      {str(body.place) && <div className="font-semibold">{str(body.place)}</div>}
      <ul className="mt-2 grid grid-cols-[repeat(auto-fit,minmax(4.5rem,1fr))] gap-2">
        {days.map((d, i) => {
          const Icon = ICONS[str(d.icon)] ?? Cloud;
          return (
            <li key={i} className="flex flex-col items-center gap-1 rounded-md border p-2 text-center">
              <span className="text-xs font-medium">{str(d.day)}</span>
              <Icon className="size-7 text-info" aria-label={str(d.icon) || "weather"} />
              <span className="text-xs">
                <b>{str(d.high)}{unit}</b> <span className="text-muted-foreground">{str(d.low)}{unit}</span>
              </span>
              <span className="line-clamp-2 text-[11px] text-muted-foreground">{str(d.text)}</span>
            </li>
          );
        })}
      </ul>
    </div>
  );
}
