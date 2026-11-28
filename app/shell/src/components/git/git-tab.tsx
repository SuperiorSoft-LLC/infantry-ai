// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The Changes / Git panel on the live wire: the current project's working tree (or one of its worktrees, or what a
// branch changed since its base), a diff viewer, the history and the branches. Unknown is never shown as clean.
import { useState, type ReactNode } from "react";
import { Columns2, FolderGit2, Maximize2, GitBranch, GitCommitHorizontal, GitFork, RefreshCw, Rows3, X } from "lucide-react";
import {
  changeLetter,
  groupChanges,
  type BranchChangedFile,
  type ChangedFile,
  type DiffTarget,
  type GitCommit,
} from "@infantry/client";
import {
  AppDialog,
  Button,
  Chip,
  EmptyState,
  IconButton,
  Input,
  ScrollArea,
  SectionLabel,
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
  ToggleGroup,
  ToggleGroupItem,
  cn,
} from "@infantry/ui";
import { DiffView, type DiffMode } from "./diff-view";
import { projectsStore, useProjects, useProjectsLoaded } from "./use-projects";

const PROJECT_DIR = "__project__";

const letterTone: Record<string, string> = { A: "text-ok", U: "text-ok", M: "text-warn", R: "text-info", D: "text-danger", "!": "text-danger" };

function ago(epochSeconds: number): string {
  const s = Math.max(0, Date.now() / 1000 - epochSeconds);
  if (s < 60) return "just now";
  if (s < 3600) return `${Math.floor(s / 60)}m ago`;
  if (s < 86400) return `${Math.floor(s / 3600)}h ago`;
  if (s < 86400 * 30) return `${Math.floor(s / 86400)}d ago`;
  return new Date(epochSeconds * 1000).toLocaleDateString();
}

function FileRow({ path, fromPath, letter, active, onClick, trailing }: { path: string; fromPath?: string | null; letter: string; active: boolean; onClick: () => void; trailing?: ReactNode }) {
  const slash = path.lastIndexOf("/");
  return (
    <button
      type="button"
      onClick={onClick}
      title={fromPath ? `${fromPath} → ${path}` : path}
      className={cn("flex w-full items-center gap-2 rounded-md px-2 py-1 text-left text-xs hover:bg-accent", active && "bg-accent")}
    >
      <span className={cn("w-3 shrink-0 font-mono font-semibold", letterTone[letter])}>{letter}</span>
      <span className="min-w-0 flex-1 truncate">
        <span className="font-medium">{path.slice(slash + 1)}</span>
        {slash > 0 && <span className="ml-1.5 text-muted-foreground">{path.slice(0, slash)}</span>}
      </span>
      {trailing}
    </button>
  );
}

function sameTarget(a: DiffTarget | null, b: DiffTarget): boolean {
  if (!a || a.kind !== b.kind || a.path !== b.path) return false;
  if (a.kind === "branch" && b.kind === "branch") return a.branch === b.branch;
  return a.kind === "tree" && b.kind === "tree" && a.staged === b.staged;
}

function TreeChanges({ changes, truncated, worktree }: { changes: ChangedFile[]; truncated?: boolean; worktree: string | null }) {
  const target = useProjects((s) => s.target);
  const { staged, unstaged } = groupChanges(changes);
  if (!changes.length) return <p className="px-2 py-3 text-xs text-ok">Working tree clean. Nothing to commit.</p>;
  const group = (title: string, files: ChangedFile[]) =>
    files.length > 0 && (
      <div>
        <SectionLabel>
          {title} · {files.length}
        </SectionLabel>
        {files.map((f) => {
          const t: DiffTarget = { kind: "tree", path: f.path, staged: f.staged, worktree };
          return <FileRow key={`${f.staged}:${f.path}`} path={f.path} fromPath={f.fromPath} letter={changeLetter[f.status]} active={sameTarget(target, t)} onClick={() => projectsStore().getState().openFile(t)} />;
        })}
      </div>
    );
  return (
    <div className="space-y-1">
      {group("Staged", staged)}
      {group("Changes", unstaged)}
      {truncated && <p className="px-2 text-[11px] text-muted-foreground">More files changed than are listed.</p>}
    </div>
  );
}

