// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Attention tab: what needs the owner, live from the daemon. Owner questions first (answer with one click on an
// option, or write your own in the one modal), then attention items (acknowledge), then the notification bus feed.
import { useEffect, useState } from "react";
import { AlertOctagon, Bell, CheckCircle2, X } from "lucide-react";
import { openItems, openQuestions, type OwnerQuestion } from "@infantry/client";
import { Button, Chip, EmptyState, SectionLabel, cn } from "@infantry/ui";
import { DecisionCard, OwnAnswerDialog } from "@/components/mail/decision-card";
import { useAttention } from "@/components/mail/use-mail";

function ago(at: number): string {
  const m = Math.max(0, Math.round((Date.now() - at) / 60_000));
  return m < 1 ? "now" : m < 60 ? `${m}m` : m < 1440 ? `${Math.round(m / 60)}h` : `${Math.round(m / 1440)}d`;
}

export function AttentionPanel() {
  const items = useAttention((s) => s.items);
  const questions = useAttention((s) => s.questions);
  const notes = useAttention((s) => s.notifications);
  const refusal = useAttention((s) => s.refusal);
  const { ensureLoaded, ack, dismiss, act, clearRefusal } = useAttention((s) => s);
  const [other, setOther] = useState<OwnerQuestion | null>(null);

  useEffect(() => ensureLoaded(), [ensureLoaded]);
  const qs = openQuestions(questions);
  const open = openItems(items);

  if (qs.length === 0 && open.length === 0 && notes.length === 0) {
    return <EmptyState icon={<CheckCircle2 className="size-6" />} title="Nothing needs you" hint="Questions, failures and notices show up here." />;
  }
  return (
    <div className="space-y-4">
      {refusal && (
        <div role="alert" className="flex items-center justify-between rounded-lg border border-danger/60 bg-danger/10 px-3 py-2 text-xs text-danger">
          <span>{refusal}</span>
          <Button variant="ghost" size="xs" onClick={clearRefusal}>
            OK
          </Button>
        </div>
      )}
      {qs.length > 0 && (
        <section>
          <SectionLabel>Questions ({qs.length})</SectionLabel>
          <ul className="mt-2 space-y-2">
            {qs.map((q) => (
              <DecisionCard key={q.id} q={q} onOther={setOther} />
            ))}
          </ul>
        </section>
      )}
      {open.length > 0 && (
        <section>
          <SectionLabel>Needs a look ({open.length})</SectionLabel>
          <ul className="mt-2 space-y-2">
            {open.map((i) => (
              <li key={i.id} className={cn("rounded-lg border bg-card p-3", i.kind.includes("fail") && "border-danger/50")}>
                <div className="flex items-start gap-2">
                  <AlertOctagon className={cn("mt-0.5 size-4 shrink-0", i.kind.includes("fail") ? "text-danger" : "text-warn")} />
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-1.5">
                      <Chip mono className="h-5 text-[10px]">
                        {i.kind}
                      </Chip>
                      <span className="text-[11px] text-muted-foreground">{ago(i.at)}</span>
                    </div>
                    <p className="mt-1 text-[13px]">{i.outcome}</p>
                    {i.detail && <p className="truncate text-xs text-muted-foreground">{i.detail}</p>}
                    {i.ticketId && <p className="text-xs text-muted-foreground">ticket {i.ticketId}</p>}
                  </div>
                </div>
                <div className="mt-2 flex justify-end">
                  <Button variant="outline" size="xs" onClick={() => ack(i.id)}>
                    Acknowledge
                  </Button>
                </div>
              </li>
            ))}
          </ul>
        </section>
      )}
      {notes.length > 0 && (
        <section>
          <SectionLabel>Notifications</SectionLabel>
          <ul className="mt-2 space-y-1.5">
            {notes.map((n) => (
              <li key={n.id} className="flex items-start gap-2 rounded-lg border bg-card px-3 py-2">
                <Bell className={cn("mt-0.5 size-3.5 shrink-0", n.level === "error" ? "text-danger" : n.level === "warning" ? "text-warn" : "text-info")} />
                <div className="min-w-0 flex-1">
                  <p className="truncate text-[13px] font-medium">{n.title}</p>
                  {n.body && <p className="line-clamp-2 text-xs text-muted-foreground">{n.body}</p>}
                  {n.actions && n.actions.length > 0 && (
                    <div className="mt-1.5 flex gap-1.5">
                      {n.actions.map((a) => (
                        <Button key={a.id} size="xs" variant="outline" onClick={() => act(n.id, a.id)}>
                          {a.label}
                        </Button>
                      ))}
                    </div>
                  )}
                </div>
                <button type="button" aria-label="Dismiss" onClick={() => dismiss(n.id)} className="text-muted-foreground hover:text-foreground">
                  <X className="size-3.5" />
                </button>
              </li>
            ))}
          </ul>
        </section>
      )}
      <OwnAnswerDialog question={other} onClose={() => setOther(null)} />
    </div>
  );
}
