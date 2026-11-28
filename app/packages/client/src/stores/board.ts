// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The board store: tickets by lane, fed by `ticket_list` -> `tickets` and the `ticket` / `tickets` push. Every change
// (save, move, reorder, delete) is applied at once and rolled back when the daemon refuses it. The link is injected
// (the daemon store's sendFrame / onFrame), so the store is tested with recorded frames and no socket.
import { createStore, type StoreApi } from "zustand/vanilla";
import { Frame, json, type Envelope } from "../wire";
import { hop, timedHop } from "../hops";

/** The lanes, left to right (TicketStatus in the Kotlin protocol). */
export const LANES = ["backlog", "todo", "in_progress", "pending", "done"] as const;
export type Lane = (typeof LANES)[number];
export const LANE_TITLES: Record<Lane, string> = { backlog: "Backlog", todo: "To Do", in_progress: "In Progress", pending: "Pending", done: "Done" };

/** A ticket as the `ticket` frame carries it. Only what this client reads is typed; the daemon omits default-valued keys. */
export interface BoardTicket {
  id: string;
  title: string;
  status: string;
  position: number;
  description?: string;
  ticketType?: string | null;
  epicId?: string | null;
  agentId?: string | null;
  queuedAgentName?: string | null;
  role?: string | null;
  wontFix?: boolean;
  heldBy?: string[];
  holdReason?: string | null;
  branch?: string | null;
  sessionId?: string | null;
  updatedAt?: number;
  /** PARITY-AUDIT-1 #7: the executor pins (provider = harness id) and, on a full ticket, who runs it and why. */
  provider?: string | null;
  model?: string | null;
  weight?: number | null;
  effort?: string | null;
  effectiveExecutor?: EffectiveExecutor | null;
  /** Set by this client on a ticket that came from the compact list: the description is not loaded yet (`loadFull`). */
  partial?: boolean;
}

/** The fields a save may carry (TicketSave). No `id` creates a ticket. */
export interface TicketDraft {
  id?: string;
  title?: string;
  description?: string;
  ticketType?: string;
  epicId?: string;
  /** "" clears a pin; weight 0 clears it. */
  agentId?: string;
  provider?: string;
  model?: string;
  weight?: number;
  effort?: string;
}

/** Where one executor part comes from: ticket, rule, weight, profile, role or default. */
export interface ExecutorField {
  value?: string | null;
  source: string;
}
/** The daemon's effective executor of a ticket (`effectiveExecutor`). */
export interface EffectiveExecutor {
  agentId?: string | null;
  role?: string | null;
  provider?: ExecutorField | null;
  model?: ExecutorField | null;
  rule?: string | null;
}

export interface BoardFilter {
  epic: string;
  role: string;
  assignee: string;
  text: string;
}
export const ALL = "all";
export const NO_FILTER: BoardFilter = { epic: ALL, role: ALL, assignee: ALL, text: "" };

export interface BoardLink {
  sendFrame(env: Envelope): boolean;
  onFrame(listener: (env: Envelope) => void): () => void;
}

export interface BoardState {
  tickets: Record<string, BoardTicket>;
  loaded: boolean;
  /** The last refusal, shown once and cleared by the view. */
  refusal: string | null;
  filter: BoardFilter;
  /** Request ids in flight, with the state to restore when the daemon refuses them. */
  inflight: Record<string, Record<string, BoardTicket>>;
}

export interface BoardActions {
  /** Ask for the board once per connection (no-op when loaded). */
  ensureLoaded(): void;
  /** Fetch the full ticket (`ticket_get`) when the list sent only its card fields. */
  loadFull(id: string): void;
  save(draft: TicketDraft): void;
  /** Put [id] in [status] at [position] (index in the lane; default the bottom). */
  move(id: string, status: string, position?: number): void;
  /** Same-lane order: [ids] is the lane's complete new order. */
  reorder(status: string, ids: string[]): void;
  remove(id: string): void;
  setFilter(patch: Partial<BoardFilter>): void;
  clearRefusal(): void;
  dispose(): void;
}

export type BoardStore = StoreApi<BoardState & BoardActions>;

/** Tickets of [status] in board order. */
export function laneOf(tickets: Record<string, BoardTicket>, status: string): BoardTicket[] {
  return Object.values(tickets)
    .filter((t) => t.status === status)
    .sort((a, b) => a.position - b.position || a.id.localeCompare(b.id));
}

export function assigneeOf(t: BoardTicket): string {
  return t.queuedAgentName ?? t.agentId ?? "";
}

export function matchesFilter(t: BoardTicket, f: BoardFilter): boolean {
  if (f.epic !== ALL && (t.epicId ?? "") !== f.epic) return false;
  if (f.role !== ALL && (t.role ?? "") !== f.role) return false;
  if (f.assignee !== ALL && assigneeOf(t) !== f.assignee) return false;
  const q = f.text.trim().toLowerCase();
  return !q || t.title.toLowerCase().includes(q) || t.id.toLowerCase().startsWith(q);
}

/** Distinct non-empty values for a filter picker. */
export function distinct(tickets: Record<string, BoardTicket>, pick: (t: BoardTicket) => string | null | undefined): string[] {
  return [...new Set(Object.values(tickets).map((t) => pick(t) ?? "").filter(Boolean))].sort();
}

