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
  /** The reason in plain words (the list row shows this; `reason` keeps the raw error for the Details panel). */
  reasonPlain?: string | null;
  runnerId?: string | null;
  /** RR-11: the oldest contract the app drives, whether this runner is older, the app it runs, and its Update runner progress. */
  needs?: string;
  outdated?: boolean;
  appVersion?: string;
  update?: { state: "running" | "done" | "failed"; steps: string[]; detail: string };
}

/** What a runner's `providers` answer says about its machine: the agent tools that are there, and whether any can answer. */
export interface RunnerSummary {
  tools: string[];
  signedIn: boolean;
  /** Set when the runner could not be read (the refusal text). */
  error?: string;
  /** RR-VIS: the tools are known (install status answered) but `providers` was refused: an old runner that cannot say who is signed in. */
  signedInUnknown?: boolean;
}

/** RR-VIS: one harness of a runner, the install and the providers answers joined by id: the only source of "signed in" in the panel. */
export interface HarnessRow {
  id: string;
  installed: boolean;
  version: string | null;
  path: string | null;
  /** null = unknown (providers not readable and the runner's hello did not say). */
  signedIn: boolean | null;
  /** Why it is not signed in (the harness's own words, never a terminal dump). */
  why: string | null;
  /** Where the login lives, as the harness reported it. */
  loginAt: string | null;
  /** The harness's auth kinds as it reported them; exactly `["api-key"]` = a key-only harness (no sign-in). */
  authKinds: string[];
  /** Key-only harness: the variable that carries its key (name only) and whether it is set on the runner. */
  keyEnv: string | null;
  keySet: boolean;
}

/** One setup step: which runner, what ran, its output (trimmed), the exit code and the reason in plain words. */
export interface LogStep {
  at: number;
  runner: string;
  step: string;
  command: string;
  output: string;
  exit: number | null;
  reason: string;
}

/** Everything the Details dialog shows about one runner (the `runner_admin detail` answer, plus the harness join). */
export interface RunnerDetail {
  runner?: { name: string; runnerId?: string; mode?: string; os?: string; appVersion?: string | null; contract?: string; needs?: string };
  link?: { url: string; fingerprint?: string; pairedAt?: number; pairedFrom?: string; lastPingAt?: number; rttMs?: number; state: string; reason?: string; reasonPlain?: string; fix?: string };
  helloHarnesses?: { id: string; loggedIn: boolean }[];
  lastCheck?: { at: number; rows: CheckRow[] } | null;
  update?: { state: string; steps: string[]; detail: string };
  harnesses?: HarnessRow[];
  /** RR-VIS: the setup log of this runner: each check, install and update step. */
  log?: LogStep[];
  /** RR-VIS: the runner daemon's own log tail, or why it could not be read. */
  runnerLog?: string[];
  runnerLogError?: string;
  /** Set when the harness answers could not be read (plain words). */
  harnessError?: string;
}

/** The text before a raw terminal dump: a refusal like `Claude is not signed in. Screen: ...` shows only its first part. */
export function plainText(s: string | null | undefined): string | null {
  if (!s) return null;
  return s.split(/\s+Screen:/)[0].trim() || null;
}

/** Joins `harness_install_status` and `providers` (and the hello's login flag as a fallback) into one row per harness. */
export function joinHarnesses(install: unknown, providers: unknown, hello: { id: string; loggedIn: boolean }[] = []): HarnessRow[] {
  const inst = Array.isArray((install as { harnesses?: unknown } | null)?.harnesses) ? ((install as { harnesses: (InstallRow & { version?: string | null })[] }).harnesses) : [];
  const prov = Array.isArray(providers) ? (providers as (ProviderRow & { unavailableReason?: string | null; loginAt?: string | null; authKinds?: string[]; keyEnv?: string | null; keySet?: boolean })[]) : null;
  const ids = [...new Set([...inst.map((h) => h.id), ...(prov ?? []).map((p) => p.id), ...hello.map((h) => h.id)])];
  return ids.map((id) => {
    const i = inst.find((h) => h.id === id);
    const p = prov?.find((x) => x.id === id);
    const h = hello.find((x) => x.id === id);
    const installed = !!i && !!i.path && i.state !== "missing";
    const signedIn = installed ? (p ? p.available !== false : h ? h.loggedIn : null) : false;
    return { id, installed, version: i?.installedVersion ?? i?.version ?? null, path: i?.path ?? null, signedIn, why: signedIn === false && installed ? plainText(p?.unavailableReason) ?? "not signed in" : null, loginAt: p?.loginAt ?? null, authKinds: p?.authKinds ?? [], keyEnv: p?.keyEnv ?? null, keySet: p?.keySet === true };
  });
}

