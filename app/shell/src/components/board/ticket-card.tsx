// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// One ticket card: id, kind chip, title, role icon, epic, assignee. Presentational; the lane makes it draggable.
import type { BoardTicket } from "@infantry/client";
import { assigneeOf } from "@infantry/client";
import { Chip, cn, type Tone } from "@infantry/ui";
import { RoleIcon } from "@/components/agents/role-icon";

const kindTone: Record<string, Tone> = { defect: "red", enhancement: "blue", performance: "amber", hardening: "amber" };

/** `ticket-e27e18e0` -> `e27e18e0` (the ids the board shows and filters on). */
export function shortId(id: string): string {
  return id.replace(/^(ticket|epic)-/, "").slice(0, 8);
}

export function kindOf(t: BoardTicket): string {
  return t.ticketType ?? "ticket";
}

export function TicketCardView({ t, dragging, overlay, ...rest }: { t: BoardTicket; dragging?: boolean; overlay?: boolean } & React.ComponentProps<"article">) {
  const who = assigneeOf(t);
  return (
    <article
      {...rest}
      data-dragging={dragging || undefined}
      className={cn("board-card space-y-2 rounded-lg border bg-card p-3 text-left hover:border-info/40", overlay && "board-overlay", rest.className)}
    >
      <div className="flex items-center gap-2">
        <span className="font-mono text-[11px] text-info">{shortId(t.id)}</span>
        <Chip tone={kindTone[kindOf(t)] ?? "neutral"}>{kindOf(t)}</Chip>
        {t.wontFix && <Chip>won't fix</Chip>}
        {(t.heldBy?.length ?? 0) > 0 && <Chip tone="amber">held</Chip>}
      </div>
      <p className="text-[13px] leading-5 font-medium break-words">{t.title}</p>
      <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
        {t.role && (
          <span className="inline-flex items-center gap-1">
            <RoleIcon roleId={t.role} className="size-3" /> {t.role}
          </span>
        )}
        {t.epicId && <Chip>{shortId(t.epicId)}</Chip>}
        {who && <span className="ml-auto max-w-[45%] truncate text-ok">{who}</span>}
      </div>
    </article>
  );
}
