// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// One ticket card: id, kind chip, title, role icon, epic, assignee, and the lane's actions (To Do: Start; In Progress:
// To review; Pending: Done / Back; Done: Reopen; Backlog: To do). The lane makes it draggable; the action buttons
// stop the pointer so a press is not a drag.
import type { BoardTicket } from "@infantry/client";
import { assigneeOf } from "@infantry/client";
import { Chip, cn, type Tone } from "@infantry/ui";
import { daemonStore } from "@/daemon/daemon";
import { useBoard } from "./use-board";
import { RoleIcon } from "@/components/agents/role-icon";

const kindTone: Record<string, Tone> = { defect: "red", enhancement: "blue", performance: "amber", hardening: "amber" };

/** `ticket-e27e18e0` -> `e27e18e0` (the ids the board shows and filters on). */
export function shortId(id: string): string {
  return id.replace(/^(ticket|epic)-/, "").slice(0, 8);
}

export function kindOf(t: BoardTicket): string {
  return t.ticketType ?? "ticket";
}

type Act = { label: string; to?: string; start?: true };
const ACTIONS: Record<string, Act[]> = {
  backlog: [{ label: "To do", to: "todo" }],
  todo: [{ label: "Start", start: true }],
  in_progress: [{ label: "To review", to: "pending" }],
  pending: [{ label: "Done", to: "done" }, { label: "Back", to: "in_progress" }],
  done: [{ label: "Reopen", to: "todo" }],
};
let starts = 0;

function CardActions({ t }: { t: BoardTicket }) {
  const move = useBoard((s) => s.move);
  const acts = ACTIONS[t.status] ?? [];
  if (!acts.length) return null;
  const run = (a: Act) =>
    a.start
      ? daemonStore().getState().sendFrame({ type: "ticket_start", data: JSON.stringify({ id: t.id }), requestId: `ui:start:${(starts += 1).toString(36)}` })
      : a.to && move(t.id, a.to);
  return (
    <div className="flex gap-1.5" onPointerDown={(e) => e.stopPropagation()} onKeyDown={(e) => e.stopPropagation()}>
      {acts.map((a) => (
        <button
          key={a.label}
          type="button"
          onClick={(e) => (e.stopPropagation(), run(a))}
          aria-label={`${a.label}: ${t.title}`}
          className="rounded-md border px-1.5 text-[11px] leading-5 text-muted-foreground hover:border-info/50 hover:text-foreground"
        >
          {a.label}
        </button>
      ))}
    </div>
  );
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
      {!overlay && <CardActions t={t} />}
    </article>
  );
}
