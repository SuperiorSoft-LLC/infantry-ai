// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// A bordered row card for list-style tabs (pods, profiles, triggers, notes...).
import type { ReactNode } from "react";
import { Chip } from "./chip";
import type { NamedRow } from "../types";

export function ListRow({ row, icon, trailing }: { row: NamedRow; icon?: ReactNode; trailing?: ReactNode }) {
  return (
    <div className="flex items-center gap-3 rounded-lg border bg-card p-3">
      {icon && <div className="grid size-9 shrink-0 place-items-center rounded-md border bg-raised text-muted-foreground">{icon}</div>}
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-2">
          <span className="truncate font-medium">{row.title}</span>
          {row.badge && <Chip tone={row.tone ?? "neutral"}>{row.badge}</Chip>}
        </div>
        <div className="truncate text-xs text-muted-foreground">{row.subtitle}</div>
      </div>
      {row.meta && <span className="shrink-0 font-mono text-xs text-muted-foreground">{row.meta}</span>}
      {trailing}
    </div>
  );
}
