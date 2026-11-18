// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Project picker filled from the daemon (`project_list`); no free-text.
import { useState } from "react";
import { ChevronDown, Check, FolderPlus } from "lucide-react";
import { useDaemon } from "@/daemon/daemon";
import { Button } from "@infantry/ui";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@infantry/ui";

export function ProjectPicker() {
  const projects = useDaemon((s) => s.projects);
  const [chosen, setCurrent] = useState<string | null>(null);
  const current = chosen ?? projects[0]?.id ?? null;
  const name = projects.find((p) => p.id === current)?.name ?? (projects.length === 0 ? "No projects" : "Project");
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="outline" size="sm" className="max-w-48 font-semibold">
          <span className="truncate">{name}</span>
          <ChevronDown className="text-muted-foreground" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="start" className="w-56">
        <DropdownMenuLabel>Projects</DropdownMenuLabel>
        {projects.length === 0 && <DropdownMenuItem disabled>The daemon has no projects yet</DropdownMenuItem>}
        {projects.map((p) => (
          <DropdownMenuItem key={p.id} onSelect={() => setCurrent(p.id)}>
            <Check className={p.id === current ? "opacity-100" : "opacity-0"} />
            {p.name}
          </DropdownMenuItem>
        ))}
        <DropdownMenuSeparator />
        <DropdownMenuItem>
          <FolderPlus /> Add project...
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
