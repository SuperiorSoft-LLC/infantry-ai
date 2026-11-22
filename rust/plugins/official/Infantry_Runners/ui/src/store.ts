// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The Runners store (RR-9). Every operation is the owner-only `runner_admin` frame, `{op: list|add|check|detach, ...}`,
// answered by one `runner_admin` frame `{ok, ...}` for the same request id; a refusal is `{ok: false, error}` and is shown
// as the daemon wrote it. The store keeps the list, the last check of each runner and the last refusal. The bearer is
// sent once and never kept in the store.
import { createStore, type StoreApi } from "zustand/vanilla";
import { useStore } from "zustand";
import { json, runnerRequest, type Envelope, type FrameLink } from "@infantry/ui-plugin-api";

export type RunnerWord = "online" | "offline" | "outdated" | "unknown";

export interface RunnerView {
  name: string;
  url: string;
  state: RunnerWord;
  /** `embedded` for the in-process `local` runner; absent for a paired one. */
  kind?: string;
  contract?: string | null;
  rttMs?: number | null;
  reason?: string | null;
  runnerId?: string | null;
}

/** What a runner's `providers` answer says about its machine: the agent tools that are there, and whether any can answer. */
export interface RunnerSummary {
  tools: string[];
  signedIn: boolean;
  /** Set when the runner could not be read (the refusal text). */
  error?: string;
}

interface ProviderRow {
  id: string;
  available?: boolean;
  unavailableReason?: string | null;
}

/** A tool counts as installed unless the runner says it is not (an unavailable one with another reason, such as not signed in, is installed). */
const missingReason = /not installed|not on path|not found|missing/i;

/** The summary of a `providers` answer; null when the answer is not a list. */
export function summarizeProviders(raw: unknown): RunnerSummary | null {
  if (!Array.isArray(raw)) return null;
  const rows = raw as ProviderRow[];
  const tools = rows.filter((h) => h.available !== false || !missingReason.test(h.unavailableReason ?? "")).map((h) => h.id);
  return { tools, signedIn: rows.some((h) => h.available !== false) };
}

/** `Tools: pi, claude · signed in: yes`. */
export function summaryLine(s: RunnerSummary): string {
  if (s.error) return `Tools: unknown (${s.error})`;
  return `Tools: ${s.tools.length ? s.tools.join(", ") : "none installed"} · signed in: ${s.signedIn ? "yes" : "no"}`;
}

export interface CheckRow {
  id: string;
  ok: boolean;
  detail: string;
  fix?: string;
}

export interface RunnersState {
  runners: RunnerView[];
  loaded: boolean;
  checks: Record<string, CheckRow[]>;
  /** RR-14: the one-line summary of each runner's machine, from its own `providers` answer (absent until read; error set when it could not be read). */
  summaries: Record<string, RunnerSummary>;
  /** The runner a check is running for. */
  checking: string | null;
  refusal: string | null;
  /** The last Add runner refusal, shown inside the dialog (cleared by the next `add`). */
  addError: string | null;
  adding: boolean;
}

export interface RunnersActions {
  refresh(): void;
  /** RR-14: reads [name]'s `providers` (through `runner_frame` for a paired runner) and keeps the summary line. */
  summarize(name: string): void;
  add(endpoint: string, bearer: string): void;
  check(name: string): void;
  detach(name: string): void;
  clearRefusal(): void;
  dispose(): void;
}

export type RunnersStore = StoreApi<RunnersState & RunnersActions>;

interface Answer {
  ok?: boolean;
  error?: string;
  runners?: RunnerView[];
  name?: string;
  rows?: CheckRow[];
}

/** The status the row shows: the dot, the word and the reason (an offline or outdated runner always carries one). */
export function statusOf(r: RunnerView): { dot: "done" | "needs-input" | "error" | "idle"; word: string; reason: string | null } {
  switch (r.state) {
    case "online":
      return { dot: "done", word: "online", reason: null };
    case "outdated":
      return { dot: "needs-input", word: "outdated", reason: r.reason ?? "this runner speaks another contract" };
    case "offline":
      return { dot: "error", word: "offline", reason: r.reason ?? "no answer" };
    default:
      return { dot: "idle", word: "not checked", reason: null };
  }
}

