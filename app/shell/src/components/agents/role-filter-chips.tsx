// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Horizontally scrolling role filter (ArrowScrollRow.kt): all, architect, assistant, backend...
import { useRef } from "react";
import { ChevronLeft, ChevronRight } from "lucide-react";
import { cn } from "@infantry/ui";
import { roles } from "@/mock/data";
import { RoleIcon } from "./role-icon";
import { IconButton } from "@infantry/ui";

export function RoleFilterChips({ value, onChange }: { value: string; onChange: (id: string) => void }) {
  const row = useRef<HTMLDivElement>(null);
  const scroll = (dx: number) => row.current?.scrollBy({ left: dx, behavior: "smooth" });
  const items = [{ id: "all", label: "all" }, ...roles];

  return (
    <div className="flex items-center gap-1">
      <IconButton label="Scroll roles left" size="icon-xs" onClick={() => scroll(-120)}>
        <ChevronLeft />
      </IconButton>
      <div ref={row} role="tablist" aria-label="Filter by role" className="no-scrollbar flex flex-1 gap-1.5 overflow-x-auto">
        {items.map((r) => {
          const active = r.id === value;
          return (
            <button
              key={r.id}
              role="tab"
              aria-selected={active}
              onClick={() => onChange(r.id)}
              className={cn(
                "inline-flex h-7 shrink-0 items-center gap-1.5 rounded-md border px-2 text-xs transition-colors",
                active ? "border-danger/70 text-danger" : "text-muted-foreground hover:text-foreground",
              )}
            >
              {r.id !== "all" && <RoleIcon roleId={r.id} className="size-3" />}
              {r.label}
            </button>
          );
        })}
      </div>
      <IconButton label="Scroll roles right" size="icon-xs" onClick={() => scroll(120)}>
        <ChevronRight />
      </IconButton>
    </div>
  );
}