interface ProviderRow {
  id: string;
  available?: boolean;
}

/** One harness of a `harness_install_status` answer: installed when it has a path and is not missing (the wizard's own test). */
interface InstallRow {
  id: string;
  state?: string;
  path?: string | null;
  /** What `harness_install_status` sends: the CLI's `--version` text (null when it could not be read). */
  installedVersion?: string | null;
}

/**
 * The summary of a runner's two answers: the tools that are installed there (`harness_install_status`: a `providers` row alone cannot
 * tell "not installed" from "not signed in") and whether any harness can answer (`providers`: `available`). Null when either is not a list.
 */
export function summarizeProviders(providers: unknown, install: unknown): RunnerSummary | null {
  const rows = (install as { harnesses?: unknown } | null)?.harnesses;
  if (!Array.isArray(providers) || !Array.isArray(rows)) return null;
  const tools = (rows as InstallRow[]).filter((h) => !!h.path && h.state !== "missing").map((h) => h.id);
  return { tools, signedIn: (providers as ProviderRow[]).some((h) => h.available !== false && tools.includes(h.id)) };
}

/** A key-only harness (opencode and the like) has no sign-in: only an API key. */
export function keyOnly(h: Pick<HarnessRow, "authKinds">): boolean {
  return h.authKinds.length === 1 && h.authKinds[0] === "api-key";
}

/** `API key: DEEPSEEK_API_KEY (found in the runner's environment)`; never a value. */
export function keyLine(h: Pick<HarnessRow, "keyEnv" | "keySet">): string {
  return `${h.keyEnv ?? "its API key variable"} (${h.keySet ? "found in the runner's environment" : "not set"})`;
}

/** `Tools: pi, claude · signed in: yes`. */
export function summaryLine(s: RunnerSummary): string {
  if (s.error) return `Tools: not readable: ${s.error}`;
  if (s.signedInUnknown) return `Tools: ${s.tools.length ? s.tools.join(", ") : "none installed"} · signed in: unknown (this runner is too old to report it: Update runner)`;
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
  /** RR-14: the one-line summary of each runner's machine, from its own `harness_install_status` and `providers` answers (absent until read; error set when it could not be read). */
  summaries: Record<string, RunnerSummary>;
  /** The runner a check is running for. */
  checking: string | null;
  refusal: string | null;
  /** The last Add runner refusal, shown inside the dialog (cleared by the next `add`). */
  addError: string | null;
  adding: boolean;
  /** RR-11: what the last Update runner answered (progress, or the command to run on another machine). */
  updateNote: Record<string, string>;
  /** RR-VIS: the Details panel data of each runner opened. */
  details: Record<string, RunnerDetail>;
}

export interface RunnersActions {
  /** RR-VIS: reads one runner's connection detail (`runner_admin detail`) and its harness rows. */
  detail(name: string): void;
  refresh(): void;
  /** RR-14: reads [name]'s `providers` (through `runner_frame` for a paired runner) and keeps the summary line. */
  summarize(name: string): void;
  add(endpoint: string, bearer: string): void;
  check(name: string): void;
  /** RR-11: Update runner. Answered at once; the rows then carry the progress, so the list is re-read until it ends. */
  update(name: string): void;
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
  text?: string;
  link?: RunnerDetail["link"];
  runner?: RunnerDetail["runner"];
  helloHarnesses?: RunnerDetail["helloHarnesses"];
  lastCheck?: RunnerDetail["lastCheck"];
  update?: RunnerDetail["update"];
  log?: RunnerDetail["log"];
  runnerLog?: string[];
  runnerLogError?: string;
}

