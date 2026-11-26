// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The one owner-decision card (an agent's `infantry_ask` question): one button per option, the recommended one filled,
// "Other…" for own words. Answered, it shows "answered: <choice>". Used by the Mail inbox and the Attention tab.
import { useState } from "react";
import { CheckCircle2, HelpCircle, Sparkles } from "lucide-react";
import { decisionAnswer, type OwnerQuestion } from "@infantry/client";
import { AppDialog, Button, Chip, Textarea, cn } from "@infantry/ui";
import { useAttention } from "./use-mail";

function ago(at: number): string {
  const m = Math.max(0, Math.round((Date.now() - at) / 60_000));
  return m < 1 ? "now" : m < 60 ? `${m}m` : m < 1440 ? `${Math.round(m / 60)}h` : `${Math.round(m / 1440)}d`;
}

const RESOLVED_WORD: Record<string, string> = { answered: "answered", auto: "auto-picked", cancelled: "withdrawn", stale: "closed" };

export function DecisionCard({ q, onOther, as: Tag = "li" }: { q: OwnerQuestion; onOther: (q: OwnerQuestion) => void; as?: "li" | "article" }) {
  const answer = useAttention((s) => s.answer);
  const open = q.state === "open";
  const picked = decisionAnswer(q);
  return (
    <Tag className={cn("block rounded-lg border bg-card p-3", open ? "border-warn/50" : "opacity-80")} aria-label={open ? "Decision waiting for you" : "Decision"}>
      <div className="flex items-start gap-2">
        {open ? <HelpCircle className="mt-0.5 size-4 shrink-0 text-warn" /> : <CheckCircle2 className="mt-0.5 size-4 shrink-0 text-ok" />}
        <div className="min-w-0 flex-1">
          <p className="text-[13px] font-medium break-words">{q.question}</p>
          <p className="mt-0.5 text-xs text-muted-foreground">
            {q.agentId ?? q.role ?? q.sessionId.slice(0, 8)} · {ago(q.createdAt)}
            {open && q.defaultAfterMin ? ` · auto-picks in ${q.defaultAfterMin}m` : ""}
          </p>
          {open && q.reason && <p className="mt-1 text-xs italic text-muted-foreground">{q.reason}</p>}
        </div>
      </div>
      {open ? (
        <div className="mt-2 flex flex-wrap gap-1.5">
          {q.options.map((o) => (
            <Button
              key={o.label}
              size="xs"
              variant={o.label === q.recommended ? "default" : "outline"}
              title={o.description || (o.label === q.recommended ? "Recommended" : undefined)}
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
      ) : (
        <div className="mt-2 flex flex-wrap items-center gap-1.5">
          <Chip tone={q.state === "answered" || q.state === "auto" ? "green" : "neutral"} className="h-5 text-[10px]">
            {RESOLVED_WORD[q.state] ?? q.state}
            {picked ? `: ${picked}` : ""}
          </Chip>
        </div>
      )}
    </Tag>
  );
}

/** The one modal for an own-words answer to a decision; [question] null = closed. */
export function OwnAnswerDialog({ question, onClose }: { question: OwnerQuestion | null; onClose: () => void }) {
  const answer = useAttention((s) => s.answer);
  const [text, setText] = useState("");
  const close = (): void => {
    setText("");
    onClose();
  };
  return (
    <AppDialog
      open={question !== null}
      onOpenChange={(o) => !o && close()}
      title="Your own answer"
      description={question?.question}
      footer={
        <Button size="sm" disabled={!text.trim()} onClick={() => question && answer(question.id, null, text) && close()}>
          Answer
        </Button>
      }
    >
      <div className="p-4">
        <Textarea rows={5} value={text} onChange={(e) => setText(e.target.value)} placeholder="Write the answer the agent gets" />
      </div>
    </AppDialog>
  );
}
