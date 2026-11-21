// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Horizontally scrolling role filter (ArrowScrollRow.kt): all, then the daemon's roles in their usual order.
import { useMemo } from "react";
import { roleList } from "@infantry/client";
import { cn } from "@infantry/ui";
import { ScrollStrip } from "@/components/layout/scroll-strip";
import { useRoles } from "@/components/knowledge/use-knowledge";
import { RoleGlyph } from "./role-icon";

export function RoleFilterChips({ value, onChange }: { value: string; onChange: (id: string) => void }) {
  const roles = useRoles((s) => s.roles);
  const items = useMemo(() => [{ id: "all", name: "all", icon: "" }, ...roleList(roles).filter((r) => !r.disabled)], [roles]);

  return (
    <ScrollStrip label="roles" activeKey={value} rowClassName="gap-1.5" rowProps={{ role: "tablist", "aria-label": "Filter by role" }}>
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
            {r.id !== "all" && <RoleGlyph icon={r.icon} className="size-3" />}
            {r.name}
          </button>
        );
      })}
    </ScrollStrip>
  );
}
