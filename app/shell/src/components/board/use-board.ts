// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The one board store of this window, bound to the daemon store's link, and the hook components read it through.
import { useStore } from "zustand";
import { createArchiveStore, createBoardStore, type ArchiveActions, type ArchiveState, type ArchiveStore, type BoardActions, type BoardState, type BoardStore } from "@infantry/client";
import { daemonStore } from "@/daemon/daemon";

let store: BoardStore | null = null;

export function boardStore(): BoardStore {
  return (store ??= createBoardStore(daemonStore().getState()));
}

/** Read the board store from a component; re-renders only when the selected slice changes. */
export function useBoard<T>(select: (s: BoardState & BoardActions) => T): T {
  return useStore(boardStore(), select);
}

let archive: ArchiveStore | null = null;

/** ARCHIVE-RUST-2: the archive store of this window (Archive view, ticket and epic Archive actions). */
export function archiveStore(): ArchiveStore {
  return (archive ??= createArchiveStore(daemonStore().getState()));
}

export function useArchive<T>(select: (s: ArchiveState & ArchiveActions) => T): T {
  return useStore(archiveStore(), select);
}
