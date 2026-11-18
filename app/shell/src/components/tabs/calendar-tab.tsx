// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Calendar (plugin view): a week strip of day cards.
import { Plus } from "lucide-react";
import { calendar } from "@/mock/data";
import { cn } from "@infantry/ui";
import { Button } from "@infantry/ui";
import { TabPage } from "./tab-page";

export function CalendarTab() {
  return (
    <TabPage
      title="Calendar"
      description="Week of Nov 16, 2026 · from the Calendar plugin"
      actions={
        <Button variant="outline" size="sm">
          <Plus /> Add task
        </Button>
      }
    >
      <div className="grid grid-cols-[repeat(auto-fill,minmax(150px,1fr))] gap-2">
        {calendar.map((d) => (
          <section key={d.day} className={cn("min-h-32 rounded-lg border bg-card p-2", d.day === "Tue 17" && "border-warn")}>
            <h3 className="mb-2 text-xs font-semibold">{d.day}</h3>
            <ul className="space-y-1.5">
              {d.items.length === 0 && <li className="text-xs text-muted-foreground">Nothing planned</li>}
              {d.items.map((i) => (
                <li key={i} className="rounded border-l-2 border-danger bg-raised px-2 py-1 text-xs">
                  {i}
                </li>
              ))}
            </ul>
          </section>
        ))}
      </div>
    </TabPage>
  );
}
