// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The Run tab's store (RUST-SESS-2): saved run commands (`command_list` -> `commands`, pushed after every save/delete),
// the runs (`run_list` -> `runs`, refreshed on `run` / `run_finished`), the processes report (`processes`) and the
// console byte stream (`run_bytes`). Bytes never enter the store: a console subscribes with `onBytes` and writes them
// to its terminal. `runFeed` is the pure replay/live ordering rule the console applies. A branch command picks its
// branch from `run_branches` {project} -> {project, branches: [{name, worktree?}]}, kept per project.
import { createStore, type StoreApi } from "zustand/vanilla";
import { json, type Envelope } from "../wire";
import type { FrameLink } from "./link";

export interface RunCommand {
  id: string;
  name: string;
  cwd?: string | null;
  argv: string[];
  graceMillis?: number;
  project?: string | null;
  rooted?: boolean;
  detached?: boolean;
  /** The command names {worktree} or {branch}: each start names a branch. */
  branch?: boolean;
  secrets?: string[];
  renders?: string[];
}

export interface RunCommandDraft {
  id: string;
  name?: string;
  argv: string[];
  cwd?: string;
  project?: string;
  detached?: boolean;
}

export type RunState = "STARTING" | "RUNNING" | "FINISHED";

export interface RunSummary {
  runId: string;
  commandId: string;
  name?: string | null;
  state: RunState | string;
  startedAt: number;
  endedAt?: number | null;
  exitCode?: number | null;
  outcome?: string | null;
  terminal?: boolean;
  interactive?: boolean;
  branch?: string | null;
  pid?: number | null;
  lastSequence?: number;
}

/** One branch a branch command can start on (`run_branches`). */
export interface RunBranch {
  name: string;
  worktree?: string | null;
}

export interface RunFinished {
  runId: string;
  exitCode?: number | null;
  outcome: string;
  endedAt: number;
  detail?: string | null;
}

export interface RunBytes {
  runId: string;
  sequence: number;
  data: string;
  replay: boolean;
  droppedBytes?: number;
}

export interface TrackedProcess {
  pid: number;
  command: string;
  sessionId?: string | null;
  startedAt?: number | null;
  memoryMb?: number | null;
  rogue?: boolean;
  reason?: string | null;
  /** ROGUE-PROC-1: agent, run, test daemon, dev server, build, shell or child. */
  kind?: string | null;
  ticketId?: string | null;
  ageMs?: number | null;
  /** Average CPU % over the process's life. */
  cpuPercent?: number | null;
  /** True for a descendant found under a tracked pid (not started by the daemon directly). */
  inferred?: boolean;
  /** A shared build daemon: flagged, never killed by Clean up. */
  shared?: boolean;
}

export interface ProcessesReport {
  freeMb?: number | null;
  totalMb?: number | null;
  floorMb?: number;
  underPressure?: boolean;
  tracked: TrackedProcess[];
  unattributed: TrackedProcess[];
}

export interface ProcessesAnswer {
  report: ProcessesReport;
  reaped: unknown[];
  refused?: string | null;
}

export interface RunsState {
  commands: RunCommand[];
  runs: RunSummary[];
  processes: ProcessesAnswer | null;
  loaded: boolean;
  refusal: string | null;
  /** Branches by project id (`run_branches`). */
  branches: Record<string, RunBranch[]>;
}

export interface RunsActions {
  refresh(): void;
  saveCommand(d: RunCommandDraft): void;
  deleteCommand(id: string): void;
  /** [branch] is required for a branch command and refused for any other. */
  start(commandId: string, branch?: string): void;
  /** Asks `run_branches` for [project]'s branches. */
  listBranches(project: string): void;
  stop(runId: string, force?: boolean): void;
  attach(runId: string): void;
  input(runId: string, text: string): void;
  resize(runId: string, cols: number, rows: number): void;
  listProcesses(): void;
  reap(pid: number): void;
  reapRogue(): void;
  /** A console's byte tap: every `run_bytes` frame of [runId]. */
  onBytes(runId: string, listener: (b: RunBytes) => void): () => void;
  clearRefusal(): void;
  dispose(): void;
}

export type RunsStore = StoreApi<RunsState & RunsActions>;

/** The request types whose `error` answer is the Run tab's refusal. */
const OWN = ["command_", "run_", "processes"];

export function isRunActive(r: Pick<RunSummary, "state">): boolean {
  return r.state === "STARTING" || r.state === "RUNNING";
}