function BranchChanges({ branch }: { branch: string }) {
  const d = useProjects((s) => s.branchChanges);
  const target = useProjects((s) => s.target);
  const head = (
    <div className="flex items-center gap-2 px-2 pb-2 text-xs">
      <GitBranch className="size-3.5 text-muted-foreground" />
      <span className="truncate font-medium">{branch}</span>
      {d?.base && <span className="text-muted-foreground">vs {d.base}</span>}
      <IconButton label="Back to the working tree" variant="ghost" size="icon" className="ml-auto size-6" onClick={() => projectsStore().getState().viewBranch(null)}>
        <X />
      </IconButton>
    </div>
  );
  if (!d) return <>{head}<p className="px-2 text-xs text-muted-foreground">Loading branch changes…</p></>;
  if (!d.available) return <>{head}<p className="px-2 text-xs text-warn">Branch unknown: {d.reason}</p></>;
  return (
    <>
      {head}
      {d.changes.length === 0 && <p className="px-2 text-xs text-ok">No changes vs {d.base}.</p>}
      {d.changes.map((f: BranchChangedFile) => {
        const t: DiffTarget = { kind: "branch", branch, base: null, path: f.path };
        return (
          <FileRow
            key={f.path}
            path={f.path}
            fromPath={f.fromPath}
            letter={changeLetter[f.status]}
            active={sameTarget(target, t)}
            onClick={() => projectsStore().getState().openFile(t)}
            trailing={
              f.binary ? (
                <span className="text-[10px] text-muted-foreground">binary</span>
              ) : (
                <span className="shrink-0 font-mono text-[10px]">
                  <span className="text-ok">+{f.added}</span> <span className="text-danger">−{f.removed}</span>
                </span>
              )
            }
          />
        );
      })}
      {d.truncated && <p className="px-2 text-[11px] text-muted-foreground">More files changed than are listed.</p>}
    </>
  );
}

function ChangesPane() {
  const status = useProjects((s) => s.status);
  const worktree = useProjects((s) => s.worktree);
  const wts = useProjects((s) => s.worktreeStatus);
  const branch = useProjects((s) => s.branch);
  if (branch) return <BranchChanges branch={branch} />;
  if (worktree) {
    if (!wts) return <p className="px-2 text-xs text-muted-foreground">Loading worktree…</p>;
    if (!wts.available) return <p className="px-2 text-xs text-warn">Worktree unknown: {wts.reason}</p>;
    return (
      <>
        <div className="flex flex-wrap items-center gap-1.5 px-2 pb-2 text-xs">
          {wts.ahead != null && <Chip tone="green">↑ {wts.ahead}</Chip>}
          {wts.behind != null && <Chip tone={wts.behind ? "amber" : "neutral"}>↓ {wts.behind}</Chip>}
          {wts.base ? <span className="text-muted-foreground">vs {wts.base}</span> : <span className="text-muted-foreground">{wts.baseReason}</span>}
        </div>
        <TreeChanges changes={wts.changes} truncated={wts.changesTruncated} worktree={worktree} />
      </>
    );
  }
  if (!status) return <p className="px-2 text-xs text-muted-foreground">Reading the working tree…</p>;
  if (!status.available) return <p className="px-2 text-xs text-warn">Git unknown: {status.reason}</p>;
  return <TreeChanges changes={status.changes} truncated={status.changesTruncated} worktree={null} />;
}

function CommitRow({ c }: { c: GitCommit }) {
  return (
    <div className="flex items-start gap-2 rounded-md px-2 py-1.5 hover:bg-accent" title={c.hash}>
      <GitCommitHorizontal className="mt-0.5 size-3.5 shrink-0 text-muted-foreground" />
      <div className="min-w-0 flex-1">
        <div className="truncate text-xs font-medium">{c.subject}</div>
        <div className="text-[11px] text-muted-foreground">
          <span className="font-mono">{c.shortHash}</span> · {c.author} · {ago(c.timestamp)}
        </div>
      </div>
    </div>
  );
}

