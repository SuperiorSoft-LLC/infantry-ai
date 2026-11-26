// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Mail: every thread the owner can see, as cards on the left; the open thread as a conversation on the right, the
// owner's own messages on the right side. Compose and reply go through the one modal. MAIL-OWNER-1: the Inbox opens
// with the agents' decisions for the owner (answer buttons; answered ones show "answered: <choice>"), and "Mark all
// read" / "Archive read" clear old mail by the owner's hand only.
import { useEffect, useMemo, useState } from "react";
import { Archive, CheckCheck, Inbox, Mail, PenSquare, RefreshCw, Reply, Search, Send } from "lucide-react";
import { OWNER, archivableIds, inboxDecisions, openQuestions, threadsOf, unreadCount, type MailFolder, type MailMessage, type MailThread, type OwnerQuestion } from "@infantry/client";
import { AppDialog, Button, Chip, EmptyState, Input, Label, SectionLabel, Textarea, cn } from "@infantry/ui";
import { TabPage } from "../tabs/tab-page";
import { DecisionCard, OwnAnswerDialog } from "./decision-card";
import { useAttention, useMail } from "./use-mail";

const FOLDERS: { id: MailFolder; label: string }[] = [
  { id: "inbox", label: "Inbox" },
  { id: "sent", label: "Sent" },
  { id: "all", label: "All mail" },
];

function ago(at: number): string {
  const s = Math.max(0, Math.round((Date.now() - at) / 1000));
  if (s < 60) return "now";
  if (s < 3600) return `${Math.round(s / 60)}m`;
  if (s < 86_400) return `${Math.round(s / 3600)}h`;
  return `${Math.round(s / 86_400)}d`;
}

function stateTone(m: MailMessage): "neutral" | "amber" | "green" | "red" | "blue" {
  if (m.state === "FAILED") return "red";
  if (m.state === "ANSWERED") return "green";
  if (m.needsInput) return "amber";
  if (m.state === "QUEUED" || m.state === "HELD") return "blue";
  return "neutral";
}

interface Draft {
  to: string;
  subject: string;
  body: string;
  replyTo?: string;
}

