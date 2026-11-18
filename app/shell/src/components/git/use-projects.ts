// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The one projects + git store of this window, bound to the daemon store's link.
import { useEffect } from "react";
import { useStore } from "zustand";
import { createProjectsStore, type ProjectsActions, type ProjectsState, type ProjectsStore } from "@infantry/client";
import { daemonStore, useDaemon } from "@/daemon/daemon";

let store: ProjectsStore | null = null;

export function projectsStore(): ProjectsStore {
  return (store ??= createProjectsStore(daemonStore().getState()));
}

export function useProjects<T>(select: (s: ProjectsState & ProjectsActions) => T): T {
  return useStore(projectsStore(), select);
}

/** Loads the project list once the link is open. */
export function useProjectsLoaded(): void {
  const link = useDaemon((s) => s.connection.state);
  useEffect(() => {
    if (link === "open") projectsStore().getState().ensureLoaded();
  }, [link]);
}