function HistoryPane() {
  const history = useProjects((s) => s.history);
  const worktree = useProjects((s) => s.worktree);
  const wts = useProjects((s) => s.worktreeStatus);
  const commits = worktree ? wts?.commits : history?.commits;
  const available = worktree ? wts?.available : history?.available;
  const reason = worktree ? wts?.reason : history?.reason;
  const truncated = worktree ? wts?.commitsTruncated : history?.truncated;
  if (!commits && available === undefined) return <p className="px-2 text-xs text-muted-foreground">Loading history…</p>;
  if (available === false) return <p className="px-2 text-xs text-warn">History unknown: {reason}</p>;
  if (!commits?.length) return <p className="px-2 text-xs text-muted-foreground">No commits yet.</p>;
  return (
    <div>
      {commits.map((c) => (
        <CommitRow key={c.hash} c={c} />
      ))}
      {truncated && !worktree && (
        <Button variant="ghost" size="sm" className="mt-1 w-full text-xs" onClick={() => projectsStore().getState().loadMoreHistory()}>
          Show older commits
        </Button>
      )}
    </div>
  );
}

function BranchesPane() {
  const list = useProjects((s) => s.branches);
  const result = useProjects((s) => s.checkout);
  const [name, setName] = useState("");
  if (!list) return <p className="px-2 text-xs text-muted-foreground">Loading branches…</p>;
  if (!list.available) return <p className="px-2 text-xs text-warn">Branches unknown: {list.reason || "the daemon could not list them"}</p>;
  const act = projectsStore().getState();
  return (
    <div className="space-y-2">
      {result && !result.switched && (
        <div className="rounded-md border border-warn/50 bg-warn/10 px-2 py-1.5 text-xs text-warn">
          Not switched to {result.branch}: {result.reason}
        </div>
      )}
      <div>
        {list.branches.map((b) => {
          const current = b.name === list.current;
          return (
            <div key={b.name} className="group flex items-center gap-2 rounded-md px-2 py-1 text-xs hover:bg-accent">
              <GitBranch className={cn("size-3.5 shrink-0", current ? "text-ok" : "text-muted-foreground")} />
              <button type="button" className="min-w-0 flex-1 truncate text-left" title="Show what this branch changed" onClick={() => act.viewBranch(b.name)}>
                <span className={cn(current && "font-semibold")}>{b.name}</span>
                {b.upstream && <span className="ml-1.5 text-muted-foreground">{b.upstream}</span>}
              </button>
              {b.isWorktree && !current && (
                <span title={b.worktreePath ?? ""}>
                  <Chip>worktree</Chip>
                </span>
              )}
              {current ? (
                <Chip tone="green">current</Chip>
              ) : (
                <Button variant="outline" size="sm" className="h-6 px-2 text-[11px] opacity-0 group-hover:opacity-100" disabled={b.isWorktree} onClick={() => act.checkoutBranch(b.name)}>
                  Switch
                </Button>
              )}
            </div>
          );
        })}
        {list.truncated && <p className="px-2 text-[11px] text-muted-foreground">More branches exist than are listed.</p>}
      </div>
      <form
        className="flex gap-2 px-1"
        onSubmit={(e) => {
          e.preventDefault();
          if (name.trim()) act.checkoutBranch(name.trim(), true);
          setName("");
        }}
      >
        <Input value={name} onChange={(e) => setName(e.target.value)} placeholder="New branch from current" className="h-8 text-xs" />
        <Button type="submit" size="sm" variant="outline" disabled={!name.trim()}>
          Create
        </Button>
      </form>
    </div>
  );
}

function WorktreePicker() {
  const list = useProjects((s) => s.worktrees);
  const worktree = useProjects((s) => s.worktree);
  if (!list?.available || list.worktrees.length < 2) return null;
  const label = (path: string) => path.replace(/\\/g, "/").split("/").pop() || path;
  return (
    <Select value={worktree ?? PROJECT_DIR} onValueChange={(v) => projectsStore().getState().viewWorktree(v === PROJECT_DIR ? null : v)}>
      <SelectTrigger size="sm" className="h-8 max-w-56 text-xs">
        <GitFork className="size-3.5" />
        <SelectValue />
      </SelectTrigger>
      <SelectContent>
        <SelectItem value={PROJECT_DIR}>Project directory</SelectItem>
        {list.worktrees
          .filter((w) => !w.bare && !w.project)
          .map((w) => (
            <SelectItem key={w.path} value={w.path} disabled={w.prunable}>
              {label(w.path)} {w.branch ? `· ${w.branch}` : w.detached ? "· detached" : ""}
            </SelectItem>
          ))}
      </SelectContent>
    </Select>
  );
}

