// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Attention tab: what needs the owner, live from the daemon. Owner questions first (answer with one click on an
// option, or write your own in the one modal), then attention items (acknowledge), then the notification bus feed.
import { useEffect, useState } from "react";
import { AlertOctagon, Bell, CheckCircle2, HelpCircle, Sparkles, X } from "lucide-react";
import { openItems, openQuestions, type OwnerQuestion } from "@infantry/client";
import { AppDialog, Button, Chip, EmptyState, SectionLabel, Textarea, cn } from "@infantry/ui";
import { useAttention } from "@/components/mail/use-mail";

function ago(at: number): string {
  const m = Math.max(0, Math.round((Date.now() - at) / 60_000));
  return m < 1 ? "now" : m < 60 ? `${m}m` : m < 1440 ? `${Math.round(m / 60)}h` : `${Math.round(m / 1440)}d`;
}

function QuestionCard({ q, onOther }: { q: OwnerQuestion; onOther: (q: OwnerQuestion) => void }) {
  const answer = useAttention((s) => s.answer);
  return (
    <li className="rounded-lg border border-warn/50 bg-card p-3">
      <div className="flex items-start gap-2">
        <HelpCircle className="mt-0.5 size-4 shrink-0 text-warn" />
        <div className="min-w-0 flex-1">
          <p className="text-[13px] font-medium">{q.question}</p>
          <p className="mt-0.5 text-xs text-muted-foreground">
            {q.agentId ?? q.role ?? q.sessionId.slice(0, 8)} · {ago(q.createdAt)}
            {q.defaultAfterMin ? ` · auto-picks in ${q.defaultAfterMin}m` : ""}
          </p>
          {q.reason && <p className="mt-1 text-xs italic text-muted-foreground">{q.reason}</p>}
        </div>
      </div>
      <div className="mt-2 flex flex-wrap gap-1.5">
        {q.options.map((o) => (
          <Button
            key={o.label}
            size="xs"
            variant={o.label === q.recommended ? "default" : "outline"}
            title={o.description}
            onClick={() => answer(q.id, o.label)}
          >
            {o.label === q.recommended && <Sparkles />} {o.label}
          </Button>
        ))}
        {q.allowOther && (
          <Button size="xs" variant="ghost" onClick={() => onOther(q)}>
            Other…
          </Button>
        )}
      </div>
    </li>
  );
}

export function AttentionPanel() {
  const items = useAttention((s) => s.items);
  const questions = useAttention((s) => s.questions);
  const notes = useAttention((s) => s.notifications);
  const refusal = useAttention((s) => s.refusal);
  const { ensureLoaded, ack, answer, dismiss, act, clearRefusal } = useAttention((s) => s);
  const [other, setOther] = useState<OwnerQuestion | null>(null);
  const [text, setText] = useState("");

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
              <QuestionCard key={q.id} q={q} onOther={(x) => (setOther(x), setText(""))} />
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
      <AppDialog
        open={other !== null}
        onOpenChange={(o) => !o && setOther(null)}
        title="Your own answer"
        description={other?.question}
        footer={
          <Button size="sm" disabled={!text.trim()} onClick={() => other && answer(other.id, null, text) && setOther(null)}>
            Answer
          </Button>
        }
      >
        <div className="p-4">
          <Textarea rows={5} value={text} onChange={(e) => setText(e.target.value)} placeholder="Write the answer the agent gets" />
        </div>
      </AppDialog>
    </div>
  );
}
