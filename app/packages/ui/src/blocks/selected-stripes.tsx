// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// RUST-UI-2: slowly scrolling diagonal stripes behind a selected card (mimics SelectedStripes.kt: accent at 14%,
// 10px stripes every 28px along x, one period per 3s). Pure CSS transform on the compositor; static under reduced motion.
import { cn } from "../lib/utils";

/** Put inside a `relative` element; it fills it, clips itself and ignores the pointer. */
export function SelectedStripes({ active = true, className }: { active?: boolean; className?: string }) {
  if (!active) return null;
  return (
    <span aria-hidden className={cn("pointer-events-none absolute inset-0 overflow-hidden rounded-[inherit]", className)}>
      <span className="selected-stripes" />
    </span>
  );
}
