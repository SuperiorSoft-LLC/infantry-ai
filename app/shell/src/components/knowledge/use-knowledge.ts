// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The one knowledge, roles and library store of this window, bound to the daemon store's link; the knowledge store
// follows the current project of the projects store.
import { useEffect } from "react";
import { useStore } from "zustand";
import {
  createKnowledgeStore,
  createLibraryStore,
  createRolesStore,
  type KnowledgeActions,
  type KnowledgeState,
  type KnowledgeStore,
  type LibraryActions,
  type LibraryState,
  type LibraryStore,
  type RolesActions,
  type RolesState,
  type RolesStore,
} from "@infantry/client";
import { daemonStore, useDaemon } from "@/daemon/daemon";
import { useProjects, useProjectsLoaded } from "@/components/git/use-projects";

let knowledge: KnowledgeStore | null = null;
let roles: RolesStore | null = null;
let library: LibraryStore | null = null;

export function knowledgeStore(): KnowledgeStore {
  return (knowledge ??= createKnowledgeStore(daemonStore().getState()));
}

export function rolesStore(): RolesStore {
  return (roles ??= createRolesStore(daemonStore().getState()));
}

export function libraryStore(): LibraryStore {
  return (library ??= createLibraryStore(daemonStore().getState()));
}

export function useKnowledge<T>(select: (s: KnowledgeState & KnowledgeActions) => T): T {
  return useStore(knowledgeStore(), select);
}

export function useRoles<T>(select: (s: RolesState & RolesActions) => T): T {
  return useStore(rolesStore(), select);
}

export function useLibrary<T>(select: (s: LibraryState & LibraryActions) => T): T {
  return useStore(libraryStore(), select);
}

/** True once the daemon link is open. */
function useLinkOpen(): boolean {
  return useDaemon((s) => s.connection.state) === "open";
}

/** Binds the knowledge store to the current project once the link is open. */
export function useKnowledgeProject(): string | null {
  useProjectsLoaded();
  const open = useLinkOpen();
  const projectId = useProjects((s) => s.currentId);
  useEffect(() => {
    if (open) knowledgeStore().getState().setProject(projectId);
  }, [open, projectId]);
  return projectId;
}

export function useRolesLoaded(): void {
  const open = useLinkOpen();
  useEffect(() => {
    if (open) rolesStore().getState().ensureLoaded();
  }, [open]);
}

export function useLibraryLoaded(): void {
  const open = useLinkOpen();
  useEffect(() => {
    if (open) libraryStore().getState().ensureLoaded();
  }, [open]);
}
