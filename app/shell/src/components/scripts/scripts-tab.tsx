// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Scripts: the managed Lua library. Cards show title, description, tags, status, runs and the hooks that use each script;
// search, filter and sort; one editor (the one modal) with the body, hooks, a Run box and the version history.
import { useEffect, useMemo, useState } from "react";
import { Download, FileCode, Plus, RefreshCw, Trash2, Upload } from "lucide-react";
import { allTags, filterScripts, type ScriptDetail, type ScriptFilter, type ScriptRow, type ScriptSort } from "@infantry/client";
import { AppDialog, Button, Chip, EmptyState, Input, Select, SelectContent, SelectItem, SelectTrigger, SelectValue, Switch, Textarea } from "@infantry/ui";
import { TabPage } from "../tabs/tab-page";
import { Field, RefusalBanner, ago, splitTags } from "../knowledge/parts";
import { scriptsStore, useScripts, useScriptsLoaded } from "./use-scripts";

const FILTERS: { id: ScriptFilter; label: string }[] = [
  { id: "all", label: "All" },
  { id: "failing", label: "Failing" },
  { id: "unused", label: "Unused" },
  { id: "hooked", label: "Used by hooks" },
  { id: "flagged", label: "Duplicates to review" },
  { id: "disabled", label: "Disabled" },
];

const SORTS: { id: ScriptSort; label: string }[] = [
  { id: "name", label: "Name" },
  { id: "used", label: "Most used" },
  { id: "recent", label: "Recently run" },
  { id: "failing", label: "Most failing" },
  { id: "unused", label: "Unused first" },
];

const STATUS_TONE = { ok: "green", failing: "red", "never-run": "neutral", disabled: "amber" } as const;

interface Draft {
  id: string | null;
  name: string;
  scope: "project" | "global";
  title: string;
  description: string;
  tags: string;
  body: string;
}

const NEW_BODY = "-- What this script does, in one line.\nreturn {}\n";

function draftOf(d: ScriptDetail | null): Draft {
  return d
    ? { id: d.id, name: d.name, scope: d.scope === "global" ? "global" : "project", title: d.title, description: d.description, tags: d.tags.join(", "), body: d.body }
    : { id: null, name: "", scope: "project", title: "", description: "", tags: "", body: NEW_BODY };
}

function ms(n: number | null): string {
  if (n == null) return "-";
  return n < 1000 ? `${n} ms` : `${(n / 1000).toFixed(1)} s`;
}

