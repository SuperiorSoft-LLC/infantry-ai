// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The Global Workers board's grouping (the Kotlin `workerLanes`): the working sessions of every project, one lane per project.

/** The lane of sessions that belong to no project (the Assistant, an off-board spawn). */
export const NO_PROJECT_LANE = "No project";

export interface WorkerLane<T> {
  projectId: string | null;
  name: string;
  agents: T[];
}

/**
 * One lane per project: the active project first, then by name (case-insensitive); sessions without a project last.
 * A project the client does not know shows its id. A project with no working session has no lane. Agents keep their order.
 */
export function workerLanes<T extends { projectId?: string | null }>(
  working: T[],
  projects: { id: string; name: string }[],
  activeProjectId?: string | null,
): WorkerLane<T>[] {
  const names = new Map(projects.map((p) => [p.id, p.name]));
  const groups = new Map<string | null, T[]>();
  for (const a of working) {
    const pid = a.projectId || null;
    const rows = groups.get(pid);
    if (rows) rows.push(a);
    else groups.set(pid, [a]);
  }
  const lanes: WorkerLane<T>[] = [...groups].map(([projectId, agents]) => ({
    projectId,
    name: projectId === null ? NO_PROJECT_LANE : (names.get(projectId) ?? projectId),
    agents,
  }));
  const rank = (l: WorkerLane<T>) => (l.projectId === null ? 2 : l.projectId !== activeProjectId ? 1 : 0);
  return lanes.sort((a, b) => rank(a) - rank(b) || a.name.toLowerCase().localeCompare(b.name.toLowerCase()));
}
