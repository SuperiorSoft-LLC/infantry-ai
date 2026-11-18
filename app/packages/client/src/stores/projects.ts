// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The projects + git store: which project is current, and its git view (contract 1.36.0 / 1.65.0 / 1.74.0 / 1.88.0).
//   project_list -> projects, project_save -> project
//   project_git_status -> project_git, project_git_diff / project_git_branch_diff -> project_git_patch,
//   project_git_log -> project_git_history, project_git_branches -> project_git_branch_list,
//   project_git_branch_changes -> project_git_branch_diffstat, project_git_worktrees -> project_git_worktree_list,
//   project_git_worktree_status -> project_git_worktree, project_git_checkout -> project_git_checkout_result.
// Honesty rule: `available: false` is UNKNOWN (show the reason), never "clean".
import { createStore, type StoreApi } from "zustand/vanilla";
import { json, type Envelope } from "../wire";

export type ChangeKind = "ADDED" | "MODIFIED" | "DELETED" | "RENAMED" | "UNTRACKED" | "CONFLICTED";

export interface ChangedFile {
  path: string;
  status: ChangeKind;
  staged: boolean;
  fromPath?: string | null;
}

export interface ProjectInfo {
  id: string;
  name: string;
  goal?: string;
  directory?: string;
  baseBranch?: string;
}

export interface GitStatus {
  projectId: string;
  directory: string;
  available: boolean;
  reason?: string;
  repoRoot?: string;
  branch?: string | null;
  head?: string | null;
  changes: ChangedFile[];
  changesTruncated?: boolean;
}

export interface GitPatch {
  projectId: string;
  path: string;
  staged: boolean;
  available: boolean;
  reason?: string;
  patch: string;
  binary?: boolean;
  truncated?: boolean;
  branch?: string | null;
  worktree?: string | null;
}

export interface GitCommit {
  hash: string;
  shortHash: string;
  author: string;
  timestamp: number;
  subject: string;
}

export interface GitHistory {
  projectId: string;
  available: boolean;
  reason?: string;
  commits: GitCommit[];
  truncated?: boolean;
}

export interface GitBranch {
  name: string;
  head: string;
  upstream?: string | null;
  isWorktree?: boolean;
  worktreePath?: string | null;
}

export interface GitBranchList {
  projectId: string;
  available: boolean;
  reason?: string;
  current?: string | null;
  branches: GitBranch[];
  truncated?: boolean;
}

export interface BranchChangedFile {
  path: string;
  status: ChangeKind;
  fromPath?: string | null;
  added: number;
  removed: number;
  binary?: boolean;
}

export interface GitBranchDiffstat {
  projectId: string;
  branch: string;
  base: string;
  mergeBase: string;
  available: boolean;
  reason?: string;
  changes: BranchChangedFile[];
  truncated?: boolean;
}

export interface GitWorktree {
  path: string;
  branch?: string | null;
  head?: string | null;
  main?: boolean;
  project?: boolean;
  session?: boolean;
  detached?: boolean;
  bare?: boolean;
  prunable?: boolean;
}

export interface GitWorktreeList {
  projectId: string;
  available: boolean;
  reason?: string;
  worktrees: GitWorktree[];
  selected?: string | null;
}

export interface GitWorktreeStatus {
  projectId: string;
  path: string;
  available: boolean;
  reason?: string;
  branch?: string | null;
  head?: string | null;
  base?: string;
  ahead?: number | null;
  behind?: number | null;
  baseReason?: string;
  uncommitted?: number;
  changes: ChangedFile[];
  changesTruncated?: boolean;
  commits: GitCommit[];
  commitsTruncated?: boolean;
}

export interface CheckoutResult {
  projectId: string;
  branch: string;
  create?: boolean;
  switched: boolean;
  code?: string;
  reason?: string;
  previous?: string | null;
  current?: string | null;
  dirtyCount?: number;
  worktreePath?: string | null;
}

/** What the diff viewer shows: a working-tree file (optionally in a worktree) or a file a branch changed. */
export type DiffTarget =
  | { kind: "tree"; path: string; staged: boolean; worktree?: string | null }
  | { kind: "branch"; branch: string; base?: string | null; path: string };

export interface ProjectsLink {
  sendFrame(env: Envelope): boolean;
  onFrame(listener: (env: Envelope) => void): () => void;
}

export interface ProjectsState {
  projects: ProjectInfo[];
  loaded: boolean;
  currentId: string | null;
  status: GitStatus | null;
  history: GitHistory | null;
  branches: GitBranchList | null;
  worktrees: GitWorktreeList | null;
  /** The worktree the view reads; null = the project's own directory. */
  worktree: string | null;
  worktreeStatus: GitWorktreeStatus | null;
  /** The branch whose changes since its base are listed; null = the working tree. */
  branch: string | null;
  branchChanges: GitBranchDiffstat | null;
  target: DiffTarget | null;
  patch: GitPatch | null;
  patchLoading: boolean;
  checkout: CheckoutResult | null;
  /** The last `project_* refused/failed` text. */
  error: string | null;
}

