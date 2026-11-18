// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The open prompts of one session, inline above the composer: a tool approval (allow once / for the session /
// always / deny) or a question with its choices and, when allowed, the owner's own answer. Answering sends
// `prompt_response`; the card goes when the daemon's `prompt_resolved` arrives (or at once on send). A card takes
// keys while it has focus (@infantry/client promptKey): arrows, digits, Enter, Space in a multi-select, Esc denies an
// approval. Keys never fire from the composer or the answer box.
import { useMemo, useState } from "react";
import { ShieldQuestion, MessageCircleQuestion } from "lucide-react";
import { promptKey, promptsOf, type OpenPrompt } from "@infantry/client";
import { Button, Chip, Input, PanelCard, cn } from "@infantry/ui";
import { usePrompts } from "./use-prompts";

const DECISIONS: { id: string; label: string; variant: "default" | "outline" | "destructive" }[] = [
  { id: "allow_once", label: "Allow once", variant: "default" },
  { id: "allow_session", label: "Allow for this session", variant: "outline" },
  { id: "allow_always", label: "Always allow", variant: "outline" },
  { id: "deny", label: "Deny", variant: "destructive" },
];

function expiryText(p: OpenPrompt): string | null {
  if (!p.expiresAt) return null;
  const min = Math.max(0, Math.round((p.expiresAt - Date.now()) / 60000));
  return `answers itself in ~${min} min`;
}

function ApprovalCard({ p }: { p: OpenPrompt }) {
  const decide = usePrompts((s) => s.decide);
  const [failed, setFailed] = useState(false);
  const [at, setAt] = useState(() => Math.max(0, DECISIONS.findIndex((d) => d.id === p.recommended)));
  const choose = (i: number) => setFailed(!decide(p.promptId, DECISIONS[i].id));
  const onKey = (e: React.KeyboardEvent) => {
    if (fromField(e)) return;
    const k = promptKey(e.key, DECISIONS.length, at);
    if (!k) return;
    e.preventDefault();
    if (k.kind === "move") setAt(k.at);
    else if (k.kind === "pick") choose(k.at);
    else if (k.kind === "cancel") choose(DECISIONS.length - 1);
  };
  return (
    <KeyedCard onKeyDown={onKey} label={`Tool approval: ${p.tool ?? "tool"}`}>
    <PanelCard
      title={
        <span className="flex items-center gap-2">
          <ShieldQuestion className="size-4" /> Allow {p.tool ?? "this tool"}?
        </span>
      }
      action={expiryText(p) && <Chip tone="amber">{expiryText(p)}</Chip>}
    >
      {p.input && <pre className="mb-3 max-h-32 overflow-auto rounded-md bg-muted p-2 font-mono text-xs whitespace-pre-wrap break-all">{p.input}</pre>}
      <div className="flex flex-wrap gap-2">
        {DECISIONS.map((d, i) => (
          <Button key={d.id} size="sm" variant={d.variant} className={cn(i === at && "ring-2 ring-ring/60")} onClick={() => choose(i)}>
            <span className="text-[10px] opacity-60">{i + 1}</span> {d.label}
            {p.recommended === d.id && " (recommended)"}
          </Button>
        ))}
      </div>
      {failed && <p className="mt-2 text-xs text-danger">Not connected to the daemon; try again when the link is back.</p>}
    </PanelCard>
    </KeyedCard>
  );
}

function QuestionCard({ p }: { p: OpenPrompt }) {
  const answer = usePrompts((s) => s.answer);
  const [picked, setPicked] = useState<string[]>([]);
  const [other, setOther] = useState("");
  const [failed, setFailed] = useState(false);
  const send = (labels: string[]) => setFailed(!answer(p.promptId, labels, other));
  const toggle = (label: string) => setPicked((cur) => (cur.includes(label) ? cur.filter((l) => l !== label) : [...cur, label]));
  const canSend = picked.length > 0 || other.trim().length > 0;
  const [at, setAt] = useState(() => Math.max(0, p.options.findIndex((o) => o.label === p.recommended)));
  const onKey = (e: React.KeyboardEvent) => {
    if (fromField(e)) return;
    const k = promptKey(e.key, p.options.length, at, p.multiSelect);
    if (!k || k.kind === "cancel") return;
    e.preventDefault();
    if (k.kind === "move") setAt(k.at);
    else if (k.kind === "toggle") (setAt(k.at), toggle(p.options[k.at].label));
    else if (k.kind === "pick") send([p.options[k.at].label]);
    else if (k.kind === "submit" && canSend) send(picked);
  };
  return (
    <KeyedCard onKeyDown={onKey} label="Agent question">
    <PanelCard
      title={
        <span className="flex items-center gap-2">
          <MessageCircleQuestion className="size-4" /> The agent asks
        </span>
      }
      action={expiryText(p) && <Chip tone="amber">{expiryText(p)}</Chip>}
    >
      <p className="mb-3 text-sm whitespace-pre-wrap">{p.question}</p>
      <div className="flex flex-wrap gap-2">
        {p.options.map((o, i) => {
          const on = picked.includes(o.label);
          return (
            <Button
              key={o.label}
              size="sm"
              variant={on ? "default" : "outline"}
              className={cn(i === at && "ring-2 ring-ring/60")}
              title={o.description || undefined}
              onClick={() => (p.multiSelect ? toggle(o.label) : send([o.label]))}
            >
              {i < 9 && <span className="text-[10px] opacity-60">{i + 1}</span>} {o.label}
              {p.recommended === o.label && !o.label.toLowerCase().includes("recommended") && " (recommended)"}
            </Button>
          );
        })}
      </div>
      {(p.allowOther || p.multiSelect) && (
        <form
          className="mt-3 flex gap-2"
          onSubmit={(e) => {
            e.preventDefault();
            if (canSend) send(picked);
          }}
        >
          {p.allowOther && <Input value={other} onChange={(e) => setOther(e.target.value)} placeholder="Your own answer" className="flex-1" />}
          <Button type="submit" size="sm" disabled={!canSend}>
            Send
          </Button>
        </form>
      )}
      {failed && <p className="mt-2 text-xs text-danger">Not connected to the daemon; try again when the link is back.</p>}
    </PanelCard>
    </KeyedCard>
  );
}

/** A key from a text field (the answer box) is typing, not a card key. */
const fromField = (e: React.KeyboardEvent) => {
  const el = e.target as HTMLElement;
  return el.tagName === "INPUT" || el.tagName === "TEXTAREA" || el.isContentEditable;
};

/** The focusable frame a prompt card takes keys through. */
function KeyedCard({ onKeyDown, label, children }: { onKeyDown: (e: React.KeyboardEvent) => void; label: string; children: React.ReactNode }) {
  return (
    <div tabIndex={0} role="group" aria-label={`${label} (keys: arrows, 1-9, Enter${label.startsWith("Tool") ? ", Esc denies" : ""})`} onKeyDown={onKeyDown} className="rounded-lg outline-none focus-visible:ring-2 focus-visible:ring-ring/60">
      {children}
    </div>
  );
}

export function PromptCards({ sessionId }: { sessionId: string }) {
  const open = usePrompts((s) => s.open);
  const list = useMemo(() => promptsOf(open, sessionId), [open, sessionId]);
  if (list.length === 0) return null;
  return (
    <div className="flex max-h-[45%] flex-col gap-2 overflow-auto px-4 pt-2">
      {list.map((p) => (p.kind === "tool_approval" ? <ApprovalCard key={p.promptId} p={p} /> : <QuestionCard key={p.promptId} p={p} />))}
    </div>
  );
}
