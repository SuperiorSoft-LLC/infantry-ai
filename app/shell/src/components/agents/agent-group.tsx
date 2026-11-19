// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// One sidebar group (Waiting on you / Ready to check / Running / Quiet): header with a count, plus its cards.
import type { Agent } from "@/mock/types";
import { cn } from "@infantry/ui";
import { AgentCard } from "./agent-card";

const DOT = { ok: "bg-ok", muted: "bg-muted-foreground", warn: "bg-warn", info: "bg-info" } as const;
const TEXT = { ok: "text-ok", muted: "text-muted-foreground", warn: "text-warn", info: "text-info" } as const;

export function AgentGroup({
  title,
  tone,
  agents,
  selectedId,
  onSelect,
}: {
  title: string;
  tone: "ok" | "muted" | "warn" | "info";
  agents: Agent[];
  selectedId: string;
  onSelect: (id: string) => void;
}) {
  if (agents.length === 0) return null;
  return (
    <section aria-label={title} className="space-y-2">
      <h2 className="flex items-center gap-2 px-1 pt-2 text-xs font-semibold">
        <span className={cn("size-1.5 rounded-full", DOT[tone])} />
        <span className={TEXT[tone]}>{title}</span>
        <span className="font-normal text-muted-foreground">{agents.length}</span>
      </h2>
      {agents.map((a) => (
        <AgentCard key={a.id} agent={a} selected={a.id === selectedId} onSelect={onSelect} />
      ))}
    </section>
  );
}
