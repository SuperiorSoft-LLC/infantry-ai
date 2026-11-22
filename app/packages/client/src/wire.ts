// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The daemon wire, hand-written to match the Kotlin DTOs (superiorSoftLibrary/infantryUiCore/.../protocol/WireProtocol.kt and
// infantryProtocol). Only what this client reads is typed; unknown keys are ignored, as the Kotlin decoder does.
// ts-rs generates these from the Rust daemon later (docs/rust-port/PLAN.md).

/** Who caused a turn. Absent = the owner (the UI). */
export type MessageOrigin =
  | { type: "agent"; sessionId?: string; agentId?: string; role?: string }
  | { type: "external"; senderLabel?: string; channel?: string }
  | { type: "system"; kind?: string };

/** Every frame, both ways. `data` is a string; for most frames it is JSON-encoded. */
export interface Envelope {
  type: string;
  data: string;
  sessionId?: string | null;
  sequence?: number | null;
  requestId?: string;
  origin?: MessageOrigin;
}

/** Frame types (Envelope companion constants in WireProtocol.kt). */
export const Frame = {
  READY: "ready",
  POLICY: "policy",
  PROMPT: "prompt",
  STDOUT: "stdout",
  STDERR: "stderr",
  EXIT: "exit",
  ERROR: "error",
  SESSION: "session",
  SESSIONS: "sessions",
  KILL: "kill",
  INTERRUPT: "interrupt",
  QUEUE_ADD: "queue_add",
  QUEUE_REMOVE: "queue_remove",
  QUEUE_FORCE: "queue_force",
  PROMPT_ACK: "prompt_ack",
  NEW: "new",
  ASSISTANT: "assistant",
  TEXT_DELTA: "text_delta",
  STATUS: "status",
  FINISHED: "finished",
  TURN: "turn",
  REASONING: "reasoning",
  REPLAY_REQUEST: "replay_request",
  REPLAY_GAP: "replay_gap",
  USAGE: "usage",
  AGENT_LIST: "agent_list",
  AGENTS: "agents",
  AGENT: "agent",
  TICKET_LIST: "ticket_list",
  TICKET_GET: "ticket_get",
  TICKETS: "tickets",
  TICKET: "ticket",
  PROJECT_LIST: "project_list",
  PROJECTS: "projects",
  PROJECT: "project",
} as const;

/** The `requestId` prefix of a client message id (CHAT-SENDING-1); the daemon dedupes on it. */
export const CLIENT_SEND_PREFIX = "send:";
/** The typed refusal of a prompt into a session with a run in flight (1.96.0): `run_in_flight <sessionId>: <reason>`. */
export const RUN_IN_FLIGHT = "run_in_flight";

/** Frames that belong to one session's sequenced stream (each carries a per-session `sequence`). */
export const STREAM_FRAMES: ReadonlySet<string> = new Set([
  Frame.SESSION, Frame.STATUS, Frame.TURN, Frame.TEXT_DELTA, Frame.REASONING, Frame.STDOUT, Frame.STDERR,
  Frame.ERROR, Frame.FINISHED, Frame.EXIT, Frame.USAGE,
]);

// Session records come from the Rust protocol crate (ts-rs, `cargo test -p infantry-protocol` writes app/src/generated).
import type { SessionRecord as WireSessionRecord } from "../../../src/generated/SessionRecord";
import type { SessionState as WireSessionState } from "../../../src/generated/SessionState";
import type { KillOutcome } from "../../../src/generated/KillOutcome";
import type { QueuedTurn } from "../../../src/generated/QueuedTurn";
import type { SessionProgress } from "../../../src/generated/SessionProgress";
import type { SessionWaiting } from "../../../src/generated/SessionWaiting";

/** ts-rs 10 writes i64 as `bigint`; `JSON.parse` gives numbers. */
export type Num<T> = T extends bigint ? number : T extends readonly (infer U)[] ? Num<U>[] : T extends object ? { [K in keyof T]: Num<T[K]> } : T;
/** The daemon omits default-valued keys (and the embedded runner sends fewer), so all but [Req] are optional. */
type Loose<T, Req extends keyof T> = Pick<T, Req> & { [K in Exclude<keyof T, Req>]?: T[K] };

export type SessionState = WireSessionState;
export type Outcome = "SUCCEEDED" | "FAILED" | "KILLED_GRACEFUL" | "KILLED_FORCED" | "ORPHANED";
export type { KillOutcome };
export type ProgressView = Loose<Num<SessionProgress>, "mode" | "state" | "startedAt" | "updatedAt">;
export type QueuedTurnView = Num<QueuedTurn>;
export type WaitingView = Loose<Num<SessionWaiting>, "reason" | "since">;

/** SessionRecord as the `session` / `sessions` frames carry it: the generated type, plus the computed keys its serializer appends. */
export type SessionRecord = Loose<
  Omit<Num<WireSessionRecord>, "progress" | "waiting">,
  "id" | "provider" | "workingDir" | "spawnedAt" | "lastHeartbeat" | "state"