export function GitTab() {
  useProjectsLoaded();
  const projects = useProjects((s) => s.projects);
  const loaded = useProjects((s) => s.loaded);
  const current = useProjects((s) => s.projects.find((p) => p.id === s.currentId) ?? null);
  const status = useProjects((s) => s.status);
  const worktree = useProjects((s) => s.worktree);
  const wts = useProjects((s) => s.worktreeStatus);
  const patch = useProjects((s) => s.patch);
  const loading = useProjects((s) => s.patchLoading);
  const target = useProjects((s) => s.target);
  const error = useProjects((s) => s.error);
  const [mode, setMode] = useState<DiffMode>("unified");
  const [pane, setPane] = useState("changes");

  if (!loaded) return <div className="p-6 text-xs text-muted-foreground">Loading projects…</div>;
  if (!projects.length || !current)
    return (
      <div className="p-6">
        <EmptyState icon={<FolderGit2 className="size-6" />} title="No project yet" hint="Add a project with a directory from the project switcher to see its git tree." />
      </div>
    );

  const branch = worktree ? wts?.branch : status?.branch;
  const head = worktree ? wts?.head : status?.head;
  const count = worktree ? wts?.uncommitted : status?.available ? new Set(status.changes.map((c) => c.path)).size : undefined;

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <div className="flex flex-wrap items-center gap-2 border-b px-4 py-2.5">
        <FolderGit2 className="size-4 text-muted-foreground" />
        <h2 className="text-sm font-semibold">{current.name}</h2>
        {branch ? <Chip tone="blue">{branch}</Chip> : status?.available && <Chip tone="amber">detached</Chip>}
        {head && <span className="font-mono text-[11px] text-muted-foreground">{head.slice(0, 8)}</span>}
        {count != null && count > 0 && <Chip tone="amber">{count} changed</Chip>}
        <span className="min-w-0 flex-1 truncate text-right font-mono text-[11px] text-muted-foreground" title={current.directory}>
          {current.directory || "no directory set"}
        </span>
        <WorktreePicker />
        <IconButton label="Refresh" variant="ghost" size="icon" className="size-8" onClick={() => projectsStore().getState().refresh()}>
          <RefreshCw />
        </IconButton>
      </div>
      {error && (
        <div className="flex items-center gap-2 border-b bg-danger/10 px-4 py-1.5 text-xs text-danger">
          <span className="min-w-0 flex-1 truncate">{error}</span>
          <IconButton label="Dismiss" variant="ghost" size="icon" className="size-6" onClick={() => projectsStore().getState().clearError()}>
            <X />
          </IconButton>
        </div>
      )}
      <div className="flex min-h-0 flex-1 max-sm:flex-col">
        <Tabs value={pane} onValueChange={setPane} className="flex w-80 shrink-0 flex-col gap-0 border-r max-sm:max-h-[45%] max-sm:w-full max-sm:border-r-0 max-sm:border-b">
          <TabsList className="m-2 grid grid-cols-3">
            <TabsTrigger value="changes">Changes</TabsTrigger>
            <TabsTrigger value="history">History</TabsTrigger>
            <TabsTrigger value="branches">Branches</TabsTrigger>
          </TabsList>
          <ScrollArea className="min-h-0 flex-1">
            <div className="px-2 pb-3">
              <TabsContent value="changes">
                <ChangesPane />
              </TabsContent>
              <TabsContent value="history">
                <HistoryPane />
              </TabsContent>
              <TabsContent value="branches">
                <BranchesPane />
              </TabsContent>
            </div>
          </ScrollArea>
        </Tabs>
        <div className="flex min-w-0 flex-1 flex-col">
          <div className="flex items-center gap-2 border-b px-3 py-1.5">
            <span className="min-w-0 flex-1 truncate font-mono text-xs">
              {target ? target.path : <span className="text-muted-foreground">No file selected</span>}
              {target?.kind === "tree" && target.staged && <span className="ml-2 text-muted-foreground">(staged)</span>}
              {target?.kind === "branch" && <span className="ml-2 text-muted-foreground">on {target.branch}</span>}
            </span>
            <ToggleGroup type="single" size="sm" value={mode} onValueChange={(v) => v && setMode(v as DiffMode)}>
              <ToggleGroupItem value="unified" aria-label="Unified diff" title="Unified">
                <Rows3 />
              </ToggleGroupItem>
              <ToggleGroupItem value="split" aria-label="Side-by-side diff" title="Side by side">
                <Columns2 />
              </ToggleGroupItem>
            </ToggleGroup>
          </div>
          <ScrollArea className="min-h-0 flex-1">
            <DiffView patch={patch} loading={loading} mode={mode} />
          </ScrollArea>
        </div>
      </div>
    </div>
  );
}

