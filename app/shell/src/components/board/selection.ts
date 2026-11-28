// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// PARITY-AUDIT-1 #9: the board's multi-select (Ctrl/Cmd-click a card; a plain click still opens it) and Move to epic
// (Kotlin EpicMoveUi). A move is a `ticket_save` of `epicId` alone per ticket (the same write infantry_ticket_epic makes),
// so status, lane order, branch and worker are kept; tickets go in board order.
import { createStore, useStore } from "zustand";
import { LANES, laneOf, type BoardTicket, type TicketDraft } from "@infantry/client";

interface SelState {
  ids: string[];
  toggle(id: string): void;
  clear(): void;
}

export const selectionStore = createStore<SelState>((set, get) => ({
  ids: [],
  toggle: (id) => set({ ids: get().ids.includes(id) ? get().ids.filter((x) => x !== id) : [...get().ids, id] }),
  clear: () => set({ ids: [] }),
}));

export function useSelection<T>(select: (s: SelState) => T): T {
  return useStore(selectionStore, select);
}

/** A modifier click selects; a plain click opens. */
export const isSelectClick = (e: { ctrlKey: boolean; metaKey: boolean }) => e.ctrlKey || e.metaKey;

/** The selected tickets in board order (lane, then position); ids no longer on the board are dropped. */
export function inBoardOrder(tickets: Record<string, BoardTicket>, ids: string[]): string[] {
  const want = new Set(ids);
  return LANES.flatMap((l) => laneOf(tickets, l).map((t) => t.id)).filter((id) => want.has(id));
}

/** Move [ids] to [epicId] ("" clears the epic), one save each, in board order. Returns the ids sent. */
export function moveToEpic(tickets: Record<string, BoardTicket>, ids: string[], epicId: string, save: (d: TicketDraft) => void): string[] {
  const ordered = inBoardOrder(tickets, ids).filter((id) => (tickets[id].epicId ?? "") !== epicId);
  for (const id of ordered) save({ id, epicId });
  return ordered;
}
