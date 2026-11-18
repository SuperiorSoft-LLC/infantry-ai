// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The agent card's "⋯" menu (also on right-click), after the Kotlin Sidebar card menu: Open, Resume session (dormant),
// the current ticket with Change / Assign ticket… and Unassign, Remove (confirm). The ticket picker searches To Do
// tickets and assigns now or adds to the agent's queue (`ticket_assign` {id, agentId, queue}). Tickets go to an agent
// profile, so a session with no profile says so instead.
import { useEffect, useMemo, useState } from "react";
import { ListPlus, MoreHorizontal, Play, SquareArrowOutUpRight, Ticket, Trash2, Unlink } from "lucide-react";
import {
  AppDialog,
  Button,
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
  Input,
  cn,
} from "@infantry/ui";
import type { BoardTicket } from "@infantry/client";
import { daemonStore, useDaemon } from "@/daemon/daemon";
import { boardStore, useBoard } from "@/components/board/use-board";
import { openSessionDialog } from "@/components/session/session-actions";
import { useUi } from "@/state/ui-state";

const DORMANT = new Set(["EXITED", "ORPHANED", "FAILED"]);
let counter = 0;
const send = (type: string, data: unknown) => daemonStore().getState().sendFrame({ type, data: JSON.stringify(data), requestId: `agent-menu:${(counter += 1).toString(36)}` });

/** Assign [ticketId] to the agent profile [agentId]: now (it becomes the current ticket) or queued behind it. */
export function assignTicket(ticketId: string, agentId: string, queue = false): boolean {
  return send("ticket_assign", { id: ticketId, agentId, queue });
}

export function AgentMenu({ sessionId, open, onOpenChange }: { sessionId: string; open: boolean; onOpenChange: (o: boolean) => void }) {
  const ui = useUi();
  const r = useDaemon((s) => s.sessions[sessionId]?.record);
  const ticket = useBoard((s) => (r?.ticketId ? s.tickets[r.ticketId] : undefined));
  const [picking, setPicking] = useState(false);
  if (!r) return null;
  const dormant = DORMANT.has(r.state);
  return (
    <>
      <DropdownMenu open={open} onOpenChange={onOpenChange}>
        <DropdownMenuTrigger asChild>
          <button
            type="button"
            aria-label="Agent actions"
            className={cn(
              "absolute top-1.5 right-1.5 z-10 grid size-6 place-items-center rounded-md text-muted-foreground hover:bg-accent hover:text-foreground",
              "opacity-0 group-hover/card:opacity-100 focus-visible:opacity-100 data-[state=open]:opacity-100",
            )}
          >
            <MoreHorizontal className="size-4" />
          </button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-60">
          <DropdownMenuItem onSelect={() => (ui.set("selectedAgentId", sessionId), ui.set("sessionTab", "cli"))}>
            <SquareArrowOutUpRight /> Open
          </DropdownMenuItem>
          {dormant && (
            <DropdownMenuItem onSelect={() => daemonStore().getState().spawnWith({ resume: sessionId, cwd: r.workingDir ?? undefined })}>
              <Play /> Resume session
            </DropdownMenuItem>
          )}
          <DropdownMenuSeparator />
          <DropdownMenuLabel className="truncate text-xs font-normal text-muted-foreground">
            {r.ticketId ? `Ticket: ${ticket?.title ?? r.ticketId}` : "No ticket"}
          </DropdownMenuLabel>
          <DropdownMenuItem disabled={!r.agentId} onSelect={() => setPicking(true)}>
            <Ticket /> {r.ticketId ? "Change ticket…" : "Assign ticket…"}
          </DropdownMenuItem>
          {!r.agentId && <p className="px-2 pb-1 text-[11px] text-muted-foreground">Tickets go to an agent profile; this session has none.</p>}
          {r.ticketId && (
            <DropdownMenuItem onSelect={() => send("ticket_unassign", { id: r.ticketId })}>
              <Unlink /> Unassign
            </DropdownMenuItem>
          )}
          <DropdownMenuSeparator />
          <DropdownMenuItem variant="destructive" onSelect={() => openSessionDialog("delete", sessionId)}>
            <Trash2 /> Remove
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
      {picking && r.agentId && <TicketPicker agentId={r.agentId} current={r.ticketId} onClose={() => setPicking(false)} />}
    </>
  );
}

/** Search To Do tickets (held ones behind a toggle); Up/Down/Enter picks, Esc closes. */
export function TicketPicker({ agentId, current, onClose }: { agentId: string; current?: string | null; onClose: () => void }) {
  const tickets = useBoard((s) => s.tickets);
  const link = useDaemon((s) => s.connection.state);
  const [q, setQ] = useState("");
  const [held, setHeld] = useState(false);
  const [at, setAt] = useState(0);
  useEffect(() => {
    if (link === "open") boardStore().getState().ensureLoaded();
  }, [link]);
  const list = useMemo(() => {
    const needle = q.trim().toLowerCase();
    return Object.values(tickets)
      .filter((t: BoardTicket) => t.status === "todo" && t.id !== current && (held || !t.heldBy?.length))
      .filter((t) => !needle || `${t.title} ${t.id}`.toLowerCase().includes(needle))
      .sort((a, b) => a.position - b.position);
  }, [tickets, q, held, current]);
  const pick = (t: BoardTicket | undefined, queue: boolean) => {
    if (!t) return;
    assignTicket(t.id, agentId, queue);
    onClose();
  };
  return (
    <AppDialog
      open
      onOpenChange={(o) => !o && onClose()}
      title="Assign a ticket"
      description="Assign makes it the agent's current ticket; Queue puts it after the current one."
      footer={
        <>
          <label className="mr-auto flex items-center gap-1.5 self-center text-xs text-muted-foreground">
            <input type="checkbox" checked={held} onChange={(e) => setHeld(e.target.checked)} /> Show held tickets
          </label>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
        </>
      }
    >
      <Input
        autoFocus
        placeholder="Search To Do tickets"
        aria-label="Search To Do tickets"
        value={q}
        onChange={(e) => (setQ(e.target.value), setAt(0))}
        onKeyDown={(e) => {
          if (e.key === "ArrowDown") (e.preventDefault(), setAt((i) => Math.min(i + 1, list.length - 1)));
          else if (e.key === "ArrowUp") (e.preventDefault(), setAt((i) => Math.max(i - 1, 0)));
          else if (e.key === "Enter") (e.preventDefault(), pick(list[at], e.shiftKey));
        }}
      />
      <ul role="listbox" aria-label="To Do tickets" className="mt-2 max-h-72 space-y-1 overflow-y-auto">
        {list.length === 0 && <li className="p-2 text-xs text-muted-foreground">No matching To Do tickets.</li>}
        {list.map((t, i) => (
          <li key={t.id} role="option" aria-selected={i === at} className={cn("flex items-center gap-2 rounded-md border px-2 py-1.5 text-sm", i === at ? "border-info/60 bg-accent" : "")}>
            <span className="min-w-0 flex-1 truncate" title={t.title}>
              {t.title}
              {!!t.heldBy?.length && <span className="ml-1 text-xs text-warn">held</span>}
            </span>
            <Button size="sm" variant="outline" onClick={() => pick(t, false)}>
              Assign
            </Button>
            <Button size="sm" variant="ghost" aria-label={`Queue ${t.title}`} onClick={() => pick(t, true)}>
              <ListPlus />
            </Button>
          </li>
        ))}
      </ul>
      <p className="mt-2 text-[11px] text-muted-foreground">Enter assigns, Shift+Enter queues.</p>
    </AppDialog>
  );
}
