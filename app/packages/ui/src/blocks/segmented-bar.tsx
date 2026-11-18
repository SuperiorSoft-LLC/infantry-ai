// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Plan-limit style bar made of small segments (LimitsSection.kt).
import { cn } from "../lib/utils";

export function SegmentedBar({ percent, segments = 24, className }: { percent: number; segments?: number; className?: string }) {
  const filled = Math.round((percent / 100) * segments);
  const tone = percent >= 90 ? "bg-danger" : percent >= 75 ? "bg-warn" : "bg-ok";
  return (
    <div role="progressbar" aria-valuenow={percent} aria-valuemin={0} aria-valuemax={100} className={cn("flex h-2 gap-[3px]", className)}>
      {Array.from({ length: segments }, (_, i) => (
        <span key={i} className={cn("h-full flex-1 rounded-[2px]", i < filled ? tone : "bg-raised")} />
      ))}
    </div>
  );
}
