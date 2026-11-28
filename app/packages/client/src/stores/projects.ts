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
  laneCaps?: Record<string, number>;
  autoRemoveDoneWorkers?: boolean;
  experiments?: Record<string, string>;
  /** Per-project data; `icon.color`, `icon.text` and `icon.image` hold the project icon. */
  data?: Record<string, string>;
}

// ---- project icons ------------------------------------------------------------------------------

export const ICON_KEYS = { color: "icon.color", text: "icon.text", image: "icon.image" } as const;
/** The daemon's icon palette (it stores one per new project); the fallback for a project saved before icons. */
export const ICON_PALETTE = ["#2563eb", "#7c3aed", "#db2777", "#dc2626", "#ea580c", "#ca8a04", "#16a34a", "#0d9488", "#0891b2", "#4f46e5", "#9333ea", "#475569"];
/** The daemon's per-value limit on project data: an uploaded image must fit in it. */
export const ICON_IMAGE_MAX_CHARS = 4096;

/** 1-2 letters for a name: "Infantry AI Site" -> "IS", "t51" -> "T5", "x" -> "X". */
export function projectInitials(name: string): string {
  const words = name.trim().split(/[\s_\-.]+/).filter((w) => /[\p{L}\p{N}]/u.test(w));
  if (!words.length) return "?";
  const first = (w: string) => [...w.replace(/[^\p{L}\p{N}]/gu, "")][0] ?? "";
  if (words.length === 1) return [...words[0].replace(/[^\p{L}\p{N}]/gu, "")].slice(0, 2).join("").toUpperCase();
  return (first(words[0]) + first(words[words.length - 1])).toUpperCase();
}

/** Same hash as the daemon's `icon_color_for`, so an old project without a stored color is stable everywhere. */
export function paletteColorFor(id: string): string {
  let h = 2166136261;
  for (const b of new TextEncoder().encode(id)) h = Math.imul(h ^ b, 16777619) >>> 0;
  return ICON_PALETTE[h % ICON_PALETTE.length];
}

export interface ProjectIcon {
  text: string;
  color: string;
  image: string | null;
}

export function projectIcon(p: Pick<ProjectInfo, "id" | "name" | "data">): ProjectIcon {
  const d = p.data ?? {};
  const color = /^#[0-9a-fA-F]{6}$/.test(d[ICON_KEYS.color] ?? "") ? d[ICON_KEYS.color] : paletteColorFor(p.id);
  const image = (d[ICON_KEYS.image] ?? "").startsWith("data:image/") ? d[ICON_KEYS.image] : null;
  return { text: (d[ICON_KEYS.text] ?? "").trim() || projectInitials(p.name), color, image };
}

/** The Delete button is enabled only for exactly "DELETE". */
export function deleteConfirmed(typed: string): boolean {
  return typed === "DELETE";
}

export interface ProjectEdit {
  name: string;
  /** Blank = the initials of the name. */
  iconText: string;
  iconColor: string;
  /** A data URL, or null for letters. */
  iconImage: string | null;
  autoRemoveDoneWorkers?: boolean;
  laneCaps?: Record<string, number>;
}

/** The frames one Edit save sends: `project_save` for the name, `project_config` for icon and settings. */
export function editFrames(p: ProjectInfo, e: ProjectEdit): { type: string; body: Record<string, unknown> }[] {
  const out: { type: string; body: Record<string, unknown> }[] = [];
  const name = e.name.trim();
  if (name && name !== p.name) out.push({ type: "project_save", body: { id: p.id, name } });
  const d = p.data ?? {};
  const set: Record<string, string> = {};
  const unset: string[] = [];
  const want = (k: string, v: string | null) => {
    const cur = d[k] ?? "";
    if ((v ?? "") === cur) return;
    if (v) set[k] = v;
    else if (k in d) unset.push(k);
  };
  want(ICON_KEYS.color, e.iconColor);
  want(ICON_KEYS.text, e.iconText.trim() || null);
  want(ICON_KEYS.image, e.iconImage);
  const cfg: Record<string, unknown> = {};
  if (Object.keys(set).length) cfg.data = set;
  if (unset.length) cfg.unsetData = unset;
  if (e.autoRemoveDoneWorkers !== undefined && e.autoRemoveDoneWorkers !== !!p.autoRemoveDoneWorkers) cfg.autoRemoveDoneWorkers = e.autoRemoveDoneWorkers;
  if (e.laneCaps && JSON.stringify(e.laneCaps) !== JSON.stringify(p.laneCaps ?? {})) cfg.laneCaps = e.laneCaps;
  if (Object.keys(cfg).length) out.push({ type: "project_config", body: { projectId: p.id, ...cfg } });
  return out;
}

/** The `project_base` frame body for a picked base: a branch name, or null for "clear (use default)". */
export function baseFrame(projectId: string, branch: string | null): { type: string; body: Record<string, unknown> } {
  return { type: "project_base", body: branch ? { projectId, branch } : { projectId, clear: true } };
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
  /** Branch lists by project (any project, not only the current one), for the base-branch picker. */
  branchLists: Record<string, GitBranchList>;
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
  /** Name, icon and settings in one go (see `editFrames`); every client gets the changed project back. */
  editProject(projectId: string, edit: ProjectEdit): void;
  /** `project_delete`; the daemon removes it from Infantry AI (never the folder on disk). */
  deleteProject(projectId: string): void;
  /** `project_git_branches` for one project; the answer lands in `branchLists`. */
  loadBranches(projectId: string): void;
  /** `project_base`: set the base new tickets fork from, or null to clear it (daemon default). */
  setBaseBranch(projectId: string, branch: string | null): void;
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
        case "project_deleted": {
          const gone = json<ProjectInfo>(env.data);
          if (!gone) return;
          const rest = get().projects.filter((x) => x.id !== gone.id);
          set({ projects: rest });
          if (get().currentId === gone.id) {
            if (rest[0]) get().select(rest[0].id);
            else set({ currentId: null, status: null, history: null, branches: null, worktrees: null });
          }
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
          if (b?.projectId) set({ branchLists: { ...get().branchLists, [b.projectId]: b } });
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
      branchLists: {},
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
      editProject(projectId, edit) {
        const p = get().projects.find((x) => x.id === projectId);
        if (!p) return;
        for (const f of editFrames(p, edit)) send(f.type, f.body);
      },
      deleteProject(projectId) {
        send("project_delete", { projectId });
      },
      loadBranches(projectId) {
        send("project_git_branches", { projectId });
      },
      setBaseBranch(projectId, branch) {
        const f = baseFrame(projectId, branch);
        send(f.type, f.body);
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
