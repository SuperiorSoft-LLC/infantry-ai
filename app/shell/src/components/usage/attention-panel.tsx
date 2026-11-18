// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Attention tab: what needs the owner, newest first, each with its agent and a primary action.
import { AlertOctagon, CheckCircle2, HelpCircle, ShieldQuestion } from "lucide-react";
import { agents, attention } from "@/mock/data";
import type { AttentionItem } from "@/mock/types";
import { Button } from "@infantry/ui";
import { EmptyState } from "@infantry/ui";
import { AgentAvatar } from "@/components/agents/agent-avatar";

const meta: Record<AttentionItem["kind"], { icon: typeof HelpCircle; tone: string; action: string }> = {
  question: { icon: HelpCircle, tone: "text-warn", action: "Answer" },
  review: { icon: CheckCircle2, tone: "text-info", action: "Review" },
  permission: { icon: ShieldQuestion, tone: "text-warn", action: "Allow" },
  error: { icon: AlertOctagon, tone: "text-danger", action: "Open" },
};

export function AttentionPanel() {
  if (attention.length === 0) {
    return <EmptyState icon={<CheckCircle2 className="size-6" />} title="Nothing needs you" hint="Questions and reviews show up here." />;
  }
  return (
    <ul className="space-y-2">
      {attention.map((item) => {
        const m = meta[item.kind];
        const agent = agents.find((a) => a.id === item.agentId);
        return (
          <li key={item.id} className="rounded-lg border bg-card p-3">
            <div className="flex items-start gap-3">
              {agent && <AgentAvatar agent={agent} size="sm" />}
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-1.5 text-[13px] font-medium">
                  <m.icon className={`size-3.5 shrink-0 ${m.tone}`} />
                  <span className="truncate">{item.title}</span>
                </div>
                <div className="truncate text-xs text-muted-foreground">
                  {agent?.name} · {item.detail} · {item.age}
                </div>
              </div>
            </div>
            <div className="mt-2 flex justify-end gap-2">
              <Button variant="ghost" size="xs">
                Dismiss
              </Button>
              <Button variant="outline" size="xs">
                {m.action}
              </Button>
            </div>
          </li>
        );
      })}
    </ul>
  );
}
