// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Needs input / To review / Working counters and the Board button. To review = tickets in the Pending lane; the Board
// badge = tickets in To Do and In Progress (the board loads once the link is open).
import { useEffect } from "react";
import { LayoutGrid } from "lucide-react";
import { cn, KeyHint } from "@infantry/ui";
import type { Agent } from "@/mock/types";
import { useDaemon } from "@/daemon/daemon";
import { useUi } from "@/state/ui-state";
import { useWorkerLanes } from "./global-workers";
import { boardStore, useBoard } from "@/components/board/use-board";

function Counter({ value, label, tone }: { value: number; label: string; tone: "muted" | "info" | "ok" }) {
  return (
    <div className="flex items-baseline gap-1.5">
      <span className={cn("text-base font-semibold", tone === "info" && "text-info", tone === "ok" && "text-ok")}>{value}</span>
      <span className="text-xs text-muted-foreground">{label}</span>
    </div>
  );
}

export function SidebarCounters({ agents, onBoard }: { agents: Agent[]; onBoard: () => void }) {
  const link = useDaemon((s) => s.connection.state);
  useEffect(() => {
    if (link === "open") boardStore().getState().ensureLoaded();
  }, [link]);
  const toReview = useBoard((s) => Object.values(s.tickets).filter((t) => t.status === "pending").length);
  const open = useBoard((s) => Object.values(s.tickets).filter((t) => t.status === "todo" || t.status === "in_progress").length);
  const needsInput = agents.filter((a) => a.status === "needs-input").length;
  const ui = useUi();
  const working = useWorkerLanes().count;
  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between gap-2 px-1">
        <Counter value={needsInput} label="Needs input" tone="muted" />
        <Counter value={toReview} label="To review" tone="info" />
        {ui.globalWorkers ? (
          <button type="button" onClick={() => ui.set("workersOpen", true)} aria-label="Show working agents in every project" data-testid="global-workers-button" className="rounded px-1 hover:bg-accent focus-visible:ring-3 focus-visible:ring-ring/50 focus-visible:outline-none">
            <Counter value={working} label="Working" tone="ok" />
          </button>
        ) : (
          <Counter value={working} label="Working" tone="ok" />
        )}
      </div>
      <button
        type="button"
        onClick={onBoard}
        className="flex w-full items-center gap-3 rounded-lg border bg-card px-3 py-2.5 text-sm font-semibold hover:bg-accent focus-visible:ring-3 focus-visible:ring-ring/50 focus-visible:outline-none"
      >
        <LayoutGrid className="size-4 text-muted-foreground" />
        <span className="flex-1 text-left">Board</span>
        {open > 0 && <span className="grid h-5 min-w-5 place-items-center rounded border border-danger/70 px-1 text-[11px] text-danger">{open}</span>}
        <KeyHint>^8</KeyHint>
      </button>
    </div>
  );
}
