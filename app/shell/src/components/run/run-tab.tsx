// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Run: the saved run commands (add / edit / delete / start), the runs as pills (newest first), the selected run's
// console (xterm.js) with Stop / Force stop, and the processes panel (tracked + unattributed, reap one, reap rogue).
// Editing goes through the one modal; refusals show in the shared banner. A branch command starts on a branch picked
// from `run_branches` (the command's project, else the current one).
import { useEffect, useMemo, useState } from "react";
import { Cpu, Pencil, Play, Plus, RefreshCw, Square, SquareTerminal, Trash2, Zap } from "lucide-react";
import { isRunActive, type RunCommand, type RunSummary, type TrackedProcess } from "@infantry/client";
import {
  AppDialog, Button, Chip, EmptyState, Input, PanelCard, Select, SelectContent, SelectItem, SelectTrigger, SelectValue, Switch, Label, cn, type Tone,
} from "@infantry/ui";
import { useProjects } from "@/components/git/use-projects";
import { TabPage } from "../tabs/tab-page";
import { Field, RefusalBanner, Segmented, ago } from "../knowledge/parts";
import { RunConsole } from "./run-console";
import { runsStore, useRuns, useRunsLoaded } from "./use-runs";

type View = "runs" | "processes";

interface Draft {
  isNew: boolean;
  id: string;
  name: string;
  command: string;
  cwd: string;
  project: string;
  detached: boolean;
}

/** `a "b c" d` -> ["a", "b c", "d"]. */
export function splitArgs(text: string): string[] {
  const out: string[] = [];
  const re = /"((?:[^"\\]|\\.)*)"|'([^']*)'|(\S+)/g;
  for (let m = re.exec(text); m; m = re.exec(text)) out.push(m[1] !== undefined ? m[1].replace(/\\(.)/g, "$1") : (m[2] ?? m[3]));
  return out;
}

