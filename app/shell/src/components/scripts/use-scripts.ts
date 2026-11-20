// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The one scripts store of this window, bound to the daemon link and following the current project.
import { useEffect } from "react";
import { useStore } from "zustand";
import { createScriptsStore, type ScriptsActions, type ScriptsState, type ScriptsStore } from "@infantry/client";
import { daemonStore, useDaemon } from "@/daemon/daemon";
import { useProjects, useProjectsLoaded } from "@/components/git/use-projects";

let scripts: ScriptsStore | null = null;

export function scriptsStore(): ScriptsStore {
  return (scripts ??= createScriptsStore(daemonStore().getState()));
}

export function useScripts<T>(select: (s: ScriptsState & ScriptsActions) => T): T {
  return useStore(scriptsStore(), select);
}

/** Loads the scripts of the current project once the link is open, and again when the project changes. */
export function useScriptsLoaded(): string | null {
  useProjectsLoaded();
  const open = useDaemon((s) => s.connection.state) === "open";
  const projectId = useProjects((s) => s.currentId);
  useEffect(() => {
    if (open) scriptsStore().getState().setProject(projectId);
  }, [open, projectId]);
  return projectId;
}
