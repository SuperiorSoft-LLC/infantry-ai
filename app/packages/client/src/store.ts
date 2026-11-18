// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The sessions store: every inbound frame is queued and the queue is applied once per animation frame in ONE state
// update, so a fast stream into many sessions costs one render per frame, not one per delta.
//
// Replay: each session-stream frame carries a per-session `sequence` (from 1). A session's `cursor` is the highest
// CONTIGUOUS sequence applied; a frame ahead of it is held until the gap fills (or the daemon says it was evicted).
// On connect the client asks for every session's retained history (`replay_request`, exclusive cursor); after a
// reconnect to the same daemon instance it asks again from each cursor; a new instance (daemon restart) starts over.
import { createStore, type StoreApi } from "zustand/vanilla";
import { DaemonConnection, type LinkState, type TransportFactory } from "./connection";
import { applyFrames, type TranscriptItem } from "./transcript";
import {
  CLIENT_SEND_PREFIX, Frame, STREAM_FRAMES, isRunning, json, parseReady,
  type AgentProfile, type Envelope, type ProjectSummary, type ReadyInfo, type ReplayGap, type RunUsage, type SessionRecord,
  type StatusPayload, type TicketSummary,
} from "./wire";

/** Tunables. Each is a named knob with the cheapest sensible default (no hard-coded limits). */
export interface StoreSettings {
  /** `limits.chat.sendAckSeconds`: a prompt not acknowledged in this time shows "not sent". */
  sendAckSeconds: number;
  /** Frames held ahead of a gap before the client gives up on the gap and moves on. */
  maxHeldFrames: number;
  /** Latest-flush fallback when animation frames are throttled (a hidden window), ms. */
  flushFallbackMs: number;
  /** Ask every session's history on connect ("all") or only the one the user opens ("selected"). */
  historyOnConnect: "all" | "selected";
}

export const DEFAULT_SETTINGS: StoreSettings = { sendAckSeconds: 45, maxHeldFrames: 512, flushFallbackMs: 250, historyOnConnect: "all" };

export interface PendingSend {
  clientId: string;
  sessionId: string;
  text: string;
  at: number;
  state: "sending" | "accepted" | "failed";
  reason?: string;
}

export interface SessionView {
  record: SessionRecord;
  items: TranscriptItem[];
  /** Highest contiguous sequence applied. */
  cursor: number;
  status: StatusPayload | null;
  usage: Record<string, RunUsage>;
  /** The last line of output, for the sidebar card. */
  lastLine: string;
  /** History was asked from this daemon instance. */
  historyAsked: boolean;
}

export interface Connection {
  url: string;
  state: LinkState;
  attempt: number;
  reason?: string;
  ready?: ReadyInfo;
}

export interface DaemonState {
  connection: Connection;
  sessions: Record<string, SessionView>;
  agents: Record<string, AgentProfile>;
  tickets: Record<string, TicketSummary>;
  projects: ProjectSummary[];
  pending: Record<string, PendingSend>;
  /** Frames applied in the last flush, and flushes so far (the stress overlay reads these). */
  stats: { lastBatch: number; flushes: number; frames: number };
}

export interface DaemonActions {
  connect(): void;
  disconnect(): void;
  /** Send a prompt, or hold it in the daemon's queue when the session is mid-turn. */
  send(sessionId: string, text: string): void;
  /** A new session for [text]: `new`, then `prompt`. */
  spawn(text: string): void;
  /** Start a session with `SpawnOptions` (the `new` frame's JSON data); false when the link is not open. */
  spawnWith(options: Record<string, unknown>): boolean;
  interrupt(sessionId: string): void;
  unqueue(sessionId: string, itemId: string): void;
  forceQueued(sessionId: string, itemId: string): void;
  retry(clientId: string): void;
  requestHistory(sessionId: string): void;
  /** Feed frames as if the daemon sent them (stress mode, tests). */
  ingest(env: Envelope): void;
}

export type DaemonStore = StoreApi<DaemonState & DaemonActions>;

interface Internal {
  queue: Envelope[];
  held: Map<string, Map<number, Envelope>>;
  instance?: string;
  raf: number | null;
  fallback: ReturnType<typeof setTimeout> | null;
  ackTimers: Map<string, ReturnType<typeof setTimeout>>;
}