export function ScriptsTab() {
  const projectId = useScriptsLoaded();
  const rows = useScripts((s) => s.scripts);
  const loaded = useScripts((s) => s.loaded);
  const detail = useScripts((s) => s.detail);
  const refusal = useScripts((s) => s.refusal);
  const result = useScripts((s) => s.result);
  const [query, setQuery] = useState("");
  const [filter, setFilter] = useState<ScriptFilter>("all");
  const [sort, setSort] = useState<ScriptSort>("name");
  const [tag, setTag] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const act = scriptsStore().getState;

  const shown = useMemo(() => filterScripts(rows, { query, filter, sort, tag }), [rows, query, filter, sort, tag]);
  const tags = useMemo(() => allTags(rows), [rows]);
  const flagged = rows.filter((r) => r.flag).length;

  return (
    <TabPage
      title="Scripts"
      description="Lua scripts the daemon runs for you: what they do, how often they run, and which hooks trigger them"
      actions={
        <div className="flex gap-2">
          <Button variant="ghost" size="sm" onClick={() => act().refresh()} aria-label="Refresh">
            <RefreshCw />
          </Button>
          <Button variant="ghost" size="sm" title="Write the project's scripts to .infantry/scripts as files" disabled={!projectId} onClick={() => act().files("export", "project")}>
            <Download /> Export
          </Button>
          <Button variant="ghost" size="sm" title="Read script files from .infantry/scripts into the library" disabled={!projectId} onClick={() => act().files("import", "project")}>
            <Upload /> Import
          </Button>
          <Button variant="outline" size="sm" onClick={() => setCreating(true)}>
            <Plus /> New script
          </Button>
        </div>
      }
    >
      <RefusalBanner text={refusal} onDismiss={() => act().clearRefusal()} />
      {result && !detail && !creating && (
        <div className={`mb-3 flex items-start gap-2 rounded-md border px-3 py-2 text-xs ${result.ok ? "border-ok/40" : "border-danger/50"}`}>
          <pre className="min-w-0 flex-1 whitespace-pre-wrap">{result.text}</pre>
          <Button variant="ghost" size="sm" onClick={() => act().clearResult()}>
            Dismiss
          </Button>
        </div>
      )}
      <div className="mb-3 flex flex-wrap items-center gap-2">
        <Input aria-label="Search scripts" placeholder="Search title, description, tags" className="h-8 w-64" value={query} onChange={(e) => setQuery(e.target.value)} />
        <Select value={filter} onValueChange={(v) => setFilter(v as ScriptFilter)}>
          <SelectTrigger aria-label="Filter" className="h-8 w-48">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {FILTERS.map((f) => (
              <SelectItem key={f.id} value={f.id}>
                {f.label}
                {f.id === "flagged" && flagged > 0 ? ` (${flagged})` : ""}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        <Select value={sort} onValueChange={(v) => setSort(v as ScriptSort)}>
          <SelectTrigger aria-label="Sort" className="h-8 w-40">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {SORTS.map((f) => (
              <SelectItem key={f.id} value={f.id}>
                {f.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        {tags.length > 0 && (
          <Select value={tag ?? "*"} onValueChange={(v) => setTag(v === "*" ? null : v)}>
            <SelectTrigger aria-label="Tag" className="h-8 w-36">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="*">Any tag</SelectItem>
              {tags.map((t) => (
                <SelectItem key={t} value={t}>
                  {t}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        )}
        <span className="text-xs text-muted-foreground">
          {shown.length} of {rows.length}
        </span>
      </div>
      {!loaded ? (
        <EmptyState icon={<FileCode className="size-6" />} title="Loading scripts" />
      ) : shown.length === 0 ? (
        <EmptyState icon={<FileCode className="size-6" />} title={rows.length === 0 ? "No scripts yet" : "No script matches"} hint={rows.length === 0 ? "Agents save scripts with a title and a description; they appear here." : undefined} />
      ) : (
        <div className="grid grid-cols-[repeat(auto-fill,minmax(300px,1fr))] gap-3">
          {shown.map((r) => (
            <ScriptCard key={r.id} row={r} onOpen={() => (r.scope === "plugin" ? undefined : act().open(r.id))} />
          ))}
        </div>
      )}
      <ScriptDialog
        key={creating ? "new" : (detail?.id ?? "none")}
        detail={creating ? null : detail}
        open={creating || !!detail}
        projectId={projectId}
        onClose={() => {
          setCreating(false);
          act().close();
        }}
      />
    </TabPage>
  );
}

function ScriptCard({ row: r, onOpen }: { row: ScriptRow; onOpen: () => void }) {
  return (
    <button type="button" onClick={onOpen} className={`rounded-lg border bg-card p-3 text-left transition-colors hover:border-info/50 ${r.enabled ? "" : "opacity-60"}`}>
      <div className="flex items-center gap-2">
        <span className="min-w-0 flex-1 truncate font-medium">{r.title}</span>
        <Chip tone={STATUS_TONE[r.status]} className="h-5 text-[10px]">
          {r.status === "never-run" ? "never run" : r.status}
        </Chip>
      </div>
      <div className="mt-0.5 flex items-center gap-1.5 text-[11px] text-muted-foreground">
        <span className="truncate font-mono">{r.name}</span>
        <Chip className="h-4 text-[10px]">{r.scope}</Chip>
      </div>
      <p className="mt-1 line-clamp-2 text-xs text-muted-foreground">{r.description}</p>
      {r.tags.length > 0 && (
        <div className="mt-1.5 flex flex-wrap gap-1">
          {r.tags.map((t) => (
            <Chip key={t} className="h-5 text-[10px]">
              {t}
            </Chip>
          ))}
        </div>
      )}
      <div className="mt-2 text-[11px] text-muted-foreground">
        {r.stats.runs} run{r.stats.runs === 1 ? "" : "s"}
        {r.stats.failures > 0 && <span className="text-danger"> ({r.stats.failures} failed)</span>}
        {r.stats.lastRunAt ? ` · last ${ago(r.stats.lastRunAt)} ago` : ""}
        {r.stats.avgMs != null ? ` · avg ${ms(r.stats.avgMs)}` : ""}
      </div>
      {r.hooks.length > 0 && (
        <div className="mt-1 truncate text-[11px] text-muted-foreground" title={r.hooks.map((h) => `${h.id}: ${h.on}`).join("\n")}>
          hooks: {r.hooks.map((h) => h.on).join(" · ")}
        </div>
      )}
      {r.flag && <div className="mt-1 text-[11px] text-warn">{r.flag}</div>}
    </button>
  );
}

function ScriptDialog({ detail, open, projectId, onClose }: { detail: ScriptDetail | null; open: boolean; projectId: string | null; onClose: () => void }) {
  const [d, setD] = useState<Draft>(() => draftOf(detail));
  const [args, setArgs] = useState("{}");
  const [on, setOn] = useState("");
  const result = useScripts((s) => s.result);
  const act = scriptsStore().getState;
  // The draft follows the stored version; an edit in flight survives a refresh that brings nothing new.
  useEffect(() => {
    if (detail) setD(draftOf(detail));
  }, [detail?.id, detail?.version]); // eslint-disable-line react-hooks/exhaustive-deps
  if (!open) return null;
  const isNew = !detail;
  const readOnly = !!detail && detail.scope === "plugin";
  const canSave = !!d.title.trim() && !!d.description.trim() && !!d.body.trim() && (!isNew || !!d.name.trim()) && (d.scope === "global" || !!projectId);
  const save = (): void => {
    act().save({ ...(d.id ? { id: d.id } : { name: d.name.trim(), scope: d.scope }), title: d.title.trim(), description: d.description.trim(), body: d.body, tags: splitTags(d.tags) });
    if (isNew) onClose();
  };
  return (
    <AppDialog
      open
      onOpenChange={(o) => !o && onClose()}
      title={isNew ? "New script" : detail.title}
      description={detail ? `${detail.name}.${detail.ext} · ${detail.scope} · version ${detail.version} · by ${detail.author || "unknown"}` : "Saved in the library; agents and hooks run it by name."}
      wide
      footer={
        <div className="flex w-full items-center justify-between gap-2">
          {detail ? (
            <div className="flex items-center gap-3">
              <Button variant="destructive" size="sm" onClick={() => (act().remove(detail.id), onClose())}>
                <Trash2 /> Delete
              </Button>
              <label className="flex items-center gap-2 text-xs">
                <Switch checked={detail.enabled} onCheckedChange={(v) => act().setEnabled(detail.id, v)} /> Enabled
              </label>
            </div>
          ) : (
            <span />
          )}
          <Button size="sm" disabled={!canSave || readOnly} onClick={save}>
            Save
          </Button>
        </div>
      }
    >
      <div className="space-y-3">
        {detail?.flag && <div className="rounded-md border border-warn/50 px-3 py-2 text-xs text-warn">{detail.flag}</div>}
        {isNew && (
          <div className="grid gap-3 sm:grid-cols-[1fr_auto]">
            <Field id="script-name" label="Name" hint="Letters, digits, - _ . ; hooks and run use it.">
              <Input id="script-name" value={d.name} onChange={(e) => setD({ ...d, name: e.target.value })} />
            </Field>
            <Field id="script-scope" label="Scope">
              <Select value={d.scope} onValueChange={(v) => setD({ ...d, scope: v as "project" | "global" })}>
                <SelectTrigger id="script-scope" className="h-9 w-32">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="project">Project</SelectItem>
                  <SelectItem value="global">Global</SelectItem>
                </SelectContent>
              </Select>
            </Field>
          </div>
        )}
        <Field id="script-title" label="Title">
          <Input id="script-title" value={d.title} onChange={(e) => setD({ ...d, title: e.target.value })} />
        </Field>
        <Field id="script-desc" label="Description" hint="What it does and when to use it.">
          <Textarea id="script-desc" rows={2} value={d.description} onChange={(e) => setD({ ...d, description: e.target.value })} />
        </Field>
        <Field id="script-tags" label="Tags" hint="Comma separated.">
          <Input id="script-tags" value={d.tags} onChange={(e) => setD({ ...d, tags: e.target.value })} />
        </Field>
        <Field id="script-body" label="Source">
          <Textarea id="script-body" rows={14} spellCheck={false} className="font-mono text-xs" value={d.body} onChange={(e) => setD({ ...d, body: e.target.value })} />
        </Field>
        {detail && (
          <>
            <section className="space-y-2 rounded-md border p-3">
              <h3 className="text-xs font-medium">Hooks that run this script</h3>
              {detail.hooks.length === 0 && <p className="text-xs text-muted-foreground">None. Add one below, e.g. “lane enter done”, “ticket.commented” or “every 300s”.</p>}
              {detail.hooks.map((h) => (
                <div key={h.id} className="flex items-center gap-2 text-xs">
                  <Switch checked={h.enabled} onCheckedChange={(v) => act().setHookEnabled(h.id, v, h.scope === "global")} aria-label={`Enable hook ${h.id}`} />
                  <span className="min-w-0 flex-1 truncate">
                    <span className="font-mono">{h.on}</span> <span className="text-muted-foreground">({h.id}{h.scope === "global" ? ", every project" : ""})</span>
                  </span>
                  {h.scope !== "plugin" && (
                    <Button variant="ghost" size="sm" onClick={() => act().removeHook(h.id, h.scope === "global")} aria-label={`Remove hook ${h.id}`}>
                      <Trash2 />
                    </Button>
                  )}
                </div>
              ))}
              <div className="flex gap-2">
                <Input aria-label="New hook event" placeholder="lane enter done" className="h-8" value={on} onChange={(e) => setOn(e.target.value)} />
                <Button variant="outline" size="sm" disabled={!on.trim()} onClick={() => (act().addHook(detail.id, on.trim()), setOn(""))}>
                  Add hook
                </Button>
              </div>
            </section>
            <section className="space-y-2 rounded-md border p-3">
              <h3 className="text-xs font-medium">Run</h3>
              <Textarea aria-label="Run arguments" rows={2} className="font-mono text-xs" placeholder='{"key": "value"}' value={args} onChange={(e) => setArgs(e.target.value)} />
              <div className="flex gap-2">
                <Button size="sm" disabled={!detail.enabled} onClick={() => act().run(detail.name, args, false)}>
                  Run
                </Button>
                <Button variant="outline" size="sm" disabled={!detail.enabled} onClick={() => act().run(detail.name, args, true)}>
                  Dry run
                </Button>
              </div>
              {result && <pre className={`max-h-56 overflow-auto whitespace-pre-wrap rounded-md border p-2 text-xs ${result.ok ? "" : "border-danger/50"}`}>{result.text}</pre>}
              <p className="text-[11px] text-muted-foreground">
                {detail.stats.runs} runs, {detail.stats.failures} failed, avg {ms(detail.stats.avgMs)}
                {detail.stats.lastError ? ` · last error: ${detail.stats.lastError}` : ""}
              </p>
            </section>
            <section className="space-y-1 rounded-md border p-3">
              <h3 className="text-xs font-medium">Versions</h3>
              {detail.versions.map((v) => (
                <div key={v.version} className="flex items-center gap-2 text-xs">
                  <span className="w-10 font-mono">v{v.version}</span>
                  <span className="min-w-0 flex-1 truncate text-muted-foreground">
                    {v.author || "unknown"} · {ago(v.savedAt)} ago · {v.size} bytes{v.current ? " · current" : ""}
                  </span>
                  {!v.current && (
                    <Button variant="ghost" size="sm" onClick={() => act().restore(detail.id, v.version)}>
                      Restore
                    </Button>
                  )}
                </div>
              ))}
            </section>
          </>
        )}
      </div>
    </AppDialog>
  );
}
