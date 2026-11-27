// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Session actions the session view offers besides prompt/interrupt (RUST-SESS-2): restart (`session_restart`, refused
// mid-turn unless forced), switch provider/model (`session_swap` -> pushed `session_swapped`) and stats
// (`session_stats_get` -> `session_stats`). Each is a request/answer: the promise resolves with the answer, or rejects
// with the daemon's refusal text (`<type> refused: ...`).
import { json, type Envelope } from "../wire";
import type { FrameLink } from "./link";

export interface SessionStats {
  sessionId: string;
  harness: string;
  providerSessionId?: string | null;
  status?: string | null;
  startedAt?: number | null;
  endedAt?: number | null;
  durationMs?: number | null;
  userPrompts?: number | null;
  assistantTurns?: number | null;
  toolUses?: number | null;
  models?: string[] | null;
  lastContextTokens?: number | null;
  billedTokens?: number | null;
  costTotal?: number | null;
  firstContextTokens?: number | null;
  modelContextLimit?: number | null;
  /** One point per model request the harness reported. */
  series?: StatsPoint[] | null;
  /** Tools by calls, with the context tokens their results added (null = not measured). */
  topSources?: ContextSource[] | null;
  /** Context growth after steps that ran several tools at once (never split). */
  unattributedToolTokens?: number | null;
}

export interface StatsPoint {
  at?: number | null;
  contextTokens: number;
  cumulativeBilledTokens: number;
  cumulativeCost?: number | null;
}

export interface ContextSource {
  tool: string;
  calls: number;
  tokensAdded?: number | null;
}

export interface SessionSwapped {
  sessionId: string;
  provider: string;
  model?: string | null;
  defaultModel?: string | null;
  from?: { provider?: string | null; model?: string | null; effort?: string | null } | null;
  newConversation?: boolean;
  role?: string | null;
  fromRole?: string | null;
}

export interface ContextLimitChanged {
  sessionId: string;
  contextLimit: number | null;
  source: string | null;
  support: string;
  /** When it takes effect, as the harness answers it. */
  effect: string;
}

export interface SwapRequest {
  sessionId: string;
  provider: string;
  model?: string;
  effort?: string;
  /** SESSION-NAME-1: how the session shows on its card; "" clears it (back to the role name), absent keeps it. */
  displayName?: string;
}

export interface SessionActions {
  restart(sessionId: string, force?: boolean): Promise<Envelope>;
  swap(req: SwapRequest): Promise<SessionSwapped>;
  /** Changes the session's context limit (tokens, 0 = off; null = back to the settings default). */
  setContextLimit(sessionId: string, limit: number | null): Promise<ContextLimitChanged>;
  stats(sessionId: string): Promise<SessionStats>;
  dispose(): void;
}

/** True when a restart refusal says the session is mid-turn (a forced restart is then offered). */
export const isMidTurnRefusal = (why: string): boolean => why.includes("mid-turn");

interface Pending {
  type: string;
  sessionId: string;
  answers: string[];
  resolve: (e: Envelope) => void;
  reject: (why: string) => void;
  timer: ReturnType<typeof setTimeout>;
}

export function createSessionActions(link: FrameLink, timeoutMs = 30_000): SessionActions {
  let counter = 0;
  const waiting = new Map<string, Pending>();

  const settle = (id: string, p: Pending): void => {
    waiting.delete(id);
    clearTimeout(p.timer);
  };

  const off = link.onFrame((env) => {
    if (env.requestId && waiting.has(env.requestId)) {
      const p = waiting.get(env.requestId)!;
      if (env.type === "error") return settle(env.requestId, p), p.reject(env.data);
      if (p.answers.includes(env.type)) return settle(env.requestId, p), p.resolve(env);
    }
    // Answers that come back without the request id: match on type and session.
    for (const [id, p] of waiting) {
      if (env.type === "error" && env.data.startsWith(p.type)) return settle(id, p), p.reject(env.data);
      if (p.answers.includes(env.type)) {
        const sid = json<{ sessionId?: string }>(env.data)?.sessionId ?? env.sessionId;
        if (!sid || sid === p.sessionId) return settle(id, p), p.resolve(env);
      }
    }
  });

  /** [silentOk]: no answer in time counts as done (a daemon that only answers a refusal). */
  const request = (type: string, sessionId: string, body: unknown, answers: string[], silentOk = false): Promise<Envelope> =>
    new Promise((resolve, reject) => {
      counter += 1;
      const requestId = `sess:${Date.now().toString(36)}-${counter.toString(36)}`;
      const timer = setTimeout(() => {
        waiting.delete(requestId);
        if (silentOk) resolve({ type, data: "" });
        else reject(`${type} got no answer`);
      }, silentOk ? Math.min(timeoutMs, 8_000) : timeoutMs);
      waiting.set(requestId, { type, sessionId, answers, resolve, reject, timer });
      if (!link.sendFrame({ type, data: JSON.stringify(body), requestId, sessionId })) {
        clearTimeout(timer);
        waiting.delete(requestId);
        reject(`${type} not sent: the daemon link is down`);
      }
    });

  return {
    restart: (sessionId, force = false) => request("session_restart", sessionId, { sessionId, force: String(force) }, ["session_restart", "session_restarted"], true),
    async swap(req) {
      const body: Record<string, string> = { sessionId: req.sessionId, provider: req.provider };
      if (req.model) body.model = req.model;
      if (req.effort) body.effort = req.effort;
      if (req.displayName !== undefined) body.displayName = req.displayName.trim();
      const e = await request("session_swap", req.sessionId, body, ["session_swapped"]);
      return json<SessionSwapped>(e.data) ?? { sessionId: req.sessionId, provider: req.provider };
    },
    async setContextLimit(sessionId, limit) {
      const e = await request("session_context_limit", sessionId, { sessionId, limit }, ["session_context_limit"]);
      const r = json<ContextLimitChanged>(e.data);
      if (!r) throw "session_context_limit: unreadable answer";
      return r;
    },
    async stats(sessionId) {
      const e = await request("session_stats_get", sessionId, { sessionId }, ["session_stats"]);
      const s = json<SessionStats>(e.data);
      if (!s) throw "session_stats: unreadable answer";
      return s;
    },
    dispose() {
      off();
      waiting.forEach((p) => clearTimeout(p.timer));
      waiting.clear();
    },
  };
}
