// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The board filter bar: epic, role and assignee pickers filled from the tickets on the board, plus a text filter.
import { X } from "lucide-react";
import { ALL, NO_FILTER, assigneeOf, distinct, type BoardFilter } from "@infantry/client";
import { Button, Input, Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@infantry/ui";
import { shortId } from "./ticket-card";
import { useBoard } from "./use-board";

function FilterPicker({ label, value, options, onChange, format = (o) => o }: { label: string; value: string; options: string[]; onChange: (v: string) => void; format?: (o: string) => string }) {
  return (
    <Select value={value} onValueChange={onChange}>
      <SelectTrigger aria-label={`Filter by ${label}`} className="h-8 w-40 text-xs">
        <SelectValue />
      </SelectTrigger>
      <SelectContent>
        <SelectItem value={ALL}>All {label}s</SelectItem>
        {options.map((o) => (
          <SelectItem key={o} value={o}>
            {format(o)}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  );
}

export function FilterBar() {
  const tickets = useBoard((s) => s.tickets);
  const filter = useBoard((s) => s.filter);
  const setFilter = useBoard((s) => s.setFilter);
  const active = (Object.keys(NO_FILTER) as (keyof BoardFilter)[]).some((k) => filter[k] !== NO_FILTER[k]);
  return (
    <div className="mb-3 flex flex-wrap items-center gap-2" role="search" aria-label="Filter the board">
      <Input aria-label="Filter by title or id" placeholder="Filter tickets" className="h-8 w-48 text-xs" value={filter.text} onChange={(e) => setFilter({ text: e.target.value })} />
      <FilterPicker label="epic" value={filter.epic} options={distinct(tickets, (t) => t.epicId)} format={shortId} onChange={(epic) => setFilter({ epic })} />
      <FilterPicker label="role" value={filter.role} options={distinct(tickets, (t) => t.role)} onChange={(role) => setFilter({ role })} />
      <FilterPicker label="assignee" value={filter.assignee} options={distinct(tickets, assigneeOf)} onChange={(assignee) => setFilter({ assignee })} />
      {active && (
        <Button variant="ghost" size="sm" onClick={() => setFilter(NO_FILTER)}>
          <X /> Clear
        </Button>
      )}
    </div>
  );
}
