// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Notes: the project's memory. Notes grouped by folder (search and a scope filter), the decision timeline (superseded
// decisions struck through) and the issues list with each issue's attempts and fix. Viewing, editing and adding go
// through the one modal.
import { useEffect, useMemo, useState } from "react";
import { Bug, FileText, Folder, Gavel, Plus, RefreshCw, Search, Trash2 } from "lucide-react";
import { decisionTimeline, notesByFolder, type Decision, type Issue, type KnowledgeKind, type MemoryScope } from "@infantry/client";
import { AppDialog, Button, Chip, EmptyState, Input, Select, SelectContent, SelectItem, SelectTrigger, SelectValue, Textarea, cn } from "@infantry/ui";
import { TabPage } from "../tabs/tab-page";
import { RefusalBanner, Segmented, Field, ago } from "./parts";
import { useKnowledge, useKnowledgeProject } from "./use-knowledge";

const SCOPES: { id: MemoryScope; label: string }[] = [
  { id: "project", label: "Project" },
  { id: "global", label: "Global" },
  { id: "role", label: "Role" },
  { id: "session", label: "Session" },
];

const KINDS: { id: KnowledgeKind; label: string }[] = [
  { id: "notes", label: "Notes" },
  { id: "decisions", label: "Decisions" },
  { id: "issues", label: "Issues" },
];

const NONE = "__none__";

type Modal =
  | { kind: "note"; name: string; folder: string; description: string; body: string; isNew: boolean; editing: boolean }
  | { kind: "decision"; summary: string; body: string; supersedes: string }
  | { kind: "issue"; summary: string; location: string; ticketId: string };

export function NotesTab() {
  const projectId = useKnowledgeProject();
  const kind = useKnowledge((s) => s.kind);
  const refusal = useKnowledge((s) => s.refusal);
  const { setKind, refresh, clearRefusal } = useKnowledge((s) => s);
  const [modal, setModal] = useState<Modal | null>(null);

  const add = (): void => {
    if (kind === "notes") setModal({ kind: "note", name: "", folder: "", description: "", body: "", isNew: true, editing: true });
    else if (kind === "decisions") setModal({ kind: "decision", summary: "", body: "", supersedes: NONE });
    else setModal({ kind: "issue", summary: "", location: "", ticketId: "" });
  };

  return (
    <TabPage
      title="Notes"
      description="Project memory: notes, decisions and known issues"
      actions={
        <div className="flex gap-2">
          <Button variant="ghost" size="sm" onClick={refresh} aria-label="Refresh">
            <RefreshCw />
          </Button>
          <Button variant="outline" size="sm" onClick={add} disabled={!projectId}>
            <Plus /> {kind === "notes" ? "New note" : kind === "decisions" ? "Add decision" : "Open issue"}
          </Button>
        </div>
      }
    >
      <RefusalBanner text={refusal} onDismiss={clearRefusal} />
      {!projectId ? (
        <EmptyState icon={<FileText className="size-6" />} title="No project selected" hint="Pick a project to read its memory." />
      ) : (
        <div className="space-y-3">
          <Segmented value={kind} options={KINDS} onChange={setKind} className="max-w-sm" />
          {kind === "notes" && <NotesView onOpen={(m) => setModal(m)} />}
          {kind === "decisions" && <DecisionsView />}
          {kind === "issues" && <IssuesView />}
        </div>
      )}
      <KnowledgeDialog modal={modal} setModal={setModal} />
    </TabPage>
  );
}