/** The status the row shows: the dot, the word and the reason (an offline or outdated runner always carries one). */
export function statusOf(r: RunnerView): { dot: "done" | "needs-input" | "error" | "idle"; word: string; reason: string | null } {
  switch (r.state) {
    case "online":
      return { dot: "done", word: "online", reason: null };
    case "outdated":
      return { dot: "needs-input", word: "outdated", reason: r.reasonPlain ?? r.reason ?? "this runner speaks another contract" };
    case "offline":
      return { dot: "error", word: "offline", reason: r.reasonPlain ?? r.reason ?? "no answer" };
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
    /** While any runner is updating, re-read the list every 2 s. */
    const schedulePoll = () => {
      if (!get().runners.some((r) => r.update?.state === "running")) return;
      setTimeout(() => {
        get().refresh();
        schedulePoll();
      }, 2000);
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
      if (ask?.op === "detail" && ask.name) {
        const { ok: _ok, ...rest } = a as Answer & { ok?: boolean };
        set({ details: { ...get().details, [ask.name]: { ...get().details[ask.name], ...(rest as RunnerDetail) } } });
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
        case "update":
          if (a.text) set({ updateNote: { ...get().updateNote, [ask.name ?? ""]: a.text } });
          get().refresh();
          schedulePoll();
          return;
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
      updateNote: {},
      details: {},
      detail(name) {
        send("detail", { name }, name);
        const keep = (d: Partial<RunnerDetail>) => set({ details: { ...get().details, [name]: { ...get().details[name], ...d } } });
        Promise.allSettled([runnerRequest(link, name, "providers", JSON.stringify({ refresh: true })), runnerRequest(link, name, "harness_install_status", "{}")]).then(([p, i]) => {
          if (p.status === "rejected" && i.status === "rejected") {
            keep({ harnesses: [], harnessError: String(i.reason instanceof Error ? i.reason.message : i.reason).slice(0, 160) });
            return;
          }
          const hello = get().details[name]?.helloHarnesses ?? [];
          keep({ harnesses: joinHarnesses(i.status === "fulfilled" ? json<unknown>(i.value.data) : null, p.status === "fulfilled" ? json<unknown>(p.value.data) : null, hello), harnessError: undefined });
        });
      },
      refresh: () => void send("list", {}),
      summarize(name) {
        const keep = (v: RunnerSummary) => set({ summaries: { ...get().summaries, [name]: v } });
        const why = (r: PromiseRejectedResult) => String(r.reason instanceof Error ? r.reason.message : r.reason).slice(0, 120);
        Promise.allSettled([runnerRequest(link, name, "providers", JSON.stringify({ refresh: true })), runnerRequest(link, name, "harness_install_status", "{}")]).then(([p, i]) => {
          if (p.status === "fulfilled" && i.status === "fulfilled") return keep(summarizeProviders(json<unknown>(p.value.data), json<unknown>(i.value.data)) ?? { tools: [], signedIn: false, error: "the runner's answer could not be read" });
          // The install list still names the tools when only `providers` is refused (an older runner): say what is known and why the rest is not.
          if (i.status === "fulfilled") {
            const rows = json<{ harnesses?: InstallRow[] }>(i.value.data)?.harnesses;
            if (Array.isArray(rows)) return keep({ tools: rows.filter((h) => !!h.path && h.state !== "missing").map((h) => h.id), signedIn: false, signedInUnknown: true });
          }
          keep({ tools: [], signedIn: false, error: p.status === "rejected" ? why(p) : i.status === "rejected" ? why(i) : "unreadable answer" });
        });
      },
      add(endpoint, bearer) {
        set({ adding: true, addError: null });
        if (!send("add", { endpoint: endpoint.trim(), bearer: bearer.trim() })) set({ adding: false });
      },
      check(name) {
        set({ checking: name });
        if (!send("check", { name }, name)) set({ checking: null });
      },
      update: (name) => void send("update", { name }, name),
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