/**
 * The right-panel Changes view (RUST-UI-7, the Kotlin `infantry.changes` tab): the same projects store and panes as the Git
 * tab, stacked for a narrow column: the working tree's changes (or the picked worktree's), then the picked file's diff.
 */
export function ChangesPanel() {
  useProjectsLoaded();
  const loaded = useProjects((s) => s.loaded);
  const current = useProjects((s) => s.projects.find((p) => p.id === s.currentId) ?? null);
  const status = useProjects((s) => s.status);
  const patch = useProjects((s) => s.patch);
  const loading = useProjects((s) => s.patchLoading);
  const target = useProjects((s) => s.target);
  const error = useProjects((s) => s.error);
  const [full, setFull] = useState(false);
  if (!loaded) return <p className="p-2 text-xs text-muted-foreground">Loading projects…</p>;
  if (!current) return <EmptyState icon={<FolderGit2 className="size-6" />} title="No project yet" hint="Changes are read from the current project's directory." />;
  return (
    <div className="space-y-2">
      <div className="flex flex-wrap items-center gap-1.5">
        <span className="min-w-0 flex-1 truncate text-xs font-semibold">{current.name}</span>
        {status?.branch && <Chip tone="blue">{status.branch}</Chip>}
        <WorktreePicker />
        <IconButton label="Refresh" variant="ghost" size="icon" className="size-7" onClick={() => projectsStore().getState().refresh()}>
          <RefreshCw />
        </IconButton>
      </div>
      {error && (
        <div className="flex items-center gap-2 rounded-md bg-danger/10 px-2 py-1 text-xs text-danger">
          <span className="min-w-0 flex-1 truncate">{error}</span>
          <IconButton label="Dismiss" variant="ghost" size="icon" className="size-6" onClick={() => projectsStore().getState().clearError()}>
            <X />
          </IconButton>
        </div>
      )}
      <ChangesPane />
      {target && (
        <div className="rounded-lg border">
          <div className="flex items-center gap-1 border-b px-2 py-1">
            <span className="min-w-0 flex-1 truncate font-mono text-[11px]">{target.path}</span>
            <IconButton label="Open full diff" data-testid="changes-open-full" variant="ghost" size="icon" className="size-6" onClick={() => setFull(true)}>
              <Maximize2 />
            </IconButton>
          </div>
          <DiffView patch={patch} loading={loading} mode="unified" />
          <ChangesDiffModal open={full} onOpenChange={setFull} path={target.path}>
            <DiffView patch={patch} loading={loading} mode="unified" />
          </ChangesDiffModal>
        </div>
      )}
    </div>
  );
}

/** "Open full" for the Changes panel: the selected file's diff (the same DiffView) in the wide shared modal. */
export function ChangesDiffModal({ open, onOpenChange, path, children }: { open: boolean; onOpenChange: (o: boolean) => void; path: string; children: ReactNode }) {
  return (
    <AppDialog open={open} wide="full" onOpenChange={onOpenChange} title={path} description="Changes against the base">
      <div data-testid="changes-diff-modal" className="max-h-[75vh] overflow-auto rounded-lg border">
        {children}
      </div>
    </AppDialog>
  );
}
