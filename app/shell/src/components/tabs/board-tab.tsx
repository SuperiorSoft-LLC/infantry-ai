// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Kanban board: one bordered column per state, ticket cards inside.
import { Plus } from "lucide-react";
import { board } from "@/mock/data";
import type { Ticket } from "@/mock/types";
import { useUi } from "@/state/ui-state";
import { Button } from "@infantry/ui";
import { Chip } from "@infantry/ui";
import { RoleIcon } from "@/components/agents/role-icon";
import { TabPage } from "./tab-page";

const kindTone = { feature: "blue", bug: "red", plan: "amber", chore: "neutral" } as const;

function TicketCard({ t }: { t: Ticket }) {
  return (
    <article className="space-y-2 rounded-lg border bg-card p-3 hover:border-info/40">
      <div className="flex items-center gap-2">
        <span className="font-mono text-[11px] text-info">{t.id}</span>
        <Chip tone={kindTone[t.kind]}>{t.kind}</Chip>
      </div>
      <p className="text-[13px] leading-5 font-medium">{t.title}</p>
      <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
        <span className="inline-flex items-center gap-1">
          <RoleIcon roleId={t.roleId} className="size-3" /> {t.roleId}
        </span>
        {t.epic && <Chip>{t.epic}</Chip>}
        {t.assignee && <span className="ml-auto text-ok">{t.assignee}</span>}
      </div>
    </article>
  );
}

export function BoardTab() {
  const ui = useUi();
  return (
    <TabPage
      title="Board"
      description="Infantry AI Project · ticket → dev → staging → main"
      actions={
        <Button size="sm" onClick={() => ui.set("dialog", "new-ticket")}>
          <Plus /> New ticket
        </Button>
      }
    >
      <div className="grid auto-cols-[minmax(220px,1fr)] grid-flow-col gap-3 overflow-x-auto pb-2">
        {board.map((col) => (
          <section key={col.id} aria-label={col.title} className="flex flex-col gap-2 rounded-lg border bg-panel/40 p-2">
            <h3 className="flex items-center justify-between px-1 text-xs font-semibold">
              {col.title}
              <span className="font-normal text-muted-foreground">{col.tickets.length}</span>
            </h3>
            {col.tickets.map((t) => (
              <TicketCard key={t.id} t={t} />
            ))}
          </section>
        ))}
      </div>
    </TabPage>
  );
}