function renumber(list: BoardTicket[]): BoardTicket[] {
  return list.map((t, i) => (t.position === i ? t : { ...t, position: i }));
}

export function createBoardStore(link: BoardLink): BoardStore {
  let counter = 0;
  /** A move's requested lane by requestId: a daemon that answers with the ticket still elsewhere refused it quietly. */
  const wanted = new Map<string, { id: string; status: string }>();
  return createStore<BoardState & BoardActions>()((set, get) => {
    const rollback = (requestId: string, why: string): void => {
      const { inflight, tickets } = get();
      const restore = inflight[requestId];
      const rest = { ...inflight };
      delete rest[requestId];
      set({ inflight: rest, tickets: { ...tickets, ...restore }, refusal: why });
    };
    const settle = (requestId: string): void => {
      const rest = { ...get().inflight };
      delete rest[requestId];
      set({ inflight: rest });
    };
    const request = (type: string, data: string, restore: Record<string, BoardTicket>): string => {
      counter += 1;
      const requestId = `board:${Date.now().toString(36)}-${counter.toString(36)}`;
      set({ inflight: { ...get().inflight, [requestId]: restore } });
      if (!link.sendFrame({ type, data, requestId })) rollback(requestId, `${type} not sent: the daemon link is down`);
      return requestId;
    };
    /** Apply [changed] at once; returns the originals for a rollback. */
    const apply = (changed: BoardTicket[], removed: string[] = []): Record<string, BoardTicket> => {
      const before = get().tickets;
      const restore: Record<string, BoardTicket> = {};
      const next = { ...before };
      for (const t of changed) {
        if (before[t.id]) restore[t.id] = before[t.id];
        next[t.id] = t;
      }
      for (const id of removed) {
        if (before[id]) restore[id] = before[id];
        delete next[id];
      }
      set({ tickets: next });
      return restore;
    };

    // One `ticket_list` per connection: the first caller asks, the rest wait for the answer. The session store asks too on READY
    // (compact), so a fresh connection counts as asked.
    let asked = false;
    const off = link.onFrame((env) => {
      const rid = env.requestId;
      const mine = rid !== undefined && rid in get().inflight;
      switch (env.type) {
        case Frame.READY:
          asked = true;
          return;
        case Frame.TICKETS: {
          const list = timedHop("board.parse", () => json<BoardTicket[]>(env.data), { bytes: env.data.length });
          if (!list) return;
          // `tickets` is the whole board, in board order: it replaces what we hold.
          timedHop("board.set", () => set({ tickets: Object.fromEntries(list.map((t) => [t.id, { ...t, partial: true }])), loaded: true }), { tickets: list.length });
          if (mine) settle(rid as string);
          return;
        }
        case Frame.TICKET: {
          const t = json<BoardTicket>(env.data);
          if (t?.id) set({ tickets: { ...get().tickets, [t.id]: t } });
          const want = rid ? wanted.get(rid) : undefined;
          if (rid) wanted.delete(rid);
          if (mine) settle(rid as string);
          if (want && t?.id === want.id && t.status !== want.status) set({ refusal: `The daemon kept "${t.title}" in ${LANE_TITLES[t.status as Lane] ?? t.status}; ${want.status === "in_progress" ? "start the ticket to put it In Progress." : "the move was not applied."}` });
          return;
        }
        case Frame.ERROR:
          if (mine) rollback(rid as string, env.data);
          return;
      }
    });

    return {
      tickets: {},
      loaded: false,
      refusal: null,
      filter: NO_FILTER,
      inflight: {},

      ensureLoaded() {
        if (!get().loaded && !asked) {
          asked = true;
          hop("board.request");
          link.sendFrame({ type: Frame.TICKET_LIST, data: JSON.stringify({ compact: true }) });
        }
      },
      loadFull(id) {
        if (get().tickets[id]?.partial) link.sendFrame({ type: Frame.TICKET_GET, data: id });
      },
      save(draft) {
        const old = draft.id ? get().tickets[draft.id] : undefined;
        const restore = old ? apply([{ ...old, ...draft, id: old.id }]) : {};
        request("ticket_save", JSON.stringify(draft), restore);
      },
      move(id, status, position) {
        const t = get().tickets[id];
        if (!t) return;
        const target = laneOf(get().tickets, status).filter((x) => x.id !== id);
        const at = Math.max(0, Math.min(position ?? target.length, target.length));
        target.splice(at, 0, { ...t, status });
        const changed = renumber(target);
        if (t.status !== status) changed.push(...renumber(laneOf(get().tickets, t.status).filter((x) => x.id !== id)));
        wanted.set(request("ticket_move", JSON.stringify({ id, status, position: at }), apply(changed)), { id, status });
      },
      reorder(status, ids) {
        const byId = new Map(laneOf(get().tickets, status).map((t) => [t.id, t]));
        const ordered = ids.map((id) => byId.get(id)).filter((t): t is BoardTicket => !!t);
        if (ordered.length !== ids.length) return;
        request("ticket_reorder", JSON.stringify({ status, ids }), apply(renumber(ordered)));
      },
      remove(id) {
        request("ticket_delete", id, apply([], [id]));
      },
      setFilter(patch) {
        set({ filter: { ...get().filter, ...patch } });
      },
      clearRefusal() {
        set({ refusal: null });
      },
      dispose() {
        off();
      },
    };
  });
}
