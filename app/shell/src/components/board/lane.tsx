// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// One lane: a droppable, sortable column of ticket cards. A long lane is virtualized (TanStack Virtual).
import { useRef } from "react";
import { useDroppable } from "@dnd-kit/core";
import { SortableContext, useSortable, verticalListSortingStrategy } from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";
import { useVirtualizer } from "@tanstack/react-virtual";
import type { BoardTicket } from "@infantry/client";
import { TicketCardView, shortId } from "./ticket-card";

/** Lanes longer than this render only the cards in view. */
export const VIRTUAL_AFTER = 40;
const ESTIMATE_PX = 96;
const GAP_PX = 8;

export const laneDropId = (status: string) => `lane:${status}`;

function SortableCard({ t, onOpen }: { t: BoardTicket; onOpen: (id: string) => void }) {
  const s = useSortable({ id: t.id, data: { status: t.status } });
  return (
    <TicketCardView
      ref={s.setNodeRef as never}
      t={t}
      dragging={s.isDragging}
      style={{ transform: CSS.Translate.toString(s.transform), transition: s.transition }}
      aria-label={`${t.title} (${shortId(t.id)})`}
      onClick={() => onOpen(t.id)}
      {...s.attributes}
      {...s.listeners}
    />
  );
}

function VirtualCards({ tickets, onOpen, scrollRef }: { tickets: BoardTicket[]; onOpen: (id: string) => void; scrollRef: React.RefObject<HTMLDivElement | null> }) {
  const v = useVirtualizer({ count: tickets.length, getScrollElement: () => scrollRef.current, estimateSize: () => ESTIMATE_PX + GAP_PX, overscan: 6 });
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
  const scrollRef = useRef<HTMLDivElement>(null);
  const filtered = tickets.length !== total;
  return (
    <section ref={drop.setNodeRef} aria-label={title} data-over={over || undefined} className="board-lane flex min-h-40 flex-col gap-2 rounded-lg border bg-panel/40 p-2">
      <h3 className="flex items-center justify-between px-1 text-xs font-semibold">
        {title}
        <span className="font-normal text-muted-foreground">{filtered ? `${tickets.length} of ${total}` : total}</span>
      </h3>
      <div ref={scrollRef} className="flex max-h-[calc(100vh-17rem)] min-h-16 flex-col gap-2 overflow-y-auto pr-0.5">
        <SortableContext items={tickets.map((t) => t.id)} strategy={verticalListSortingStrategy}>
          {tickets.length === 0 ? (
            <p className="px-1 py-3 text-center text-xs text-muted-foreground">{filtered ? "No match" : "Nothing here"}</p>
          ) : tickets.length > VIRTUAL_AFTER ? (
            <VirtualCards tickets={tickets} onOpen={onOpen} scrollRef={scrollRef} />
          ) : (
            tickets.map((t) => <SortableCard key={t.id} t={t} onOpen={onOpen} />)
          )}
        </SortableContext>
      </div>
    </section>
  );
}