/** base64 -> bytes. */
export function decodeBase64(b64: string): Uint8Array {
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

/** UTF-8 text -> base64. */
export function encodeBase64(text: string): string {
  const bytes = new TextEncoder().encode(text);
  let bin = "";
  for (let i = 0; i < bytes.length; i += 0x8000) bin += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(bin);
}

export type FeedStep = { kind: "reset"; bytes: Uint8Array } | { kind: "write"; bytes: Uint8Array } | { kind: "skip" };

/**
 * The console's ordering rule: a replay resets the terminal and sets the floor; a live chunk at or below the floor is
 * already in the replay and is skipped. Live chunks before any replay are written (the attach answer may still come).
 */
export function runFeed(): { accept(b: RunBytes): FeedStep; floor(): number } {
  let floor = -1;
  return {
    accept(b) {
      if (b.replay) {
        floor = Math.max(floor, b.sequence);
        return { kind: "reset", bytes: decodeBase64(b.data) };
      }
      if (b.sequence <= floor) return { kind: "skip" };
      floor = b.sequence;
      return { kind: "write", bytes: decodeBase64(b.data) };
    },
    floor: () => floor,
  };
}

/** Folds a `run` or `run_finished` frame into the run list (newest first). */
export function foldRun(runs: RunSummary[], r: Partial<RunSummary> & { runId: string }): RunSummary[] {
  const i = runs.findIndex((x) => x.runId === r.runId);
  if (i < 0) return r.commandId ? [{ state: "STARTING", startedAt: Date.now(), ...r } as RunSummary, ...runs] : runs;
  const next = [...runs];
  next[i] = { ...next[i], ...r };
  return next;
}

export function createRunsStore(link: FrameLink): RunsStore {
  let counter = 0;
  const inflight = new Set<string>();
  const taps = new Map<string, Set<(b: RunBytes) => void>>();

  return createStore<RunsState & RunsActions>()((set, get) => {
    const send = (type: string, body: unknown): void => {
      counter += 1;
      const requestId = `run:${Date.now().toString(36)}-${counter.toString(36)}`;
      inflight.add(requestId);
      const data = typeof body === "string" ? body : JSON.stringify(body);
      if (!link.sendFrame({ type, data, requestId })) {
        inflight.delete(requestId);
        set({ refusal: `${type} not sent: the daemon link is down` });
      }
    };
    const listRuns = (): void => send("run_list", "");

    const off = link.onFrame((env: Envelope) => {
      const mine = !!env.requestId && inflight.delete(env.requestId);
      switch (env.type) {
        case "ready":
          if (get().loaded) get().refresh();
          return;
        case "commands": {
          const list = json<RunCommand[]>(env.data);
          if (Array.isArray(list)) set({ commands: list, loaded: true });
          return;
        }
        case "run_branches": {
          const b = json<{ project?: string; branches?: RunBranch[] }>(env.data);
          if (b?.project && Array.isArray(b.branches)) set({ branches: { ...get().branches, [b.project]: b.branches } });
          return;
        }
        case "runs": {
          const list = json<RunSummary[]>(env.data);
          if (Array.isArray(list)) set({ runs: list });
          return;
        }
        case "run": {
          const r = json<RunSummary>(env.data);
          if (r?.runId) set({ runs: foldRun(get().runs, r) });
          listRuns();
          return;
        }
        case "run_finished": {
          const f = json<RunFinished>(env.data);
          if (f?.runId) set({ runs: foldRun(get().runs, { runId: f.runId, state: "FINISHED", exitCode: f.exitCode, outcome: f.outcome, endedAt: f.endedAt }) });
          listRuns();
          return;
        }
        case "run_bytes": {
          const b = json<RunBytes>(env.data);
          if (b?.runId) taps.get(b.runId)?.forEach((t) => t(b));
          return;
        }
        case "processes": {
          const p = json<ProcessesAnswer>(env.data);
          if (p?.report) set({ processes: p, ...(p.refused ? { refusal: p.refused } : {}) });
          return;
        }
        case "error":
          if (mine || OWN.some((p) => env.data.startsWith(p))) set({ refusal: env.data });
      }
    });

    return {
      commands: [],
      runs: [],
      processes: null,
      loaded: false,
      refusal: null,
      branches: {},

      refresh() {
        send("command_list", "");
        listRuns();
      },
      saveCommand(d) {
        const body: Record<string, unknown> = { id: d.id, argv: d.argv };
        if (d.name) body.name = d.name;
        if (d.cwd) body.cwd = d.cwd;
        if (d.project) body.project = d.project;
        if (d.detached !== undefined) body.detached = d.detached;
        send("command_save", body);
      },
      deleteCommand: (id) => send("command_delete", { id }),
      listBranches: (project) => send("run_branches", { project }),
      start: (commandId, branch) => send("run_start", branch ? { commandId, branch } : { commandId }),
      stop: (runId, force) => send("run_stop", force ? `${runId} force` : runId),
      attach: (runId) => send("run_attach", { runId }),
      input: (runId, text) => void link.sendFrame({ type: "run_input", data: JSON.stringify({ runId, data: encodeBase64(text) }) }),
      resize: (runId, cols, rows) => void link.sendFrame({ type: "run_resize", data: JSON.stringify({ runId, cols, rows }) }),
      listProcesses: () => send("processes", { op: "list" }),
      reap: (pid) => send("processes_reap", { op: "reap", pid }),
      reapRogue: () => send("processes_reap", { op: "reap_rogue" }),
      onBytes(runId, listener) {
        let s = taps.get(runId);
        if (!s) taps.set(runId, (s = new Set()));
        s.add(listener);
        return () => {
          s.delete(listener);
          if (s.size === 0) taps.delete(runId);
        };
      },
      clearRefusal: () => set({ refusal: null }),
      dispose() {
        off();
        taps.clear();
        inflight.clear();
      },
    };
  });
}
