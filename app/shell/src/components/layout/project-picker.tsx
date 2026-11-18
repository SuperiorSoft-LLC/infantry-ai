// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The Projects switcher: the daemon's projects (`project_list`), the current one drives the Git panel. Adding a
// project asks for a name and a directory (`project_save`); the daemon refuses a directory that does not exist.
import { useState } from "react";
import { ChevronDown, Check, FolderPlus } from "lucide-react";
import { AppDialog, Button, Input, Label } from "@infantry/ui";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@infantry/ui";
import { projectsStore, useProjects, useProjectsLoaded } from "@/components/git/use-projects";

export function ProjectPicker() {
  useProjectsLoaded();
  const projects = useProjects((s) => s.projects);
  const current = useProjects((s) => s.currentId);
  const [adding, setAdding] = useState(false);
  const [name, setName] = useState("");
  const [dir, setDir] = useState("");
  const label = projects.find((p) => p.id === current)?.name ?? (projects.length === 0 ? "No projects" : "Project");
  const save = () => {
    projectsStore().getState().saveProject({ name: name.trim(), directory: dir.trim() });
    setAdding(false);
    setName("");
    setDir("");
  };
  return (
    <>
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="outline" size="sm" className="max-w-48 font-semibold">
            <span className="truncate">{label}</span>
            <ChevronDown className="text-muted-foreground" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="start" className="w-64">
          <DropdownMenuLabel>Projects</DropdownMenuLabel>
          {projects.length === 0 && <DropdownMenuItem disabled>The daemon has no projects yet</DropdownMenuItem>}
          {projects.map((p) => (
            <DropdownMenuItem key={p.id} onSelect={() => projectsStore().getState().select(p.id)} title={p.directory}>
              <Check className={p.id === current ? "opacity-100" : "opacity-0"} />
              <span className="min-w-0 flex-1 truncate">{p.name}</span>
            </DropdownMenuItem>
          ))}
          <DropdownMenuSeparator />
          <DropdownMenuItem onSelect={() => setAdding(true)}>
            <FolderPlus /> Add project...
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
      <AppDialog
        open={adding}
        onOpenChange={setAdding}
        title="Add project"
        description="A name and the folder of its git repository."
        footer={
          <Button size="sm" disabled={!name.trim()} onClick={save}>
            Add
          </Button>
        }
      >
        <div className="space-y-3">
          <div className="space-y-1.5">
            <Label htmlFor="project-name">Name</Label>
            <Input id="project-name" value={name} onChange={(e) => setName(e.target.value)} />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="project-dir">Directory</Label>
            <Input id="project-dir" value={dir} onChange={(e) => setDir(e.target.value)} placeholder="C:\code\my-repo" className="font-mono" />
          </div>
        </div>
      </AppDialog>
    </>
  );
}
