// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// RUST-UI-2: slowly drifting plaid (crossing bands, accent at 10%, 28px period, 3s) behind a selected card. Pure CSS transform on the compositor; static under reduced motion.
import { useEffect } from "react";
import { cn } from "../lib/utils";

/** Put inside a `relative` element; it fills it, clips itself and ignores the pointer. */
export function SelectedStripes({ active = true, animate = true, className }: { active?: boolean; animate?: boolean; className?: string }) {
  useHiddenFlag();
  if (!active) return null;
  return (
    <span aria-hidden className={cn("pointer-events-none absolute inset-0 overflow-hidden rounded-[inherit]", className)}>
      <span className={cn("selected-stripes", !animate && "selected-stripes-static")} />
    </span>
  );
}

/** Cheap: while the window is hidden the stripes pause (CSS `[data-page-hidden]`); one shared listener. */
function useHiddenFlag() {
  useEffect(() => {
    const sync = () => document.documentElement.toggleAttribute("data-page-hidden", document.hidden);
    sync();
    document.addEventListener("visibilitychange", sync);
    return () => document.removeEventListener("visibilitychange", sync);
  }, []);
}