export function MailTab() {
  const messages = useMail((s) => s.messages);
  const folder = useMail((s) => s.folder);
  const query = useMail((s) => s.query);
  const refusal = useMail((s) => s.refusal);
  const { ensureLoaded, refresh, setFolder, setQuery, markRead, markAllRead, archiveRead, send, clearRefusal } = useMail((s) => s);
  const questions = useAttention((s) => s.questions);
  const loadAttention = useAttention((s) => s.ensureLoaded);
  const [openId, setOpenId] = useState<string | null>(null);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [other, setOther] = useState<OwnerQuestion | null>(null);

  useEffect(() => ensureLoaded(), [ensureLoaded]);
  useEffect(() => loadAttention(), [loadAttention]);
  const threads = useMemo(() => threadsOf(messages, folder, query), [messages, folder, query]);
  const open = threads.find((t) => t.threadId === openId) ?? threads[0] ?? null;
  const q = query.trim().toLowerCase();
  const decisions = useMemo(
    () => (folder === "inbox" ? inboxDecisions(questions).filter((d) => !q || `${d.question}
${d.agentId ?? ""}
${d.role ?? ""}`.toLowerCase().includes(q)) : []),
    [questions, folder, q],
  );
  const openDecisions = openQuestions(questions).length;
  const unread = unreadCount(messages) + openDecisions;
  const unreadMail = unread - openDecisions;
  const archivable = archivableIds(messages).length;

  useEffect(() => {
    // Every copy in the thread, a folded duplicate included.
    if (open) Object.values(messages).forEach((m) => m.threadId === open.threadId && markRead(m.id));
  }, [open, messages, markRead]);

  const replyTo = (t: MailThread): void => {
    const other = t.last.from === OWNER ? t.last.to : t.last.from;
    setDraft({ to: other, subject: t.subject.startsWith("Re:") ? t.subject : `Re: ${t.subject}`, body: "", replyTo: t.last.id });
  };

  return (
    <TabPage
      title="Mail"
      description={unread > 0 ? `${unread} unread for you` : "Agent and owner mail across the pod"}
      actions={
        <div className="flex gap-2">
          <Button variant="ghost" size="sm" onClick={refresh} aria-label="Refresh">
            <RefreshCw />
          </Button>
          <Button variant="outline" size="sm" onClick={() => setDraft({ to: "", subject: "", body: "" })}>
            <PenSquare /> Compose
          </Button>
        </div>
      }
    >
      {refusal && (
        <div role="alert" className="mb-3 flex items-center justify-between rounded-lg border border-danger/60 bg-danger/10 px-3 py-2 text-xs text-danger">
          <span>{refusal}</span>
          <Button variant="ghost" size="xs" onClick={clearRefusal}>
            Dismiss
          </Button>
        </div>
      )}
      <div className="grid min-h-0 gap-3 md:grid-cols-[320px_1fr]">
        <section className="flex min-h-0 flex-col gap-2">
          <div className="flex gap-1 rounded-lg border bg-card p-1" role="tablist" aria-label="Mail folders">
            {FOLDERS.map((f) => (
              <button
                key={f.id}
                type="button"
                role="tab"
                aria-selected={folder === f.id}
                onClick={() => setFolder(f.id)}
                className={cn("flex-1 rounded-md px-2 py-1 text-xs", folder === f.id ? "bg-raised font-medium" : "text-muted-foreground hover:bg-raised/60")}
              >
                {f.label}
              </button>
            ))}
          </div>
          <label className="relative">
            <Search className="absolute top-2 left-2 size-3.5 text-muted-foreground" />
            <Input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Search mail" className="h-8 pl-7 text-xs" />
          </label>
          {folder === "inbox" && (
            <div className="flex gap-1.5">
              <Button variant="outline" size="xs" className="flex-1" disabled={unreadMail === 0} onClick={markAllRead} title="Mark every unread mail to you as read">
                <CheckCheck /> Mark all read
              </Button>
              <Button variant="outline" size="xs" className="flex-1" disabled={archivable === 0} onClick={archiveRead} title="Move read mail out of the Inbox (it stays in All mail)">
                <Archive /> Archive read
              </Button>
            </div>
          )}
          {decisions.length > 0 && (
            <section aria-label="Decisions">
              <SectionLabel>Decisions{openDecisions > 0 ? ` (${openDecisions} open)` : ""}</SectionLabel>
              <ul className="mt-1.5 space-y-1.5">
                {decisions.map((d) => (
                  <DecisionCard key={d.id} q={d} onOther={setOther} />
                ))}
              </ul>
            </section>
          )}
          {threads.length === 0 ? (
            decisions.length === 0 && <EmptyState icon={<Inbox className="size-6" />} title="No mail here" hint="Agents' mail and decisions for you land in the inbox." />
          ) : (
            <ul className="space-y-1.5">
              {threads.map((t) => (
                <li key={t.threadId}>
                  <button
                    type="button"
                    onClick={() => setOpenId(t.threadId)}
                    className={cn(
                      "w-full rounded-lg border bg-card p-2.5 text-left transition-colors hover:border-info/50",
                      open?.threadId === t.threadId && "border-info/70 bg-info/5",
                      t.unread > 0 && "border-l-4 border-l-warn",
                    )}
                  >
                    <div className="flex items-center gap-2">
                      <span className={cn("min-w-0 flex-1 truncate text-[13px]", t.unread > 0 && "font-semibold")}>{t.subject}</span>
                      <span className="text-[11px] text-muted-foreground">{ago(t.last.sentAt)}</span>
                    </div>
                    <div className="mt-0.5 truncate text-xs text-muted-foreground">
                      {t.last.from} → {t.last.to}: {t.last.body.split("\n")[0]}
                    </div>
                    <div className="mt-1.5 flex flex-wrap gap-1">
                      <Chip mono>{t.messages.length} msg</Chip>
                      {t.messages.some((m) => (m.folded ?? 1) > 1) && <Chip>duplicates folded</Chip>}
                      {t.unread > 0 && <Chip tone="amber">{t.unread} new</Chip>}
                      {t.last.needsInput && <Chip tone="amber">needs input</Chip>}
                    </div>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </section>
        <section className="min-h-0 rounded-lg border bg-card">
          {!open ? (
            <EmptyState icon={<Mail className="size-6" />} title="Pick a thread" />
          ) : (
            <div className="flex h-full flex-col">
              <header className="flex items-center gap-2 border-b p-3">
                <div className="min-w-0 flex-1">
                  <h3 className="truncate text-sm font-semibold">{open.subject}</h3>
                  <div className="truncate text-xs text-muted-foreground">{open.members.join(" · ")}</div>
                </div>
                <Button variant="outline" size="sm" onClick={() => replyTo(open)}>
                  <Reply /> Reply
                </Button>
              </header>
              <ol className="flex-1 space-y-3 overflow-y-auto p-3">
                {open.messages.map((m) => {
                  const mine = m.from === OWNER;
                  return (
                    <li key={m.id} className={cn("flex", mine ? "justify-end" : "justify-start")}>
                      <article className={cn("max-w-[85%] rounded-lg border p-2.5", mine ? "border-info/50 bg-info/10" : "bg-raised")}>
                        <div className="mb-1 flex items-center gap-2 text-[11px] text-muted-foreground">
                          <span className="font-medium text-foreground">{m.from}</span>
                          <span>→ {m.to}</span>
                          <span>· {ago(m.sentAt)}</span>
                          <Chip tone={stateTone(m)} className="h-5 text-[10px]">
                            {m.state.toLowerCase()}
                          </Chip>
                          {(m.folded ?? 1) > 1 && (
                            <Chip className="h-5 text-[10px]">sent ×{m.folded}, shown once</Chip>
                          )}
                        </div>
                        <p className="text-[13px] whitespace-pre-wrap">{m.body}</p>
                        {m.failureReason && <p className="mt-1 text-xs text-danger">{m.failureReason}</p>}
                      </article>
                    </li>
                  );
                })}
              </ol>
            </div>
          )}
        </section>
      </div>
      <OwnAnswerDialog question={other} onClose={() => setOther(null)} />
      <AppDialog
        open={draft !== null}
        onOpenChange={(o) => !o && setDraft(null)}
        title={draft?.replyTo ? "Reply" : "New mail"}
        description="Sent as the owner; the agent reads it on its next turn."
        footer={
          <Button
            size="sm"
            disabled={!draft?.to.trim() || !draft?.body.trim()}
            onClick={() => draft && send(draft.to, draft.subject, draft.body, draft.replyTo) && setDraft(null)}
          >
            <Send /> Send
          </Button>
        }
      >
        {draft && (
          <div className="space-y-3 p-4">
            <div className="space-y-1">
              <Label htmlFor="mail-to">To</Label>
              <Input id="mail-to" value={draft.to} onChange={(e) => setDraft({ ...draft, to: e.target.value })} placeholder="agent, role or session" />
            </div>
            <div className="space-y-1">
              <Label htmlFor="mail-subject">Subject</Label>
              <Input id="mail-subject" value={draft.subject} onChange={(e) => setDraft({ ...draft, subject: e.target.value })} />
            </div>
            <div className="space-y-1">
              <Label htmlFor="mail-body">Message</Label>
              <Textarea id="mail-body" rows={7} value={draft.body} onChange={(e) => setDraft({ ...draft, body: e.target.value })} />
            </div>
          </div>
        )}
      </AppDialog>
    </TabPage>
  );
}
