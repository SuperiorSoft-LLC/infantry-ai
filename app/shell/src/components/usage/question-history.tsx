// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// PARITY-AUDIT-1 #19: every answered owner question (the daemon keeps them all; Mail shows only the last 7 days):
// the answer, who answered and when, with a search. Collapsed until opened so the Attention tab stays about now.
import { useState } from "react";
import { ChevronDown, ChevronRight } from "lucide-react";
import { decisionAnswer, questionHistory, type OwnerQuestion } from "@infantry/client";
import { Chip, Input, SectionLabel } from "@infantry/ui";

function when(at: number): string {
  return new Date(at).toLocaleString();
}

export function QuestionHistory({ questions }: { questions: Record<string, OwnerQuestion> }) {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const list = questionHistory(questions, query);
  const total = questionHistory(questions).length;
  return (
    <section>
      <button type="button" aria-expanded={open} onClick={() => setOpen(!open)} className="flex w-full items-center gap-1 text-left">
        {open ? <ChevronDown className="size-3.5" /> : <ChevronRight className="size-3.5" />}
        <SectionLabel>Answered questions ({total})</SectionLabel>
      </button>
      {open && (
        <div className="mt-2 space-y-2">
          <Input aria-label="Search answered questions" placeholder="Search questions, answers, roles" value={query} onChange={(e) => setQuery(e.target.value)} className="h-7 text-xs" />
          {list.length === 0 ? (
            <p className="text-xs text-muted-foreground">No answered question matches.</p>
          ) : (
            <ul className="space-y-1.5">
              {list.map((q) => (
                <li key={q.id} className="rounded-lg border bg-card px-3 py-2">
                  <p className="text-[13px]">{q.question}</p>
                  <p className="mt-0.5 text-xs">
                    <span className="text-muted-foreground">answer: </span>
                    {decisionAnswer(q) ?? "—"}
                  </p>
                  <div className="mt-1 flex flex-wrap items-center gap-1.5 text-[11px] text-muted-foreground">
                    <Chip mono className="h-5 text-[10px]">{q.resolvedBy ?? q.state}</Chip>
                    {q.role && <span>{q.role}</span>}
                    <span>{when(q.resolvedAt ?? q.createdAt)}</span>
                  </div>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </section>
  );
}
