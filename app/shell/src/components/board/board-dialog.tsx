// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The Board modal (toolbar Board button, Ctrl+8): the whole window through the one AppDialog ("screen"). Kanban on the live wire: one bordered lane per status, drag to move or reorder, click a card to edit.
import { useEffect, useMemo, useState } from "react";
import { useLayout } from "@/state/ui-state";
import { DndContext, DragOverlay, KeyboardSensor, MouseSensor, TouchSensor, closestCorners, useSensor, useSensors, type DragEndEvent, type DragOverEvent, type DragStartEvent } from "@dnd-kit/core";
import { arrayMove, sortableKeyboardCoordinates } from "@dnd-kit/sortable";
import { Archive, LayoutGrid, Plus, X } from "lucide-react";
import { LANES, LANE_TITLES, hop, laneOf, matchesFilter } from "@infantry/client";
import { AppDialog, Button, EmptyState } from "@infantry/ui";
import { useDaemon } from "@/daemon/daemon";
import { ArchiveDialog } from "./archive-dialog";
import { FilterBar } from "./filter-bar";
import { Lane } from "./lane";
import { TicketCardView } from "./ticket-card";
import { TicketDialog } from "./ticket-dialog";
import { boardStore, useBoard } from "./use-board";
import "./board.css";

/** The lane a dnd-kit `over` id names: a lane drop zone, or the lane of the ticket under the pointer. */
function laneOfOver(id: string | number | undefined): string | null {
  if (id === undefined) return null;
  const s = String(id);
  if (s.startsWith("lane:")) return s.slice(5);
  return boardStore().getState().tickets[s]?.status ?? null;
}

export function BoardDialog({ onClose }: { onClose: () => void }) {
  const tickets = useBoard((s) => s.tickets);
  const loaded = useBoard((s) => s.loaded);
  const filter = useBoard((s) => s.filter);
  const refusal = useBoard((s) => s.refusal);
  const link = useDaemon((s) => s.connection.state);
  const [openId, setOpenId] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [archiveOpen, setArchiveOpen] = useState(false);
  const [dragId, setDragId] = useState<string | null>(null);
  const [overLane, setOverLane] = useState<string | null>(null);
  useEffect(() => {
    if (link === "open") boardStore().getState().ensureLoaded();
  }, [link]);
  // Hop timings (off unless enabled): the commit that put the tickets on screen, and the first paint after it.
  useEffect(() => {
    hop("board.commit", { loaded, tickets: Object.keys(tickets).length });
    if (loaded) requestAnimationFrame(() => hop("board.painted", { tickets: Object.keys(tickets).length }));
  }, [loaded, tickets]);

  // A phone: one lane per screen, a swipe snaps to the next; a card drags after a long press, so a swipe scrolls (layout.ts).
  const layout = useLayout();
  const sensors = useSensors(useSensor(MouseSensor, { activationConstraint: { distance: 6 } }), useSensor(TouchSensor, { activationConstraint: { delay: 350, tolerance: 8 } }), useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }));
  const lanes = useMemo(
    () => LANES.map((status) => ({ status, all: laneOf(tickets, status) })).map((l) => ({ ...l, shown: l.all.filter((t) => matchesFilter(t, filter)) })),
    [tickets, filter],
  );
  const dragged = dragId ? tickets[dragId] : undefined;

  const onStart = (e: DragStartEvent) => setDragId(String(e.active.id));
  const onOver = (e: DragOverEvent) => setOverLane(laneOfOver(e.over?.id));
  const onEnd = (e: DragEndEvent) => {
    setDragId(null);
    setOverLane(null);
    const id = String(e.active.id);
    const status = laneOfOver(e.over?.id);
    const t = boardStore().getState().tickets[id];
    if (!t || !status || !e.over) return;
    const full = laneOf(boardStore().getState().tickets, status).map((x) => x.id);
    const overIsLane = String(e.over.id).startsWith("lane:");
    const to = overIsLane ? full.length : full.indexOf(String(e.over.id));
    const board = boardStore().getState();
    if (t.status === status) {
      const from = full.indexOf(id);
      if (from === to || (overIsLane && from === full.length - 1)) return;
      board.reorder(status, arrayMove(full, from, overIsLane ? full.length - 1 : to));
    } else board.move(id, status, to);
  };

  return (
    <AppDialog
      open
      // A ticket's detail stacks above the board: Esc closes that first, never the board under it.
      onOpenChange={(o) => !o && !openId && !creating && !archiveOpen && onClose()}
      title="Board"
      description="ticket → dev → staging → main"
      wide="screen"
    >
      <div className="flex h-full min-h-0 flex-col">
        <div className="mb-3 flex items-start justify-between gap-2">
          <div className="min-w-0 flex-1">
            <FilterBar />
          </div>
          <Button size="sm" variant="outline" onClick={() => setArchiveOpen(true)} title="Archived tickets: search and restore">
            <Archive /> Archive
          </Button>
          <Button size="sm" onClick={() => setCreating(true)}>
            <Plus /> New ticket
          </Button>
        </div>
      {refusal && (
        <div role="alert" className="mb-3 flex items-start justify-between gap-2 rounded-lg border border-danger/60 bg-danger/10 p-2 text-xs text-danger">
          <span>{refusal}</span>
          <Button variant="ghost" size="icon" className="size-5" aria-label="Dismiss" onClick={() => boardStore().getState().clearRefusal()}>
            <X className="size-3" />
          </Button>
        </div>
      )}
      {!loaded ? (
        <EmptyState icon={<LayoutGrid className="size-6" />} title={link === "open" ? "Loading the board…" : "Waiting for the daemon…"} hint={link === "open" ? undefined : "The board appears when the connection opens."} />
      ) : (
        <DndContext sensors={sensors} collisionDetection={closestCorners} onDragStart={onStart} onDragOver={onOver} onDragEnd={onEnd} onDragCancel={() => (setDragId(null), setOverLane(null))}>
          <div className={layout.laneSnap ? "grid min-h-0 flex-1 grid-rows-[minmax(0,1fr)] snap-x snap-mandatory auto-cols-[100%] grid-flow-col gap-3 overflow-x-auto overscroll-x-contain pb-2 [&>*]:snap-start" : "grid min-h-0 flex-1 grid-rows-[minmax(0,1fr)] auto-cols-[minmax(240px,1fr)] grid-flow-col gap-3 overflow-x-auto pb-2"}>
            {lanes.map((l) => (
              <Lane key={l.status} status={l.status} title={LANE_TITLES[l.status]} tickets={l.shown} total={l.all.length} over={overLane === l.status && !!dragId} onOpen={setOpenId} />
            ))}
          </div>
          <DragOverlay dropAnimation={{ duration: 180 }}>{dragged ? <TicketCardView t={dragged} overlay /> : null}</DragOverlay>
        </DndContext>
      )}
      <ArchiveDialog open={archiveOpen} onClose={() => setArchiveOpen(false)} />
      <TicketDialog ticketId={openId} creating={creating} onClose={() => (setOpenId(null), setCreating(false))} />
      </div>
    </AppDialog>
  );
}