function NotesView({ onOpen }: { onOpen: (m: Modal) => void }) {
  const notes = useKnowledge((s) => s.notes);
  const hits = useKnowledge((s) => s.hits);
  const scope = useKnowledge((s) => s.scope);
  const query = useKnowledge((s) => s.query);
  const { setScope, search, openNote } = useKnowledge((s) => s);
  const [text, setText] = useState(query);

  useEffect(() => {
    const id = setTimeout(() => search(text), 250);
    return () => clearTimeout(id);
  }, [text, search]);

  const groups = useMemo(() => notesByFolder(notes), [notes]);
  const open = (name: string): void => {
    const n = notes.find((x) => x.name === name);
    openNote(name);
    onOpen({ kind: "note", name, folder: n?.folder ?? "", description: n?.description ?? "", body: n?.body ?? "", isNew: false, editing: false });
  };

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-2">
        <label className="relative min-w-[220px] flex-1">
          <Search className="absolute top-2 left-2 size-3.5 text-muted-foreground" />
          <Input value={text} onChange={(e) => setText(e.target.value)} placeholder="Search notes" className="h-8 pl-7 text-xs" />
        </label>
        <Segmented value={scope} options={SCOPES} onChange={setScope} />
      </div>
      {hits ? (
        hits.length === 0 ? (
          <EmptyState icon={<Search className="size-6" />} title="No matches" />
        ) : (
          <ul className="space-y-1.5">
            {hits.map((h) => (
              <li key={h.name}>
                <button type="button" onClick={() => open(h.name)} className="w-full rounded-lg border bg-card p-2.5 text-left hover:border-info/50">
                  <div className="text-[13px] font-medium">{h.name}</div>
                  <div className="truncate text-xs text-muted-foreground">{h.description}</div>
                  {h.snippet && <div className="mt-1 line-clamp-2 font-mono text-[11px] text-muted-foreground">{h.snippet}</div>}
                </button>
              </li>
            ))}
          </ul>
        )
      ) : notes.length === 0 ? (
        <EmptyState icon={<FileText className="size-6" />} title="No notes in this scope" hint="Agents and you save what the next session should know." />
      ) : (
        groups.map((g) => (
          <section key={g.folder || "root"} className="space-y-1.5">
            {g.folder && (
              <h3 className="flex items-center gap-1.5 text-xs font-medium text-muted-foreground">
                <Folder className="size-3.5" /> {g.folder}
              </h3>
            )}
            <div className="grid grid-cols-[repeat(auto-fill,minmax(240px,1fr))] gap-2">
              {g.notes.map((n) => (
                <button key={n.name} type="button" onClick={() => open(n.name)} className="rounded-lg border bg-card p-2.5 text-left transition-colors hover:border-info/50">
                  <div className="flex items-center gap-2">
                    <span className="min-w-0 flex-1 truncate text-[13px] font-medium">{n.name}</span>
                    <span className="text-[11px] text-muted-foreground">{ago(n.updatedAt)}</span>
                  </div>
                  <div className="mt-0.5 line-clamp-2 text-xs text-muted-foreground">{n.description}</div>
                </button>
              ))}
            </div>
          </section>
        ))
      )}
    </div>
  );
}

function DecisionsView() {
  const decisions = useKnowledge((s) => s.decisions);
  const timeline = useMemo(() => decisionTimeline(decisions), [decisions]);
  if (timeline.length === 0) return <EmptyState icon={<Gavel className="size-6" />} title="No decisions yet" hint="Record a decision so every agent follows it." />;
  return (
    <ol className="relative space-y-2 border-l pl-4">
      {timeline.map((d) => (
        <DecisionRow key={d.ref} d={d} />
      ))}
    </ol>
  );
}

function DecisionRow({ d }: { d: Decision }) {
  const superseded = !!d.supersededBy;
  return (
    <li className="relative">
      <span className={cn("absolute top-3 -left-[21px] size-2.5 rounded-full border-2 border-background", superseded ? "bg-muted-foreground" : "bg-info")} />
      <article className={cn("rounded-lg border bg-card p-2.5", superseded && "opacity-70")}>
        <div className="flex flex-wrap items-center gap-2">
          <Chip mono>{d.ref}</Chip>
          <span className={cn("min-w-0 flex-1 text-[13px] font-medium", superseded && "line-through")}>{d.summary}</span>
          {superseded && <Chip tone="amber">superseded by {d.supersededBy}</Chip>}
          {d.supersedes && <Chip tone="blue">supersedes {d.supersedes}</Chip>}
          <span className="text-[11px] text-muted-foreground">{ago(d.createdAt)}</span>
        </div>
        {d.body && <p className={cn("mt-1 text-xs whitespace-pre-wrap text-muted-foreground", superseded && "line-through")}>{d.body}</p>}
        {(d.authorRole || d.authorAgentId) && <p className="mt-1 text-[11px] text-muted-foreground">by {d.authorAgentId ?? d.authorRole}</p>}
      </article>
    </li>
  );
}

