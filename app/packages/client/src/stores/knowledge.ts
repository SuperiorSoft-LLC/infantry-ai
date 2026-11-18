// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The project's knowledge: memory notes, decisions and issues of one project, fed by the daemon's frames. Notes are
// listed per scope (project, global, role, session); a save shows at once and the daemon's `memory_note` answer
// replaces it; a refused request rolls back and keeps the refusal for the view. Decisions and issues are append-only
// on the daemon, so they show when the daemon pushes `decision_item` / `issue_item` to every client.
import { createStore, type StoreApi } from "zustand/vanilla";
import { Frame, json } from "../wire";
import { requester, type FrameLink } from "./link";

export type MemoryScope = "project" | "global" | "role" | "session";
export type KnowledgeKind = "notes" | "decisions" | "issues";

export interface MemoryNote {
  id?: string;
  projectId: string;
  name: string;
  folder: string;
  description: string;
  body: string;
  updatedAt?: number;
  scope?: string;
  ownerId?: string | null;
}

export interface MemoryHit {
  name: string;
  description: string;
  snippet: string;
}

export interface Decision {
  projectId: string;
  number: number;
  ref: string;
  summary: string;
  body: string;
  supersedes?: string | null;
  supersededBy?: string | null;
  authorSessionId?: string | null;
  authorAgentId?: string | null;
  authorRole?: string | null;
  createdAt: number;
}

export interface IssueEvent {
  seq: number;
  kind: "attempt" | "fix";
  summary: string;
  outcome?: string | null;
  location?: string | null;
  sessionId?: string | null;
  agentId?: string | null;
  createdAt: number;
}

export interface Issue {
  projectId: string;
  number: number;
  ref: string;
  summary: string;
  location: string;
  ticketId?: string | null;
  implicit: boolean;
  status: "open" | "fixed";
  openedBySession?: string | null;
  openedByAgent?: string | null;
  createdAt: number;
  events: IssueEvent[];
}

export interface NoteDraft {
  name: string;
  description: string;
  body: string;
  folder?: string;
}

export interface KnowledgeState {
  projectId: string | null;
  scope: MemoryScope;
  kind: KnowledgeKind;
  query: string;
  notes: MemoryNote[];
  /** Search hits for [query]; null = no search running (the full list shows). */
  hits: MemoryHit[] | null;
  /** The note open in the viewer (full body from `memory_get`). */
  open: MemoryNote | null;
  decisions: Decision[];
  issues: Issue[];
  loaded: boolean;
  refusal: string | null;
}

export interface KnowledgeActions {
  /** Bind to [projectId] and read its notes, decisions and issues (no-op when already bound). */
  setProject(projectId: string | null): void;
  setScope(scope: MemoryScope): void;
  setKind(kind: KnowledgeKind): void;
  /** Search notes; an empty query clears the hits. */
  search(query: string): void;
  refresh(): void;
  openNote(name: string): void;
  closeNote(): void;
  saveNote(draft: NoteDraft): void;
  deleteNote(name: string): void;
  addDecision(summary: string, body?: string, supersedes?: string): void;
  openIssue(summary: string, location: string, ticketId?: string): void;
  issueAttempt(issue: string, summary: string, outcome?: string): void;
  fixIssue(issue: string, summary: string): void;
  clearRefusal(): void;
  dispose(): void;
}

export type KnowledgeStore = StoreApi<KnowledgeState & KnowledgeActions>;

/** Notes grouped by folder, the root ("") first, then folders by name. */
export function notesByFolder(notes: MemoryNote[]): { folder: string; notes: MemoryNote[] }[] {
  const groups = new Map<string, MemoryNote[]>();
  for (const n of notes) groups.set(n.folder ?? "", [...(groups.get(n.folder ?? "") ?? []), n]);
  return [...groups.entries()].sort(([a], [b]) => (a === "" ? -1 : b === "" ? 1 : a.localeCompare(b))).map(([folder, list]) => ({ folder, notes: list }));
}

/** Newest decision first. */
export function decisionTimeline(decisions: Decision[]): Decision[] {
  return [...decisions].sort((a, b) => b.number - a.number);
}

function upsert<T>(list: T[], item: T, key: (t: T) => string | number): T[] {
  const k = key(item);
  const at = list.findIndex((x) => key(x) === k);
  if (at < 0) return [...list, item];
  const next = [...list];
  next[at] = item;
  return next;
}

