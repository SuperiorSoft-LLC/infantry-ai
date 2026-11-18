// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// "Running" / "Quiet" header plus its cards.
import type { Agent } from "@/mock/types";
import { cn } from "@infantry/ui";
import { AgentCard } from "./agent-card";

export function AgentGroup({
  title,
  tone,
  agents,
  selectedId,
  onSelect,
}: {
  title: string;
  tone: "ok" | "muted";
  agents: Agent[];
  selectedId: string;
  onSelect: (id: string) => void;
}) {
  if (agents.length === 0) return null;
  return (
    <section aria-label={title} className="space-y-2">
      <h2 className="flex items-center gap-2 px-1 pt-2 text-xs font-semibold">
        <span className={cn("size-1.5 rounded-full", tone === "ok" ? "bg-ok" : "bg-muted-foreground")} />
        <span className={tone === "ok" ? "text-ok" : "text-muted-foreground"}>{title}</span>
        <span className="font-normal text-muted-foreground">{agents.length}</span>
      </h2>
      {agents.map((a) => (
        <AgentCard key={a.id} agent={a} selected={a.id === selectedId} onSelect={onSelect} />
      ))}
    </section>
  );
}