function IssuesView() {
  const issues = useKnowledge((s) => s.issues);
  const [filter, setFilter] = useState<"open" | "fixed" | "all">("open");
  const list = useMemo(() => [...issues].filter((i) => filter === "all" || i.status === filter).sort((a, b) => b.number - a.number), [issues, filter]);
  return (
    <div className="space-y-3">
      <Segmented
        value={filter}
        options={[
          { id: "open", label: `Open (${issues.filter((i) => i.status === "open").length})` },
          { id: "fixed", label: "Fixed" },
          { id: "all", label: "All" },
        ]}
        onChange={setFilter}
        className="max-w-xs"
      />
      {list.length === 0 ? (
        <EmptyState icon={<Bug className="size-6" />} title="No issues here" />
      ) : (
        <ul className="space-y-2">
          {list.map((i) => (
            <IssueRow key={i.ref} i={i} />
          ))}
        </ul>
      )}
    </div>
  );
}

function IssueRow({ i }: { i: Issue }) {
  const [open, setOpen] = useState(false);
  const fixIssue = useKnowledge((s) => s.fixIssue);
  return (
    <li className={cn("rounded-lg border bg-card", i.status === "open" && "border-l-4 border-l-warn")}>
      <button type="button" onClick={() => setOpen(!open)} className="flex w-full flex-wrap items-center gap-2 p-2.5 text-left">
        <Chip mono>{i.ref}</Chip>
        <span className="min-w-0 flex-1 text-[13px] font-medium">{i.summary}</span>
        <Chip tone={i.status === "open" ? "amber" : "green"}>{i.status}</Chip>
        {i.ticketId && <Chip mono>{i.ticketId}</Chip>}
        <span className="font-mono text-[11px] text-muted-foreground">{i.location}</span>
      </button>
      {open && (
        <div className="space-y-2 border-t p-2.5">
          {i.events.length === 0 ? (
            <p className="text-xs text-muted-foreground">No attempts recorded.</p>
          ) : (
            <ol className="space-y-1.5">
              {i.events.map((e) => (
                <li key={e.seq} className="rounded-md border bg-raised p-2 text-xs">
                  <div className="flex items-center gap-2">
                    <Chip tone={e.kind === "fix" ? "green" : "neutral"}>{e.kind}</Chip>
                    <span className="flex-1">{e.summary}</span>
                    <span className="text-[11px] text-muted-foreground">{ago(e.createdAt)}</span>
                  </div>
                  {e.outcome && <p className="mt-1 text-muted-foreground">{e.outcome}</p>}
                </li>
              ))}
            </ol>
          )}
          {i.status === "open" && (
            <Button variant="outline" size="xs" onClick={() => fixIssue(i.ref, "Marked fixed by the owner")}>
              Mark fixed
            </Button>
          )}
        </div>
      )}
    </li>
  );
}