export interface ProjectsActions {
  ensureLoaded(): void;
  select(projectId: string): void;
  /** Re-read status, history, branches and worktrees of the current project. */
  refresh(): void;
  openFile(target: DiffTarget): void;
  viewBranch(branch: string | null): void;
  viewWorktree(path: string | null): void;
  loadMoreHistory(): void;
  checkoutBranch(branch: string, create?: boolean): void;
  saveProject(input: { id?: string; name?: string; directory?: string }): void;
  clearError(): void;
  dispose(): void;
}

export type ProjectsStore = StoreApi<ProjectsState & ProjectsActions>;

const HISTORY_PAGE = 50;

/** Group key for a file: one row per path even when it changed on both sides. */
export function groupChanges(changes: ChangedFile[]): { staged: ChangedFile[]; unstaged: ChangedFile[] } {
  return { staged: changes.filter((c) => c.staged), unstaged: changes.filter((c) => !c.staged) };
}

export const changeLetter: Record<ChangeKind, string> = { ADDED: "A", MODIFIED: "M", DELETED: "D", RENAMED: "R", UNTRACKED: "U", CONFLICTED: "!" };

// ---- unified diff model -------------------------------------------------------------------------

export type PatchLineKind = "context" | "add" | "del" | "hunk" | "meta";

export interface PatchLine {
  kind: PatchLineKind;
  text: string;
  oldNo: number | null;
  newNo: number | null;
}

/** Parses a unified diff into numbered lines (headers become `meta`, `@@` lines `hunk`). */
export function parseUnifiedDiff(patch: string): PatchLine[] {
  const out: PatchLine[] = [];
  let oldNo = 0;
  let newNo = 0;
  let inHunk = false;
  for (const raw of patch.split("\n")) {
    const line = raw.endsWith("\r") ? raw.slice(0, -1) : raw;
    const h = /^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@(.*)$/.exec(line);
    if (h) {
      oldNo = Number(h[1]);
      newNo = Number(h[2]);
      inHunk = true;
      out.push({ kind: "hunk", text: line, oldNo: null, newNo: null });
      continue;
    }
    if (!inHunk || line.startsWith("diff --git")) {
      inHunk = false;
      if (line.length) out.push({ kind: "meta", text: line, oldNo: null, newNo: null });
      continue;
    }
    if (line.startsWith("+")) out.push({ kind: "add", text: line.slice(1), oldNo: null, newNo: newNo++ });
    else if (line.startsWith("-")) out.push({ kind: "del", text: line.slice(1), oldNo: oldNo++, newNo: null });
    else if (line.startsWith("\\")) out.push({ kind: "meta", text: line, oldNo: null, newNo: null });
    else if (line.length || raw.length) out.push({ kind: "context", text: line.slice(1), oldNo: oldNo++, newNo: newNo++ });
  }
  return out;
}

export interface SplitRow {
  left: PatchLine | null;
  right: PatchLine | null;
  /** A hunk or meta line spanning both columns. */
  full?: PatchLine;
}

/** Side-by-side rows: a run of deletions is paired with the run of additions that follows it. */
export function splitRows(lines: PatchLine[]): SplitRow[] {
  const rows: SplitRow[] = [];
  let i = 0;
  while (i < lines.length) {
    const l = lines[i];
    if (l.kind === "hunk" || l.kind === "meta") {
      rows.push({ left: null, right: null, full: l });
      i++;
    } else if (l.kind === "context") {
      rows.push({ left: l, right: l });
      i++;
    } else {
      const dels: PatchLine[] = [];
      const adds: PatchLine[] = [];
      while (i < lines.length && lines[i].kind === "del") dels.push(lines[i++]);
      while (i < lines.length && lines[i].kind === "add") adds.push(lines[i++]);
      for (let k = 0; k < Math.max(dels.length, adds.length); k++) rows.push({ left: dels[k] ?? null, right: adds[k] ?? null });
    }
  }
  return rows;
}

// ---- the store ----------------------------------------------------------------------------------

