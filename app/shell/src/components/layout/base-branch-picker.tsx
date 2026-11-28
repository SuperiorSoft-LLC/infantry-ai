// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// PARITY-AUDIT-1 #20: the project's base branch (where every new worker forks from), set from the branches the daemon
// lists (`project_git_branches`), never typed. Picking only stages a choice; nothing is sent until the owner confirms
// the "current -> new" line (`project_base`). "Clear (use default)" removes the base.
import { useEffect, useState } from "react";
import { Button, Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@infantry/ui";
import type { ProjectInfo } from "@infantry/client";
import { projectsStore, useProjects } from "@/components/git/use-projects";

/** The Select value meaning "clear the base". Not a valid git branch name, so it can never clash with one. */
export const CLEAR_BASE = "::clear::";

/** What a confirm sends for a staged pick: a branch, null (clear), or undefined (nothing changed, send nothing). */
export function stagedBase(current: string | undefined, picked: string | null): string | null | undefined {
  if (picked === null) return undefined;
  if (picked === CLEAR_BASE) return current ? null : undefined;
  return picked === current ? undefined : picked;
}

export function BaseBranchPicker({ project }: { project: ProjectInfo }) {
  const list = useProjects((s) => s.branchLists[project.id]);
  const [picked, setPicked] = useState<string | null>(null);
  useEffect(() => {
    projectsStore().getState().loadBranches(project.id);
  }, [project.id]);
  const current = project.baseBranch || undefined;
  const next = stagedBase(current, picked);
  const names = (list?.branches ?? []).map((b) => b.name);
  const confirm = () => {
    if (next !== undefined) projectsStore().getState().setBaseBranch(project.id, next);
    setPicked(null);
  };
  return (
    <div className="space-y-1.5">
      <div className="flex items-center justify-between gap-3 text-sm">
        <span className="text-muted-foreground">Base branch</span>
        <span className="truncate font-mono">{current ?? "default"}</span>
      </div>
      {list && !list.available && <p className="text-xs text-muted-foreground">Branches unknown: {list.reason}</p>}
      {list?.available && (
        <Select value={picked ?? ""} onValueChange={setPicked}>
          <SelectTrigger aria-label="Change base branch" className="h-8 text-xs">
            <SelectValue placeholder="Change base branch…" />
          </SelectTrigger>
          <SelectContent>
            {names.map((n) => (
              <SelectItem key={n} value={n}>
                {n}
              </SelectItem>
            ))}
            {current && <SelectItem value={CLEAR_BASE}>Clear (use default)</SelectItem>}
          </SelectContent>
        </Select>
      )}
      {next !== undefined && (
        <div role="alertdialog" aria-label="Confirm base branch change" className="space-y-2 rounded-md border p-2 text-xs">
          <p>
            New workers will fork from the new base:{" "}
            <span className="font-mono">
              {current ?? "default"} → {next ?? "default"}
            </span>
          </p>
          <div className="flex justify-end gap-2">
            <Button size="sm" variant="ghost" onClick={() => setPicked(null)}>
              Cancel
            </Button>
            <Button size="sm" onClick={confirm}>
              Change base
            </Button>
          </div>
        </div>
      )}
    </div>
  );
}