const joinArgs = (argv: string[]): string => argv.map((a) => (/[\s"]/.test(a) || a === "" ? `"${a.replace(/(["\\])/g, "\\$1")}"` : a)).join(" ");

function runTone(r: RunSummary): Tone {
  if (isRunActive(r)) return "blue";
  if (r.outcome === "SUCCEEDED" || r.exitCode === 0) return "green";
  return r.outcome?.startsWith("KILLED") ? "amber" : "red";
}

function runLabel(r: RunSummary): string {
  if (isRunActive(r)) return r.state.toLowerCase();
  return r.exitCode != null ? `exit ${r.exitCode}` : (r.outcome ?? "finished").toLowerCase();
}

export function RunTab() {
  useRunsLoaded();
  const commands = useRuns((s) => s.commands);
  const runs = useRuns((s) => s.runs);
  const refusal = useRuns((s) => s.refusal);
  const [view, setView] = useState<View>("runs");
  const [selected, setSelected] = useState<string | null>(null);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [branches, setBranches] = useState<Record<string, string>>({});
  const s = runsStore().getState;
  const current = useProjects((p) => p.currentId);
  const known = useRuns((x) => x.branches);
  const projectOf = (c: RunCommand) => c.project || current || "";
  const branchProjects = useMemo(() => [...new Set(commands.filter((c) => c.branch).map((c) => c.project || current || ""))].filter(Boolean), [commands, current]);
  useEffect(() => {
    for (const p of branchProjects) runsStore().getState().listBranches(p);
  }, [branchProjects]);

  // Keep a selection: the picked run while it is listed, else the newest active one, else the newest.
  useEffect(() => {
    if (selected && runs.some((r) => r.runId === selected)) return;
    setSelected((runs.find(isRunActive) ?? runs[0])?.runId ?? null);
  }, [runs, selected]);

  const run = runs.find((r) => r.runId === selected) ?? null;
  const names = useMemo(() => Object.fromEntries(commands.map((c) => [c.id, c.name])), [commands]);

  const edit = (c: RunCommand | null): void =>
    setDraft({ isNew: !c, id: c?.id ?? "", name: c?.name ?? "", command: c ? joinArgs(c.argv) : "", cwd: c?.cwd ?? "", project: c?.project ?? "", detached: !!c?.detached });

  return (
    <TabPage
      title="Run"
      description="Saved run commands and their consoles"
      actions={
        <div className="flex gap-2">
          <Button variant="ghost" size="sm" aria-label="Refresh" onClick={() => (view === "runs" ? s().refresh() : s().listProcesses())}>
            <RefreshCw />
          </Button>
          <Button variant="outline" size="sm" onClick={() => edit(null)}>
            <Plus /> New command
          </Button>
        </div>
      }
    >
      <RefusalBanner text={refusal} onDismiss={() => s().clearRefusal()} />
      <Segmented
        value={view}
        options={[
          { id: "runs", label: `Commands (${commands.length})` },
          { id: "processes", label: "Processes" },
        ]}
        onChange={setView}
        className="mb-3 max-w-xs"
      />
      {view === "processes" ? (
        <ProcessesPanel />
      ) : (
        <div className="space-y-3">
          {commands.length === 0 ? (
            <EmptyState icon={<Play className="size-6" />} title="No saved commands" hint="A run command is a program the daemon starts for you, with its own console." />
          ) : (
            <div className="grid grid-cols-[repeat(auto-fill,minmax(260px,1fr))] gap-3">
              {commands.map((c) => (
                <div key={c.id} className="flex items-center gap-2 rounded-lg border bg-card p-3">
                  <div className="min-w-0 flex-1">
                    <h3 className="truncate font-medium">{c.name || c.id}</h3>
                    <p className="truncate font-mono text-[11px] text-muted-foreground">{joinArgs(c.argv)}</p>
                    <div className="mt-1 flex flex-wrap gap-1">
                      {c.project && <Chip className="h-5 text-[10px]">{c.project}</Chip>}
                      {c.branch && <Chip className="h-5 text-[10px]">branch</Chip>}
                      {c.detached && <Chip tone="amber" className="h-5 text-[10px]">detached</Chip>}
                    </div>
                  </div>
                  <Button variant="ghost" size="icon-xs" aria-label={`Edit ${c.name || c.id}`} onClick={() => edit(c)}>
                    <Pencil />
                  </Button>
                  {c.branch && (
                    <Select value={branches[c.id] ?? ""} onValueChange={(v) => setBranches((b) => ({ ...b, [c.id]: v }))}>
                      <SelectTrigger size="sm" className="h-7 w-36 font-mono text-xs" aria-label={`Branch for ${c.name || c.id}`}>
                        <SelectValue placeholder={(known[projectOf(c)] ?? []).length ? "branch" : "no branches"} />
                      </SelectTrigger>
                      <SelectContent>
                        {(known[projectOf(c)] ?? []).map((b) => (
                          <SelectItem key={b.name} value={b.name} className="font-mono text-xs">
                            {b.name}
                            {b.worktree && <span className="ml-1 text-muted-foreground">(worktree)</span>}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                  )}
                  <Button
                    size="sm"
                    aria-label={`Start ${c.name || c.id}`}
                    disabled={c.branch && !(branches[c.id] ?? "").trim()}
                    onClick={() => s().start(c.id, c.branch ? (branches[c.id] ?? "").trim() : undefined)}
                  >
                    <Play /> Start
                  </Button>
                </div>
              ))}
            </div>
          )}

          {runs.length > 0 && (
            <div role="listbox" aria-label="Runs" className="flex flex-wrap gap-1.5">
              {runs.map((r) => (
                <button
                  key={r.runId}
                  type="button"
                  role="option"
                  aria-selected={r.runId === selected}
                  onClick={() => setSelected(r.runId)}
                  className={cn(
                    "inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-xs transition-colors",
                    r.runId === selected ? "border-info bg-info/10" : "border-border text-muted-foreground hover:border-info/40",
                  )}
                >
                  <span className="max-w-40 truncate">{r.name || names[r.commandId] || r.commandId}</span>
                  <Chip tone={runTone(r)} className="h-4 text-[10px]">
                    {runLabel(r)}
                  </Chip>
                  <span className="text-[10px] text-muted-foreground">{ago(r.startedAt)}</span>
                </button>
              ))}
            </div>
          )}

          {run ? (
            <PanelCard
              title={
                <span className="flex items-center gap-2">
                  <SquareTerminal className="size-4" />
                  {run.name || names[run.commandId] || run.commandId}
                  <span className="font-mono text-[11px] font-normal text-muted-foreground">{run.runId}</span>
                  {run.branch && <Chip className="h-5 text-[10px]">{run.branch}</Chip>}
                  {!run.interactive && <Chip className="h-5 text-[10px]">read-only</Chip>}
                </span>
              }
              action={
                isRunActive(run) && (
                  <div className="flex gap-1.5">
                    <Button variant="outline" size="xs" onClick={() => s().stop(run.runId)}>
                      <Square /> Stop
                    </Button>
                    <Button variant="destructive" size="xs" onClick={() => s().stop(run.runId, true)}>
                      <Zap /> Force stop
                    </Button>
                  </div>
                )
              }
              bodyClassName="h-[420px]"
            >
              <RunConsole runId={run.runId} interactive={!!run.interactive} />
            </PanelCard>
          ) : (
            commands.length > 0 && <p className="text-xs text-muted-foreground">No runs yet. Start a command to open its console.</p>
          )}
        </div>
      )}
      <CommandDialog draft={draft} setDraft={setDraft} />
    </TabPage>
  );
}

function CommandDialog({ draft: d, setDraft }: { draft: Draft | null; setDraft: (d: Draft | null) => void }) {
  const close = (): void => setDraft(null);
  const set = (patch: Partial<Draft>): void => {
    if (d) setDraft({ ...d, ...patch });
  };
  const argv = d ? splitArgs(d.command) : [];
  const save = (): void => {
    if (!d) return;
    runsStore().getState().saveCommand({ id: d.id.trim(), name: d.name.trim() || undefined, argv, cwd: d.cwd.trim() || undefined, project: d.project.trim() || undefined, detached: d.detached });
    close();
  };
  return (
    <AppDialog
      open={d !== null}
      onOpenChange={(o) => !o && close()}
      title={d?.isNew ? "New run command" : (d?.name || d?.id || "")}
      description="The daemon starts the program with its own console; arguments are split like a shell."
      footer={
        <div className="flex w-full justify-between gap-2">
          {d && !d.isNew ? (
            <Button
              variant="destructive"
              size="sm"
              onClick={() => {
                runsStore().getState().deleteCommand(d.id);
                close();
              }}
            >
              <Trash2 /> Delete
            </Button>
          ) : (
            <span />
          )}
          <Button size="sm" disabled={!d?.id.trim() || argv.length === 0} onClick={save}>
            Save
          </Button>
        </div>
      }
    >
      {d && (
        <div className="space-y-3">
          <div className="grid gap-3 sm:grid-cols-2">
            <Field id="cmd-id" label="Id">
              <Input id="cmd-id" className="font-mono text-xs" value={d.id} disabled={!d.isNew} onChange={(e) => set({ id: e.target.value })} />
            </Field>
            <Field id="cmd-name" label="Name">
              <Input id="cmd-name" value={d.name} onChange={(e) => set({ name: e.target.value })} />
            </Field>
          </div>
          <Field id="cmd-argv" label="Command" hint={argv.length ? `${argv.length} argument${argv.length === 1 ? "" : "s"}` : "The program and its arguments."}>
            <Input id="cmd-argv" className="font-mono text-xs" value={d.command} onChange={(e) => set({ command: e.target.value })} placeholder='npm run dev -- --port 5173' />
          </Field>
          <Field id="cmd-cwd" label="Working folder" hint="Empty = the project folder.">
            <Input id="cmd-cwd" className="font-mono text-xs" value={d.cwd} onChange={(e) => set({ cwd: e.target.value })} />
          </Field>
          <Field id="cmd-project" label="Project">
            <Input id="cmd-project" value={d.project} onChange={(e) => set({ project: e.target.value })} placeholder="current project" />
          </Field>
          <div className="flex items-center gap-2">
            <Switch id="cmd-detached" checked={d.detached} onCheckedChange={(v) => set({ detached: v })} />
            <Label htmlFor="cmd-detached">Detached (keeps running when the app closes)</Label>
          </div>
        </div>
      )}
    </AppDialog>
  );
}

function ProcessRow({ p }: { p: TrackedProcess }) {
  return (
    <li className="flex items-center gap-2 rounded-md border bg-raised px-2 py-1.5 text-xs">
      <span className="w-14 shrink-0 font-mono text-muted-foreground">{p.pid}</span>
      <span className="min-w-0 flex-1 truncate font-mono" title={p.command}>
        {p.command}
      </span>
      {p.sessionId && <Chip className="h-5 text-[10px]">{p.sessionId.slice(0, 8)}</Chip>}
      {p.memoryMb != null && <span className="shrink-0 text-muted-foreground">{Math.round(p.memoryMb)} MB</span>}
      {p.rogue && (
        <span title={p.reason ?? undefined}>
          <Chip tone="red" className="h-5 text-[10px]">
            rogue
          </Chip>
        </span>
      )}
      <Button variant="ghost" size="xs" onClick={() => runsStore().getState().reap(p.pid)}>
        Reap
      </Button>
    </li>
  );
}

export function ProcessesPanel() {
  const answer = useRuns((s) => s.processes);
  useEffect(() => runsStore().getState().listProcesses(), []);
  const r = answer?.report;
  if (!r) return <EmptyState icon={<Cpu className="size-6" />} title="No process report yet" />;
  const rogue = [...r.tracked, ...r.unattributed].some((p) => p.rogue);
  return (
    <div className="space-y-3">
      <PanelCard
        title="Memory"
        action={
          <Button variant="destructive" size="xs" disabled={!rogue} onClick={() => runsStore().getState().reapRogue()}>
            Reap rogue
          </Button>
        }
      >
        <p className="text-[13px]">
          {r.freeMb != null ? `${Math.round(r.freeMb)} MB free` : "free: -"}
          {r.totalMb != null ? ` of ${Math.round(r.totalMb)} MB` : ""} · floor {r.floorMb ?? "-"} MB
          {r.underPressure && (
            <Chip tone="red" className="ml-2 h-5 text-[10px]">
              under pressure
            </Chip>
          )}
        </p>
        {answer.reaped.length > 0 && <p className="mt-1 text-xs text-muted-foreground">Reaped {answer.reaped.length} process(es).</p>}
      </PanelCard>
      <PanelCard title={`Tracked (${r.tracked.length})`}>
        {r.tracked.length === 0 ? <p className="text-xs text-muted-foreground">None.</p> : <ul className="space-y-1">{r.tracked.map((p) => <ProcessRow key={p.pid} p={p} />)}</ul>}
      </PanelCard>
      <PanelCard title={`Unattributed (${r.unattributed.length})`}>
        {r.unattributed.length === 0 ? <p className="text-xs text-muted-foreground">None.</p> : <ul className="space-y-1">{r.unattributed.map((p) => <ProcessRow key={p.pid} p={p} />)}</ul>}
      </PanelCard>
    </div>
  );
}