export function createProjectsStore(link: ProjectsLink): ProjectsStore {
  return createStore<ProjectsState & ProjectsActions>()((set, get) => {
    const send = (type: string, body: unknown): void => {
      link.sendFrame({ type, data: typeof body === "string" ? body : JSON.stringify(body) });
    };
    const forCurrent = (projectId: string | undefined): boolean => projectId === get().currentId;
    let historyLimit = HISTORY_PAGE;

    const readAll = (): void => {
      const id = get().currentId;
      if (!id) return;
      send("project_git_status", id);
      send("project_git_log", { projectId: id, limit: historyLimit });
      send("project_git_branches", { projectId: id });
      send("project_git_worktrees", { projectId: id });
      const wt = get().worktree;
      if (wt) send("project_git_worktree_status", { projectId: id, path: wt });
      const br = get().branch;
      if (br) send("project_git_branch_changes", { projectId: id, branch: br });
      const t = get().target;
      if (t) requestPatch(t);
    };

    const requestPatch = (t: DiffTarget): void => {
      const id = get().currentId;
      if (!id) return;
      set({ target: t, patchLoading: true });
      if (t.kind === "tree") send("project_git_diff", { projectId: id, path: t.path, staged: t.staged, ...(t.worktree ? { worktree: t.worktree } : {}) });
      else send("project_git_branch_diff", { projectId: id, branch: t.branch, path: t.path, ...(t.base ? { base: t.base } : {}) });
    };

    const matchesTarget = (p: GitPatch): boolean => {
      const t = get().target;
      if (!t || p.path !== t.path) return false;
      if (t.kind === "branch") return p.branch === t.branch;
      return !p.branch && p.staged === t.staged && (p.worktree ?? null) === (t.worktree ?? null);
    };

    const off = link.onFrame((env) => {
      switch (env.type) {
        case "ready":
          send("project_list", "");
          if (get().currentId) readAll();
          return;
        case "projects": {
          const list = json<ProjectInfo[]>(env.data) ?? [];
          const keep = list.some((p) => p.id === get().currentId);
          set({ projects: list, loaded: true });
          if (!keep && list[0]) get().select(list[0].id);
          if (!list.length) set({ currentId: null });
          return;
        }
        case "project": {
          const p = json<ProjectInfo>(env.data);
          if (!p) return;
          const has = get().projects.some((x) => x.id === p.id);
          set({ projects: has ? get().projects.map((x) => (x.id === p.id ? p : x)) : [...get().projects, p] });
          if (!get().currentId) get().select(p.id);
          else if (p.id === get().currentId) send("project_git_status", p.id);
          return;
        }
        case "project_git": {
          const s = json<GitStatus>(env.data);
          if (s && forCurrent(s.projectId)) set({ status: s });
          return;
        }
        case "project_git_patch": {
          const p = json<GitPatch>(env.data);
          if (p && forCurrent(p.projectId) && matchesTarget(p)) set({ patch: p, patchLoading: false });
          return;
        }
        case "project_git_history": {
          const h = json<GitHistory>(env.data);
          if (h && forCurrent(h.projectId)) set({ history: h });
          return;
        }
        case "project_git_branch_list": {
          const b = json<GitBranchList>(env.data);
          if (b && forCurrent(b.projectId)) set({ branches: b });
          return;
        }
        case "project_git_branch_diffstat": {
          const d = json<GitBranchDiffstat>(env.data);
          if (d && forCurrent(d.projectId) && d.branch === get().branch) set({ branchChanges: d });
          return;
        }
        case "project_git_worktree_list": {
          const w = json<GitWorktreeList>(env.data);
          if (w && forCurrent(w.projectId)) set({ worktrees: w });
          return;
        }
        case "project_git_worktree": {
          const w = json<GitWorktreeStatus>(env.data);
          if (w && forCurrent(w.projectId) && w.path === get().worktree) set({ worktreeStatus: w });
          return;
        }
        case "project_git_checkout_result": {
          const r = json<CheckoutResult>(env.data);
          if (!r || !forCurrent(r.projectId)) return;
          set({ checkout: r });
          if (r.switched) readAll();
          return;
        }
        case "error":
          if (/^(project|workspace)[a-z_]* (refused|failed)/.test(env.data)) {
            set({ error: env.data });
            if (/^project_git_(branch_)?diff /.test(env.data)) set({ patchLoading: false });
          }
      }
    });

    return {
      projects: [],
      loaded: false,
      currentId: null,
      status: null,
      history: null,
      branches: null,
      worktrees: null,
      worktree: null,
      worktreeStatus: null,
      branch: null,
      branchChanges: null,
      target: null,
      patch: null,
      patchLoading: false,
      checkout: null,
      error: null,

      ensureLoaded() {
        if (!get().loaded) send("project_list", "");
      },
      select(projectId) {
        if (projectId === get().currentId) return;
        historyLimit = HISTORY_PAGE;
        set({
          currentId: projectId,
          status: null,
          history: null,
          branches: null,
          worktrees: null,
          worktree: null,
          worktreeStatus: null,
          branch: null,
          branchChanges: null,
          target: null,
          patch: null,
          checkout: null,
          error: null,
        });
        readAll();
      },
      refresh() {
        readAll();
      },
      openFile(target) {
        set({ patch: null });
        requestPatch(target);
      },
      viewBranch(branch) {
        set({ branch, branchChanges: null, target: null, patch: null });
        const id = get().currentId;
        if (branch && id) send("project_git_branch_changes", { projectId: id, branch });
      },
      viewWorktree(path) {
        set({ worktree: path, worktreeStatus: null, target: null, patch: null, branch: null, branchChanges: null });
        const id = get().currentId;
        if (path && id) send("project_git_worktree_status", { projectId: id, path });
      },
      loadMoreHistory() {
        const id = get().currentId;
        if (!id) return;
        historyLimit = Math.min(historyLimit + HISTORY_PAGE, 200);
        send("project_git_log", { projectId: id, limit: historyLimit });
      },
      checkoutBranch(branch, create = false) {
        const id = get().currentId;
        if (id) send("project_git_checkout", { projectId: id, branch, create });
      },
      saveProject(input) {
        send("project_save", input);
      },
      clearError() {
        set({ error: null });
      },
      dispose() {
        off();
      },
    };
  });
}
