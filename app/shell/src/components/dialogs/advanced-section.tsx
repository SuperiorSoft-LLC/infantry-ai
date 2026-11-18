// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Collapsed "Advanced" block for optional settings.
import { useState, type ReactNode } from "react";
import { ChevronRight } from "lucide-react";
import { cn } from "@infantry/ui";

export function AdvancedSection({ children }: { children: ReactNode }) {
  const [open, setOpen] = useState(false);
  return (
    <div className="rounded-lg border">
      <button
        type="button"
        aria-expanded={open}
        onClick={() => setOpen((o) => !o)}
        className="flex w-full items-center gap-2 px-3 py-2 text-[13px] font-medium text-muted-foreground hover:text-foreground"
      >
        <ChevronRight className={cn("size-4 transition-transform", open && "rotate-90")} /> Advanced
      </button>
      {open && <div className="grid gap-3 border-t p-3 sm:grid-cols-2">{children}</div>}
    </div>
  );
}