export function createDaemonStore(opts: { url: string; transport: TransportFactory; settings?: Partial<StoreSettings>; offline?: boolean }): DaemonStore {
  const cfg: StoreSettings = { ...DEFAULT_SETTINGS, ...opts.settings };
  const io: Internal = { queue: [], held: new Map(), raf: null, fallback: null, ackTimers: new Map() };
  let conn: DaemonConnection | null = null;

  const store = createStore<DaemonState & DaemonActions>()((set, get) => {
    const out = (env: Envelope) => conn?.send(env) ?? false;

    const schedule = () => {
      if (io.raf != null || io.fallback != null) return;
      const run = () => {
        if (io.raf != null && typeof cancelAnimationFrame === "function") cancelAnimationFrame(io.raf);
        if (io.fallback != null) clearTimeout(io.fallback);
        io.raf = null;
        io.fallback = null;
        flush();
      };
      if (typeof requestAnimationFrame === "function") io.raf = requestAnimationFrame(run);
      io.fallback = setTimeout(run, cfg.flushFallbackMs);
    };

    const flush = () => {
      const batch = io.queue;
      if (batch.length === 0) return;
      io.queue = [];
      const s = get();
      const next = reduce(s, batch);
      set({ ...next, stats: { lastBatch: batch.length, flushes: s.stats.flushes + 1, frames: s.stats.frames + batch.length } });
    };

    /** The whole batch against the current state: plain objects, one copy per touched session. */
    const reduce = (s: DaemonState, batch: Envelope[]): Partial<DaemonState> => {
      let sessions = s.sessions;
      let touched = false;
      const streams = new Map<string, Envelope[]>();
      /** Each session's cursor within this batch (frames collected but not yet applied count). */
      const cursors = new Map<string, number>();
      let agents = s.agents, tickets = s.tickets, projects = s.projects, pending = s.pending, connection = s.connection;
      const writeSessions = () => {
        if (!touched) { sessions = { ...sessions }; touched = true; }
      };
      const flushStreams = () => {
        if (streams.size === 0) return;
        writeSessions();
        for (const [sid, frames] of streams) {
          const v = sessions[sid];
          if (v) sessions[sid] = applyToView(v, frames);
        }
        streams.clear();
        cursors.clear();
      };
      const askHistory = (sid: string, from: number) => {
        out({ type: Frame.REPLAY_REQUEST, data: JSON.stringify({ lastSequence: from }), sessionId: sid });
      };

      for (const env of batch) {
        const sid = env.sessionId ?? undefined;
        if (sid && STREAM_FRAMES.has(env.type) && env.sequence != null) {
          if (!sessions[sid] && env.type === Frame.SESSION) {
            const r = json<SessionRecord>(env.data);
            if (r) { writeSessions(); sessions[sid] = newView(r, true); }
          }
          const v = sessions[sid];
          if (!v) continue;
          const r = sequenced(sid, cursors.get(sid) ?? v.cursor, env);
          cursors.set(sid, r.cursor);
          if (r.frames.length > 0) {
            const list = streams.get(sid) ?? [];
            list.push(...r.frames);
            streams.set(sid, list);
          }
          continue;
        }
        if (sid && STREAM_FRAMES.has(env.type)) {
          const list = streams.get(sid) ?? [];
          list.push(env);
          streams.set(sid, list);
          continue;
        }
        flushStreams();
        switch (env.type) {
          case Frame.READY: {
            const ready = parseReady(env.data);
            const restarted = io.instance !== undefined && io.instance !== ready.instance;
            io.instance = ready.instance;
            connection = { ...connection, ready };
            if (restarted) {
              io.held.clear();
              writeSessions();
              for (const id of Object.keys(sessions)) sessions[id] = newView(sessions[id].record, false);
            }
            out({ type: Frame.AGENT_LIST, data: "" });
            out({ type: Frame.PROJECT_LIST, data: "" });
            out({ type: Frame.TICKET_LIST, data: "" });
            break;
          }
          case Frame.SESSIONS: {
            const list = json<SessionRecord[]>(env.data) ?? [];
            writeSessions();
            const keep: Record<string, SessionView> = {};
            const since = connection.ready?.since ?? 0;
            for (const r of list) {
              const prev = sessions[r.id];
              const v = prev ? { ...prev, record: r } : newView(r, false);
              keep[r.id] = v;
              if (v.historyAsked) askHistory(r.id, v.cursor);
              else if (cfg.historyOnConnect === "all" && r.lastHeartbeat >= since) {
                keep[r.id] = { ...v, historyAsked: true };
                askHistory(r.id, v.cursor);
              }
            }
            sessions = keep;
            break;
          }
          case Frame.REPLAY_GAP: {
            const g = json<ReplayGap>(env.data);
            const v = sid ? sessions[sid] : undefined;
            if (!g || !v || !sid) break;
            writeSessions();
            const cursor = Math.max(v.cursor, g.oldestAvailableSequence - 1);
            const items = g.evictedCount > 0 ? applyFrames(v.items, [env]) : v.items;
            sessions[sid] = drain(sid, { ...v, cursor, items });
            break;
          }
          case Frame.AGENTS: {
            const list = json<AgentProfile[]>(env.data) ?? [];
            agents = Object.fromEntries(list.filter((a) => a.id).map((a) => [a.id as string, a]));
            break;
          }
          case Frame.AGENT: {
            const a = json<AgentProfile>(env.data);
            if (a?.id) agents = { ...agents, [a.id]: a };
            break;
          }
          case Frame.TICKETS: {
            const list = json<TicketSummary[]>(env.data) ?? [];
            tickets = { ...tickets, ...Object.fromEntries(list.map((t) => [t.id, { id: t.id, title: t.title, status: t.status }])) };
            break;
          }
          case Frame.TICKET: {
            const t = json<TicketSummary>(env.data);
            if (t?.id) tickets = { ...tickets, [t.id]: { id: t.id, title: t.title, status: t.status } };
            break;
          }
          case Frame.PROJECTS: {
            const list = json<ProjectSummary[]>(env.data) ?? [];
            projects = list.map((p) => ({ id: p.id, name: p.name, directory: p.directory }));
            break;
          }
          case Frame.PROMPT_ACK: {
            const p = env.requestId ? pending[env.requestId] : undefined;
            if (p) pending = { ...pending, [p.clientId]: { ...p, state: "accepted" } };
            break;
          }
        }
      }
      flushStreams();

      // A turn echo settles the pending send it answers.
      const live = Object.values(pending);
      if (live.length > 0) {
        let changed = false;
        const nextPending = { ...pending };
        for (const p of live) {
          const v = sessions[p.sessionId];
          if (v && v.items.some((i) => i.kind === "user" && i.text === p.text && i.seq > 0 && i.at >= p.at - 1000)) {
            delete nextPending[p.clientId];
            const t = io.ackTimers.get(p.clientId);
            if (t) clearTimeout(t);
            io.ackTimers.delete(p.clientId);
            changed = true;
          }
        }
        if (changed) pending = nextPending;
      }
      return { sessions, agents, tickets, projects, pending, connection };
    };

    /** In-order delivery for one session: frames at or below the cursor are dropped, frames ahead are held. */
    const sequenced = (sid: string, cursor: number, env: Envelope): { frames: Envelope[]; cursor: number } => {
      const seq = env.sequence as number;
      const held = io.held.get(sid) ?? new Map<number, Envelope>();
      io.held.set(sid, held);
      if (seq <= cursor) return { frames: [], cursor };
      held.set(seq, env);
      const ready: Envelope[] = [];
      let c = cursor;
      while (held.has(c + 1)) {
        ready.push(held.get(c + 1) as Envelope);
        held.delete(c + 1);
        c += 1;
      }
      if (ready.length === 0 && held.size > cfg.maxHeldFrames) {
        // The gap never filled: move on from the oldest held frame (a lost frame, shown as a gap).
        const keys = [...held.keys()].sort((a, b) => a - b);
        c = keys[0] - 1;
        const gap: Envelope = { type: Frame.REPLAY_GAP, sessionId: sid, data: JSON.stringify({ requestedAfterSequence: cursor, oldestAvailableSequence: keys[0], latestAvailableSequence: keys[keys.length - 1], evictedCount: keys[0] - cursor - 1 }) };
        ready.push(gap);
        while (held.has(c + 1)) {
          ready.push(held.get(c + 1) as Envelope);
          held.delete(c + 1);
          c += 1;
        }
      }
      return { frames: ready, cursor: c };
    };

    const drain = (sid: string, v: SessionView): SessionView => {
      const held = io.held.get(sid);
      if (!held || held.size === 0) return v;
      const ready: Envelope[] = [];
      let c = v.cursor;
      for (const k of [...held.keys()]) if (k <= c) held.delete(k);
      while (held.has(c + 1)) {
        ready.push(held.get(c + 1) as Envelope);
        held.delete(c + 1);
        c += 1;
      }
      return ready.length ? applyToView(v, ready) : v;
    };

    return {
      connection: { url: opts.url, state: "closed", attempt: 0 },
      sessions: {},
      agents: {},
      tickets: {},
      projects: [],
      pending: {},
      stats: { lastBatch: 0, flushes: 0, frames: 0 },

      connect() {
        if (opts.offline || conn) return;
        conn = new DaemonConnection({
          transport: opts.transport,
          onEnvelope: (env) => get().ingest(env),
          onState: (state, d) => {
            set({ connection: { ...get().connection, state, attempt: d.attempt, reason: d.reason } });
            if (state === "closed") failPending("connection lost");
          },
        });
        conn.start();
      },
      disconnect() {
        conn?.stop();
        conn = null;
      },
      send(sessionId, text) {
        const t = text.trim();
        if (!t) return;
        const v = get().sessions[sessionId];
        if (v && isRunning(v.record)) {
          out({ type: Frame.QUEUE_ADD, data: t, sessionId });
          return;
        }
        const clientId = conn?.newClientId() ?? `${CLIENT_SEND_PREFIX}${Date.now().toString(36)}`;
        transmit({ clientId, sessionId, text: t, at: Date.now(), state: "sending" });
      },
      spawn(text) {
        const t = text.trim();
        if (!t) return;
        out({ type: Frame.NEW, data: "" });
        out({ type: Frame.PROMPT, data: t, requestId: conn?.newClientId() });
      },
      spawnWith(options) {
        return out({ type: Frame.NEW, data: JSON.stringify(options) });
      },
      interrupt(sessionId) {
        out({ type: Frame.INTERRUPT, data: sessionId, sessionId });
      },
      unqueue(sessionId, itemId) {
        out({ type: Frame.QUEUE_REMOVE, data: itemId, sessionId });
      },
      forceQueued(sessionId, itemId) {
        out({ type: Frame.QUEUE_FORCE, data: itemId, sessionId });
      },
      retry(clientId) {
        const p = get().pending[clientId];
        if (p && p.state === "failed") transmit({ ...p, state: "sending", reason: undefined, at: Date.now() });
      },
      requestHistory(sessionId) {
        const v = get().sessions[sessionId];
        if (!v || v.historyAsked) return;
        set({ sessions: { ...get().sessions, [sessionId]: { ...v, historyAsked: true } } });
        out({ type: Frame.REPLAY_REQUEST, data: JSON.stringify({ lastSequence: v.cursor }), sessionId });
      },
      ingest(env) {
        io.queue.push(env);
        schedule();
      },
    };

    function transmit(p: PendingSend) {
      set({ pending: { ...get().pending, [p.clientId]: p } });
      const sent = out({ type: Frame.PROMPT, data: p.text, sessionId: p.sessionId, requestId: p.clientId });
      if (!sent) return markFailed(p.clientId, "not connected");
      const old = io.ackTimers.get(p.clientId);
      if (old) clearTimeout(old);
      io.ackTimers.set(p.clientId, setTimeout(() => {
        const cur = get().pending[p.clientId];
        if (cur && cur.state === "sending" && cur.at === p.at) markFailed(p.clientId, `no answer in ${cfg.sendAckSeconds}s`);
      }, cfg.sendAckSeconds * 1000));
    }
    function markFailed(clientId: string, reason: string) {
      const cur = get().pending[clientId];
      if (cur) set({ pending: { ...get().pending, [clientId]: { ...cur, state: "failed", reason } } });
    }
    function failPending(reason: string) {
      for (const p of Object.values(get().pending)) if (p.state === "sending") markFailed(p.clientId, reason);
    }
  });
  return store;
}