> & {
  progress?: ProgressView | null;
  waiting?: WaitingView | null;
  turnActive?: boolean | null;
  live?: boolean;
};

export interface FileWrite {
  path: string;
  content?: string | null;
  edits?: { oldText: string; newText: string }[] | null;
  patch?: string | null;
  truncated?: boolean;
}

export interface RunActivity {
  state: string;
  label?: string | null;
  startedAt: number;
  turnStartedAt: number;
  lastEventAt: number;
}

export interface StatusPayload {
  state: SessionState;
  detail?: string;
  tool?: string | null;
  activity?: string | null;
  write?: FileWrite | null;
  todos?: { text: string; status: string; activeText?: string | null }[] | null;
  runActivity?: RunActivity | null;
}

export interface FinishedPayload {
  exitCode?: number | null;
  outcome: Outcome;
  detail?: string;
}

export interface ReplayGap {
  requestedAfterSequence: number;
  oldestAvailableSequence: number;
  latestAvailableSequence: number;
  evictedCount: number;
}

export interface RunUsage {
  runId: string;
  provider: string;
  model: string;
  input: number;
  output: number;
  reasoning?: number;
  cacheRead?: number;
  cacheWrite?: number;
  costUsd?: number | null;
}

export interface AgentProfile {
  id?: string | null;
  name: string;
  role: string;
  provider?: string | null;
  model?: string | null;
  projectId?: string | null;
}

export interface TicketSummary {
  id: string;
  title: string;
  status?: string;
}

export interface ProjectSummary {
  id: string;
  name: string;
  directory?: string;
}

/** The `ready` frame's key=value line: `provider=<id> contract=<ver> instance=<id> since=<epoch ms> build=<b>`. */
export interface ReadyInfo {
  provider?: string;
  contract?: string;
  instance?: string;
  /** History horizon: only sessions with `lastHeartbeat >= since` have replay history. */
  since: number;
  build?: string;
}

export function parseReady(data: string): ReadyInfo {
  const kv: Record<string, string> = {};
  for (const part of data.trim().split(/\s+/)) {
    const eq = part.indexOf("=");
    if (eq > 0) kv[part.slice(0, eq)] = part.slice(eq + 1);
  }
  const since = Number(kv.since);
  return { provider: kv.provider, contract: kv.contract, instance: kv.instance, since: Number.isFinite(since) ? since : 0, build: kv.build };
}

/** Decode one text frame; null for anything that is not an envelope (the caller ignores it). */
export function decodeEnvelope(text: string): Envelope | null {
  try {
    const v = JSON.parse(text);
    if (!v || typeof v !== "object" || typeof v.type !== "string") return null;
    return { ...v, data: typeof v.data === "string" ? v.data : v.data == null ? "" : JSON.stringify(v.data) };
  } catch {
    return null;
  }
}

/** Encode an outbound frame the way the Kotlin client does: null `requestId` / `origin` are omitted. */
export function encodeEnvelope(env: Envelope): string {
  const out: Record<string, unknown> = { type: env.type, data: env.data ?? "" };
  if (env.sessionId != null) out.sessionId = env.sessionId;
  if (env.sequence != null) out.sequence = env.sequence;
  if (env.requestId != null) out.requestId = env.requestId;
  if (env.origin != null) out.origin = env.origin;
  return JSON.stringify(out);
}

/** JSON `data`, or null when it is not JSON (a frame from a newer or older daemon). */
export function json<T>(data: string): T | null {
  try {
    return JSON.parse(data) as T;
  } catch {
    return null;
  }
}

/** Running = the session is mid-turn. Everything else is Quiet. */
export function isRunning(r: Pick<SessionRecord, "state" | "turnActive">): boolean {
  return r.turnActive === true || r.state === "BUSY" || r.state === "STARTING" || r.state === "WAITING_INPUT";
}

/** Sessions of the selected project. A session with no projectId is global (e.g. the Assistant) and always shows; no selected project shows all. */
export function inProject<T extends { record: Pick<SessionRecord, "projectId"> }>(items: T[], projectId: string | null | undefined): T[] {
  if (!projectId) return items;
  return items.filter((v) => !v.record.projectId || v.record.projectId === projectId);
}

/** The installation-wide Assistant (`record.global`): the one session the daemon makes for the Assistant box, or null until it has. */
export function assistantOf<T extends { record: Pick<SessionRecord, "global"> }>(items: Iterable<T>): T | null {
  for (const v of items) if (v.record.global) return v;
  return null;
}

/** [items] without the Assistant: it lives in its own box, so it is never a card of the agents lists. */
export function withoutAssistant<T extends { record: Pick<SessionRecord, "global"> }>(items: T[]): T[] {
  return items.some((v) => v.record.global) ? items.filter((v) => !v.record.global) : items;
}
