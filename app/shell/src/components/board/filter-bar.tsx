// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The board filter bar: epic, role and assignee pickers filled from the tickets on the board, plus a text filter, and
// Start next (`ticket_start_next` for the current project: the daemon starts the top To Do ticket with its pinned role).
import { Play, X } from "lucide-react";
import { daemonStore } from "@/daemon/daemon";
import { useProjects } from "@/components/git/use-projects";
import { ALL, NO_FILTER, assigneeOf, distinct, type BoardFilter } from "@infantry/client";
import { Button, Input, Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@infantry/ui";
import { EpicArchiveButton } from "./archive-dialog";
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
  const projectId = useProjects((s) => s.currentId);
  const todo = Object.values(tickets).some((t) => t.status === "todo");
  const startNext = () =>
    daemonStore().getState().sendFrame({ type: "ticket_start_next", data: JSON.stringify(projectId ? { projectId } : {}), requestId: `start-next:${Date.now().toString(36)}` });
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
      {filter.epic !== ALL && <EpicArchiveButton key={filter.epic} epicId={filter.epic} projectId={projectId} />}
      <span className="flex-1" />
      <Button size="sm" variant="outline" disabled={!todo} onClick={startNext} title="Start the top To Do ticket with its pinned role">
        <Play /> Start next
      </Button>
    </div>
  );
}
