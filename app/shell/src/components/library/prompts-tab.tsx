// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Prompts: the saved prompt library, filtered by tag; copy one to the clipboard, edit or delete it through the one modal.
import { useMemo, useState } from "react";
import { Check, Copy, MessageSquare, Plus, RefreshCw, Search, Trash2 } from "lucide-react";
import { sortedByName, tagsOf, type LibraryPrompt } from "@infantry/client";
import { AppDialog, Button, Chip, EmptyState, Input, Textarea, cn } from "@infantry/ui";
import { TabPage } from "../tabs/tab-page";
import { Field, RefusalBanner, splitTags } from "../knowledge/parts";
import { libraryStore, useLibrary, useLibraryLoaded } from "../knowledge/use-knowledge";

interface Draft {
  isNew: boolean;
  id: string;
  name: string;
  body: string;
  tags: string;
}

export function PromptsTab() {
  useLibraryLoaded();
  const prompts = useLibrary((s) => s.prompts);
  const refusal = useLibrary((s) => s.refusal);
  const { refresh, clearRefusal } = useLibrary((s) => s);
  const [tag, setTag] = useState<string | null>(null);
  const [query, setQuery] = useState("");
  const [draft, setDraft] = useState<Draft | null>(null);
  const [copied, setCopied] = useState<string | null>(null);

  const tags = useMemo(() => tagsOf(prompts), [prompts]);
  const list = useMemo(() => {
    const q = query.trim().toLowerCase();
    return sortedByName(prompts).filter((p) => (!tag || p.tags.includes(tag)) && (!q || p.name.toLowerCase().includes(q) || p.body.toLowerCase().includes(q)));
  }, [prompts, tag, query]);

  const copy = (p: LibraryPrompt): void => {
    void navigator.clipboard?.writeText(p.body).then(() => {
      setCopied(p.id);
      setTimeout(() => setCopied((c) => (c === p.id ? null : c)), 1500);
    });
  };
  const edit = (p: LibraryPrompt | null): void => setDraft({ isNew: !p, id: p?.id ?? "", name: p?.name ?? "", body: p?.body ?? "", tags: p?.tags.join(", ") ?? "" });

  return (
    <TabPage
      title="Prompts"
      description="Saved prompts to reuse in any session"
      actions={
        <div className="flex gap-2">
          <Button variant="ghost" size="sm" onClick={refresh} aria-label="Refresh">
            <RefreshCw />
          </Button>
          <Button variant="outline" size="sm" onClick={() => edit(null)}>
            <Plus /> New prompt
          </Button>
        </div>
      }
    >
      <RefusalBanner text={refusal} onDismiss={clearRefusal} />
      <div className="mb-3 flex flex-wrap items-center gap-2">
        <label className="relative w-64">
          <Search className="absolute top-2 left-2 size-3.5 text-muted-foreground" />
          <Input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Search prompts" className="h-8 pl-7 text-xs" />
        </label>
        <div className="flex flex-wrap gap-1">
          {[null, ...tags].map((t) => (
            <button
              key={t ?? "*"}
              type="button"
              onClick={() => setTag(t)}
              className={cn("h-6 rounded-md border px-2 text-xs", tag === t ? "border-info/60 bg-info/10 text-info" : "text-muted-foreground hover:bg-raised/60")}
            >
              {t ?? "All"}
            </button>
          ))}
        </div>
      </div>
      {list.length === 0 ? (
        <EmptyState icon={<MessageSquare className="size-6" />} title="No prompts here" />
      ) : (
        <div className="grid grid-cols-[repeat(auto-fill,minmax(280px,1fr))] gap-3">
          {list.map((p) => (
            <article key={p.id} className="flex flex-col rounded-lg border bg-card p-3">
              <div className="flex items-center gap-2">
                <button type="button" onClick={() => edit(p)} className="min-w-0 flex-1 truncate text-left font-medium hover:underline">
                  {p.name}
                </button>
                <Button variant="ghost" size="icon-xs" aria-label="Copy prompt" onClick={() => copy(p)}>
                  {copied === p.id ? <Check /> : <Copy />}
                </Button>
              </div>
              <p className="mt-1 line-clamp-3 flex-1 font-mono text-[11px] whitespace-pre-wrap text-muted-foreground">{p.body}</p>
              {p.tags.length > 0 && (
                <div className="mt-1.5 flex flex-wrap gap-1">
                  {p.tags.map((t) => (
                    <Chip key={t} className="h-5 text-[10px]">
                      {t}
                    </Chip>
                  ))}
                </div>
              )}
            </article>
          ))}
        </div>
      )}
      <AppDialog
        open={draft !== null}
        onOpenChange={(o) => !o && setDraft(null)}
        title={draft?.isNew ? "New prompt" : (draft?.name ?? "")}
        wide
        footer={
          <div className="flex w-full justify-between gap-2">
            {draft && !draft.isNew ? (
              <Button
                variant="destructive"
                size="sm"
                onClick={() => {
                  libraryStore().getState().deletePrompt(draft.id);
                  setDraft(null);
                }}
              >
                <Trash2 /> Delete
              </Button>
            ) : (
              <span />
            )}
            <Button
              size="sm"
              disabled={!draft?.name.trim() || !draft?.body.trim()}
              onClick={() => {
                if (draft) libraryStore().getState().savePrompt({ name: draft.name.trim(), body: draft.body, tags: splitTags(draft.tags) });
                setDraft(null);
              }}
            >
              Save
            </Button>
          </div>
        }
      >
        {draft && (
          <div className="space-y-3">
            <Field id="prompt-name" label="Name">
              <Input id="prompt-name" value={draft.name} disabled={!draft.isNew} onChange={(e) => setDraft({ ...draft, name: e.target.value })} />
            </Field>
            <Field id="prompt-tags" label="Tags" hint="Comma separated.">
              <Input id="prompt-tags" value={draft.tags} onChange={(e) => setDraft({ ...draft, tags: e.target.value })} />
            </Field>
            <Field id="prompt-body" label="Prompt">
              <Textarea id="prompt-body" rows={10} className="font-mono text-xs" value={draft.body} onChange={(e) => setDraft({ ...draft, body: e.target.value })} />
            </Field>
          </div>
        )}
      </AppDialog>
    </TabPage>
  );
}