function newView(record: SessionRecord, historyAsked: boolean): SessionView {
  return { record, items: [], cursor: 0, status: null, usage: {}, lastLine: "", historyAsked };
}

/** Apply in-order frames to one session view (items, record, status, usage, last line). */
export function applyToView(v: SessionView, frames: readonly Envelope[]): SessionView {
  let record = v.record, status = v.status, usage = v.usage, lastLine = v.lastLine;
  let cursor = v.cursor;
  for (const f of frames) {
    if (f.sequence != null && f.sequence > cursor) cursor = f.sequence;
    switch (f.type) {
      case Frame.SESSION: {
        const r = json<SessionRecord>(f.data);
        if (r) record = r;
        break;
      }
      case Frame.STATUS: {
        const s = json<StatusPayload>(f.data);
        if (s) {
          status = s;
          if (record.state !== s.state) record = { ...record, state: s.state };
        }
        break;
      }
      case Frame.USAGE: {
        const u = json<RunUsage>(f.data);
        if (u) usage = { ...usage, [u.runId]: u };
        break;
      }
      case Frame.TEXT_DELTA:
      case Frame.STDERR:
      case Frame.ERROR: {
        const line = f.data.trim().split("\n").pop();
        if (line) lastLine = line;
        break;
      }
      case Frame.FINISHED: {
        const p = json<{ outcome: string }>(f.data);
        if (p) lastLine = `finished: ${p.outcome.toLowerCase().replace(/_/g, " ")}`;
        break;
      }
    }
  }
  return { ...v, record, status, usage, lastLine, cursor, items: applyFrames(v.items, frames) };
}
