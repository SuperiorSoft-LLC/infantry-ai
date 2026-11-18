// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Thin progress line on agent cards: determinate fill, or animated stripes when the value is unknown.
import { cn } from "../lib/utils";

export function ProgressLine({ value, className }: { value: number | null; className?: string }) {
  return (
    <div
      role="progressbar"
      aria-valuenow={value ?? undefined}
      aria-valuemin={0}
      aria-valuemax={100}
      className={cn("h-1 w-full overflow-hidden rounded-full bg-raised", className)}
    >
      {value === null ? (
        <div className="stripes-info h-full w-full" />
      ) : (
        <div className="h-full rounded-full bg-info" style={{ width: `${value}%` }} />
      )}
    </div>
  );
}