function KnowledgeDialog({ modal, setModal }: { modal: Modal | null; setModal: (m: Modal | null) => void }) {
  const openNote = useKnowledge((s) => s.open);
  const decisions = useKnowledge((s) => s.decisions);
  const { saveNote, deleteNote, addDecision, openIssue, closeNote } = useKnowledge((s) => s);
  const close = (): void => {
    closeNote();
    setModal(null);
  };

  // The full note body arrives with `memory_note`; fill the viewer when it does (unless the owner is editing).
  useEffect(() => {
    if (modal?.kind === "note" && !modal.isNew && !modal.editing && openNote && openNote.name === modal.name && openNote.body !== modal.body) {
      setModal({ ...modal, folder: openNote.folder, description: openNote.description, body: openNote.body });
    }
  }, [openNote, modal, setModal]);

  let title = "";
  let footer = null;
  let body = null;
  if (modal?.kind === "note") {
    const m = modal;
    title = m.isNew ? "New note" : m.name;
    footer = m.editing ? (
      <Button
        size="sm"
        disabled={!m.name.trim() || !m.body.trim()}
        onClick={() => {
          saveNote({ name: m.name.trim(), description: m.description.trim(), body: m.body, ...(m.folder.trim() ? { folder: m.folder.trim() } : {}) });
          setModal({ ...m, isNew: false, editing: false });
        }}
      >
        Save
      </Button>
    ) : (
      <div className="flex w-full justify-between gap-2">
        <Button
          variant="destructive"
          size="sm"
          onClick={() => {
            deleteNote(m.name);
            close();
          }}
        >
          <Trash2 /> Delete
        </Button>
        <Button variant="outline" size="sm" onClick={() => setModal({ ...m, editing: true })}>
          Edit
        </Button>
      </div>
    );
    body = m.editing ? (
      <div className="space-y-3">
        <div className="grid gap-3 sm:grid-cols-2">
          <Field id="note-name" label="Name">
            <Input id="note-name" value={m.name} disabled={!m.isNew} onChange={(e) => setModal({ ...m, name: e.target.value })} />
          </Field>
          <Field id="note-folder" label="Folder">
            <Input id="note-folder" value={m.folder} onChange={(e) => setModal({ ...m, folder: e.target.value })} placeholder="(root)" />
          </Field>
        </div>
        <Field id="note-desc" label="Description" hint="One line: agents see it in the memory index.">
          <Input id="note-desc" value={m.description} onChange={(e) => setModal({ ...m, description: e.target.value })} />
        </Field>
        <Field id="note-body" label="Body">
          <Textarea id="note-body" rows={12} className="font-mono text-xs" value={m.body} onChange={(e) => setModal({ ...m, body: e.target.value })} />
        </Field>
      </div>
    ) : (
      <div className="space-y-2">
        <div className="flex flex-wrap gap-1.5">
          {m.folder && <Chip>{m.folder}</Chip>}
          {openNote?.scope && <Chip tone="blue">{openNote.scope}</Chip>}
          {openNote?.updatedAt && <Chip>updated {ago(openNote.updatedAt)}</Chip>}
        </div>
        {m.description && <p className="text-xs text-muted-foreground">{m.description}</p>}
        <pre className="rounded-lg border bg-raised p-3 font-mono text-xs whitespace-pre-wrap">{m.body || "Loading..."}</pre>
      </div>
    );
  } else if (modal?.kind === "decision") {
    const m = modal;
    title = "Add decision";
    footer = (
      <Button
        size="sm"
        disabled={!m.summary.trim()}
        onClick={() => {
          addDecision(m.summary.trim(), m.body.trim() || undefined, m.supersedes === NONE ? undefined : m.supersedes);
          setModal(null);
        }}
      >
        <Gavel /> Record
      </Button>
    );
    const live = decisionTimeline(decisions).filter((d) => !d.supersededBy);
    body = (
      <div className="space-y-3">
        <Field id="dec-summary" label="Decision">
          <Input id="dec-summary" value={m.summary} onChange={(e) => setModal({ ...m, summary: e.target.value })} />
        </Field>
        <Field id="dec-body" label="Why (optional)">
          <Textarea id="dec-body" rows={5} value={m.body} onChange={(e) => setModal({ ...m, body: e.target.value })} />
        </Field>
        <Field id="dec-supersedes" label="Supersedes (optional)">
          <Select value={m.supersedes} onValueChange={(v) => setModal({ ...m, supersedes: v })}>
            <SelectTrigger id="dec-supersedes" className="w-full">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value={NONE}>Nothing</SelectItem>
              {live.map((d) => (
                <SelectItem key={d.ref} value={d.ref}>
                  {d.ref}: {d.summary}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </Field>
      </div>
    );
  } else if (modal?.kind === "issue") {
    const m = modal;
    title = "Open issue";
    footer = (
      <Button
        size="sm"
        disabled={!m.summary.trim() || !m.location.trim()}
        onClick={() => {
          openIssue(m.summary.trim(), m.location.trim(), m.ticketId.trim() || undefined);
          setModal(null);
        }}
      >
        <Bug /> Open
      </Button>
    );
    body = (
      <div className="space-y-3">
        <Field id="iss-summary" label="Summary">
          <Input id="iss-summary" value={m.summary} onChange={(e) => setModal({ ...m, summary: e.target.value })} />
        </Field>
        <Field id="iss-location" label="Location" hint="A file or folder path; agents are warned before they touch it.">
          <Input id="iss-location" className="font-mono text-xs" value={m.location} onChange={(e) => setModal({ ...m, location: e.target.value })} />
        </Field>
        <Field id="iss-ticket" label="Ticket (optional)">
          <Input id="iss-ticket" value={m.ticketId} onChange={(e) => setModal({ ...m, ticketId: e.target.value })} />
        </Field>
      </div>
    );
  }

  return (
    <AppDialog open={modal !== null} onOpenChange={(o) => !o && close()} title={title} wide={modal?.kind === "note"} footer={footer}>
      {body}
    </AppDialog>
  );
}
