// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// A usage meter: the used share fills in (animated from 0 on mount, eased on every change), a fainter band runs on to
// where the burn lands at reset, and a thin tick marks the even pace. Tone follows the landing, not just the use.
import { useEffect, useState } from "react";
import { cn } from "../lib/utils";

const clamp = (v: number): number => Math.max(0, Math.min(100, v));

export function meterTone(used: number, projected?: number | null): "ok" | "warn" | "danger" {
  const worst = Math.max(used, projected ?? 0);
  return used >= 90 || worst >= 110 ? "danger" : used >= 75 || worst >= 100 ? "warn" : "ok";
}

const fill = { ok: "bg-ok", warn: "bg-warn", danger: "bg-danger" } as const;
const band = { ok: "bg-ok/25", warn: "bg-warn/25", danger: "bg-danger/25" } as const;

export function MeterBar({
  value,
  projected,
  pace,
  size = "md",
  label,
  className,
}: {
  /** Percent used, 0..100; null = unknown (an idle striped track). */
  value: number | null;
  /** Percent projected at reset (may pass 100: the band then fills to the end). */
  projected?: number | null;
  /** Even-pace position (the elapsed share of the window), 0..100. */
  pace?: number | null;
  size?: "sm" | "md";
  label?: string;
  className?: string;
}) {
  const [shown, setShown] = useState(0);
  useEffect(() => {
    const id = requestAnimationFrame(() => setShown(value ?? 0));
    return () => cancelAnimationFrame(id);
  }, [value]);
  const tone = meterTone(value ?? 0, projected);
  const ahead = projected != null && value != null ? clamp(projected) - clamp(shown) : 0;
  return (
    <div
      role="meter"
      aria-label={label}
      aria-valuenow={value ?? undefined}
      aria-valuemin={0}
      aria-valuemax={100}
      className={cn("relative w-full overflow-hidden rounded-full bg-raised", size === "sm" ? "h-1.5" : "h-2.5", className)}
    >
      {value === null ? (
        <div className="stripes-info h-full w-full opacity-40" />
      ) : (
        <>
          {ahead > 0 && (
            <div
              className={cn("absolute inset-y-0 transition-all duration-700 ease-out", band[tone])}
              style={{ left: `${clamp(shown)}%`, width: `${ahead}%` }}
            />
          )}
          <div
            className={cn("absolute inset-y-0 left-0 rounded-full transition-[width,background-color] duration-700 ease-out", fill[tone])}
            style={{ width: `${clamp(shown)}%` }}
          />
          {pace != null && (
            <div
              aria-hidden
              className="absolute -inset-y-px w-px bg-foreground/50 transition-[left] duration-700 ease-out"
              style={{ left: `${clamp(pace)}%` }}
            />
          )}
        </>
      )}
    </div>
  );
}