/** The contract and ping line: `contract 1.2 · 14 ms`. */
export function pingLine(r: RunnerView): string {
  const parts: string[] = [];
  if (r.contract) parts.push(`contract ${r.contract}`);
  if (typeof r.rttMs === "number") parts.push(`${r.rttMs} ms`);
  return parts.join(" · ");
}

export function createRunnersStore(link: FrameLink): RunnersStore {
  return createStore<RunnersState & RunnersActions>()((set, get) => {
    let counter = 0;
    /** What each in-flight request asked, by request id, so an answer is read for what was asked. */
    const asked = new Map<string, { op: string; name?: string }>();
    /** Sends one `runner_admin` frame; false (with a refusal) when the link is down. */
    const send = (op: string, body: Record<string, unknown>, name?: string): boolean => {
      counter += 1;
      const requestId = `runners:${Date.now().toString(36)}-${counter.toString(36)}`;
      asked.set(requestId, { op, name });
      if (link.sendFrame({ type: "runner_admin", data: JSON.stringify({ op, ...body }), requestId })) return true;
      asked.delete(requestId);
      set({ refusal: "runner_admin not sent: the daemon link is down" });
      return false;
    };
    const off = link.onFrame((env: Envelope) => {
      if (env.type === "ready") {
        if (get().loaded) get().refresh();
        return;
      }
      const ask = env.requestId ? asked.get(env.requestId) : undefined;
      if (env.requestId) asked.delete(env.requestId);
      if (env.type === "error" && (ask || env.data.startsWith("runner_admin"))) {
        set({ refusal: env.data, loaded: true, adding: false, checking: null });
        return;
      }
      if (env.type !== "runner_admin") return;
      const a = json<Answer>(env.data);
      if (!a) return;
      if (!a.ok) {
        const why = a.error ?? "refused";
        if (ask?.op === "add") set({ addError: why, adding: false });
        else set({ refusal: why, checking: null });
        return;
      }
      if (Array.isArray(a.runners)) {
        set({ runners: a.runners, loaded: true });
        return;
      }
      switch (ask?.op) {
        case "add":
          set({ adding: false, addError: null });
          get().refresh();
          return;
        case "check": {
          const name = a.name ?? ask.name;
          if (name) set({ checks: { ...get().checks, [name]: a.rows ?? [] }, checking: null });
          get().refresh();
          return;
        }
        case "detach":
          if (ask.name) {
            const rest = { ...get().checks };
            delete rest[ask.name];
            set({ checks: rest });
          }
          get().refresh();
      }
    });
    return {
      runners: [],
      loaded: false,
      summaries: {},
      checks: {},
      checking: null,
      refusal: null,
      addError: null,
      adding: false,
      refresh: () => void send("list", {}),
      summarize(name) {
        const keep = (v: RunnerSummary) => set({ summaries: { ...get().summaries, [name]: v } });
        runnerRequest(link, name, "providers", JSON.stringify({ refresh: true })).then(
          (a) => keep(summarizeProviders(json<unknown>(a.data)) ?? { tools: [], signedIn: false, error: "unreadable answer" }),
          (why: unknown) => keep({ tools: [], signedIn: false, error: String(why instanceof Error ? why.message : why).slice(0, 120) }),
        );
      },
      add(endpoint, bearer) {
        set({ adding: true, addError: null });
        if (!send("add", { endpoint: endpoint.trim(), bearer: bearer.trim() })) set({ adding: false });
      },
      check(name) {
        set({ checking: name });
        if (!send("check", { name }, name)) set({ checking: null });
      },
      detach: (name) => void send("detach", { name }, name),
      clearRefusal: () => set({ refusal: null }),
      dispose: off,
    };
  });
}

let bound: RunnersStore | null = null;
export const bindRunnersStore = (s: RunnersStore | null): void => {
  bound = s;
};
export const runnersStore = (): RunnersStore => {
  if (!bound) throw new Error("the Runners store is not bound (the plugin is not active)");
  return bound;
};
export function useRunners<T>(pick: (s: RunnersState & RunnersActions) => T): T {
  return useStore(runnersStore(), pick);
}