export function createKnowledgeStore(link: FrameLink): KnowledgeStore {
  return createStore<KnowledgeState & KnowledgeActions>()((set, get) => {
    const req = requester(link, "know", (why) => set({ refusal: why }));
    /** The notes to restore when a request with this id is refused. */
    const restore = new Map<string, MemoryNote[]>();
    const send = (type: string, data: object, before?: MemoryNote[]): void => {
      if (req.send(type, JSON.stringify(data)) && before) restore.set(type, before);
    };
    const base = (): { projectId: string; scope?: string } | null => {
      const { projectId, scope } = get();
      if (!projectId) return null;
      return scope === "project" ? { projectId } : { projectId, scope };
    };
    const load = (): void => {
      const b = base();
      if (!b) return;
      send("memory_list", b);
      send("decision_list", { projectId: b.projectId, includeSuperseded: true });
      send("issue_list", { projectId: b.projectId, includeFixed: true });
    };
    const mineProject = (p?: string): boolean => !p || p === get().projectId;

    const off = link.onFrame((env) => {
      const mine = req.settle(env);
      switch (env.type) {
        case "memory_notes": {
          const list = json<MemoryNote[]>(env.data);
          if (list && (mine || list.every((n) => mineProject(n.projectId)))) set({ notes: list, loaded: true });
          if (mine) restore.clear();
          return;
        }
        case "memory_note": {
          const n = json<MemoryNote>(env.data);
          if (!n?.name || !mineProject(n.projectId)) return;
          set({ notes: upsert(get().notes, n, (x) => x.name).sort((a, b) => a.name.localeCompare(b.name)) });
          if (get().open?.name === n.name) set({ open: n });
          if (mine) restore.clear();
          return;
        }
        case "memory_hits":
          if (mine) set({ hits: json<MemoryHit[]>(env.data) ?? [] });
          return;
        case "decisions": {
          const list = json<Decision[]>(env.data);
          if (list && mine) set({ decisions: list });
          return;
        }
        case "decision_item": {
          const d = json<Decision>(env.data);
          if (!d?.ref || !mineProject(d.projectId)) return;
          let decisions = upsert(get().decisions, d, (x) => x.ref);
          // A new decision that supersedes an older one: mark the older one at once.
          if (d.supersedes) decisions = decisions.map((x) => (x.ref === d.supersedes && !x.supersededBy ? { ...x, supersededBy: d.ref } : x));
          set({ decisions });
          return;
        }
        case "issues": {
          const list = json<Issue[]>(env.data);
          if (list && mine) set({ issues: list });
          return;
        }
        case "issue_item": {
          const i = json<Issue>(env.data);
          if (i?.ref && mineProject(i.projectId)) set({ issues: upsert(get().issues, i, (x) => x.ref) });
          return;
        }
        case Frame.ERROR: {
          if (!mine) return;
          const type = env.data.split(" ")[0];
          const before = restore.get(type);
          restore.delete(type);
          set(before ? { notes: before, refusal: env.data } : { refusal: env.data });
          return;
        }
      }
    });

    return {
      projectId: null,
      scope: "project",
      kind: "notes",
      query: "",
      notes: [],
      hits: null,
      open: null,
      decisions: [],
      issues: [],
      loaded: false,
      refusal: null,

      setProject(projectId) {
        if (projectId === get().projectId) return;
        set({ projectId, notes: [], hits: null, open: null, decisions: [], issues: [], loaded: false, query: "" });
        load();
      },
      setScope(scope) {
        if (scope === get().scope) return;
        set({ scope, notes: [], hits: null, open: null });
        const b = base();
        if (b) send("memory_list", b);
      },
      setKind(kind) {
        set({ kind });
      },
      search(query) {
        set({ query });
        const b = base();
        if (!query.trim()) return set({ hits: null });
        if (b) send("memory_search", { ...b, query: query.trim() });
      },
      refresh() {
        load();
      },
      openNote(name) {
        const b = base();
        const known = get().notes.find((n) => n.name === name);
        set({ open: known ?? { projectId: get().projectId ?? "", name, folder: "", description: "", body: "" } });
        if (b) send("memory_get", { ...b, name });
      },
      closeNote() {
        set({ open: null });
      },
      saveNote(draft) {
        const b = base();
        if (!b) return set({ refusal: "memory_save not sent: pick a project first" });
        const before = get().notes;
        const old = before.find((n) => n.name === draft.name);
        const optimistic: MemoryNote = { ...(old ?? {}), projectId: b.projectId, folder: draft.folder ?? old?.folder ?? "", name: draft.name, description: draft.description, body: draft.body };
        set({ notes: upsert(before, optimistic, (x) => x.name).sort((a, c) => a.name.localeCompare(c.name)), open: optimistic });
        send("memory_save", { ...b, ...draft }, before);
      },
      deleteNote(name) {
        const b = base();
        if (!b) return;
        const before = get().notes;
        set({ notes: before.filter((n) => n.name !== name), open: get().open?.name === name ? null : get().open });
        send("memory_delete", { ...b, name }, before);
      },
      addDecision(summary, body, supersedes) {
        const projectId = get().projectId;
        if (!projectId) return set({ refusal: "decision_add not sent: pick a project first" });
        send("decision_add", { projectId, summary, ...(body ? { body } : {}), ...(supersedes ? { supersedes } : {}) });
      },
      openIssue(summary, location, ticketId) {
        const projectId = get().projectId;
        if (!projectId) return set({ refusal: "issue_open not sent: pick a project first" });
        send("issue_open", { projectId, summary, location, ...(ticketId ? { ticketId } : {}) });
      },
      issueAttempt(issue, summary, outcome) {
        const projectId = get().projectId;
        if (projectId) send("issue_attempt", { projectId, issue, summary, ...(outcome ? { outcome } : {}) });
      },
      fixIssue(issue, summary) {
        const projectId = get().projectId;
        if (projectId) send("issue_fix", { projectId, issue, summary });
      },
      clearRefusal() {
        set({ refusal: null });
      },
      dispose() {
        off();
      },
    };
  });
}
