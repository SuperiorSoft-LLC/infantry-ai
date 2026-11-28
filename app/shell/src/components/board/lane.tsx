// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// One lane: a droppable, sortable column of ticket cards. A long lane is virtualized (TanStack Virtual). The header
// collapses a lane to its title and count (remembered per lane in this browser); a collapsed lane still takes drops.
import { useState } from "react";
import { ChevronDown, ChevronRight } from "lucide-react";
import { useDroppable } from "@dnd-kit/core";
import { SortableContext, useSortable, verticalListSortingStrategy } from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";
import { useVirtualizer } from "@tanstack/react-virtual";
import type { BoardTicket } from "@infantry/client";
import { TicketCardView, shortId } from "./ticket-card";
import { isSelectClick, selectionStore } from "./selection";

/** Lanes longer than this render only the cards in view. */
export const VIRTUAL_AFTER = 40;
const ESTIMATE_PX = 96;
const GAP_PX = 8;

export const laneDropId = (status: string) => `lane:${status}`;

const COLLAPSE_KEY = "infantry.board.collapsed";
function readCollapsed(): string[] {
  try {
    const v = JSON.parse(localStorage.getItem(COLLAPSE_KEY) ?? "[]");
    return Array.isArray(v) ? v : [];
  } catch {
    return [];
  }
}
function writeCollapsed(status: string, on: boolean) {
  try {
    const set = new Set(readCollapsed());
    if (on) set.add(status);
    else set.delete(status);
    localStorage.setItem(COLLAPSE_KEY, JSON.stringify([...set]));
  } catch {
    /* storage blocked: the lane still collapses for this view */
  }
}

function SortableCard({ t, onOpen }: { t: BoardTicket; onOpen: (id: string) => void }) {
  const s = useSortable({ id: t.id, data: { status: t.status } });
  return (
    <TicketCardView
      ref={s.setNodeRef as never}
      t={t}
      dragging={s.isDragging}
      style={{ transform: CSS.Translate.toString(s.transform), transition: s.transition }}
      aria-label={`${t.title} (${shortId(t.id)})`}
      onClick={(e) => (isSelectClick(e) ? selectionStore.getState().toggle(t.id) : onOpen(t.id))}
      {...s.attributes}
      {...s.listeners}
    />
  );
}

function VirtualCards({ tickets, onOpen, scroller }: { tickets: BoardTicket[]; onOpen: (id: string) => void; scroller: HTMLDivElement }) {
  const v = useVirtualizer({ count: tickets.length, getScrollElement: () => scroller, estimateSize: () => ESTIMATE_PX + GAP_PX, overscan: 6 });
  return (
    <div style={{ height: v.getTotalSize(), position: "relative" }}>
      {v.getVirtualItems().map((row) => (
        <div key={tickets[row.index].id} data-index={row.index} ref={v.measureElement} style={{ position: "absolute", top: 0, left: 0, width: "100%", transform: `translateY(${row.start}px)`, paddingBottom: GAP_PX }}>
          <SortableCard t={tickets[row.index]} onOpen={onOpen} />
        </div>
      ))}
    </div>
  );
}

export function Lane({ status, title, tickets, total, over, onOpen }: { status: string; title: string; tickets: BoardTicket[]; total: number; over: boolean; onOpen: (id: string) => void }) {
  const drop = useDroppable({ id: laneDropId(status) });
  // A state ref, not a ref object: the virtualized list mounts before the scroll box is attached, and must re-render once it is
  // (a null scroll element rendered an empty lane under a non-zero count).
  const [scroller, setScroller] = useState<HTMLDivElement | null>(null);
  const filtered = tickets.length !== total;
  const [collapsed, setCollapsed] = useState(() => readCollapsed().includes(status));
  const toggle = () => {
    writeCollapsed(status, !collapsed);
    setCollapsed(!collapsed);
  };
  return (
    <section ref={drop.setNodeRef} aria-label={title} data-over={over || undefined} className="board-lane flex min-h-0 flex-col gap-2 rounded-lg border bg-panel/40 p-2">
      <h3 className="flex items-center justify-between px-1 text-xs font-semibold">
        <button type="button" onClick={toggle} aria-expanded={!collapsed} className="flex items-center gap-1 hover:text-foreground">
          {collapsed ? <ChevronRight className="size-3.5" /> : <ChevronDown className="size-3.5" />}
          {title}
        </button>
        <span className="font-normal text-muted-foreground">{filtered ? `${tickets.length} of ${total}` : total}</span>
      </h3>
      {!collapsed && (
      <div ref={setScroller} className="flex min-h-16 flex-1 flex-col gap-2 overflow-y-auto pr-0.5">
        <SortableContext items={tickets.map((t) => t.id)} strategy={verticalListSortingStrategy}>
          {tickets.length === 0 ? (
            <p className="px-1 py-3 text-center text-xs text-muted-foreground">{filtered ? "No match" : "Nothing here"}</p>
          ) : tickets.length > VIRTUAL_AFTER ? (
            scroller ? <VirtualCards tickets={tickets} onOpen={onOpen} scroller={scroller} /> : <p className="px-1 py-3 text-center text-xs text-muted-foreground">Loading…</p>
          ) : (
            tickets.map((t) => <SortableCard key={t.id} t={t} onOpen={onOpen} />)
          )}
        </SortableContext>
      </div>
      )}
    </section>
  );
}
