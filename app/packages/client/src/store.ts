// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The sessions store: every inbound frame is queued and the queue is applied once per animation frame in ONE state
// update, so a fast stream into many sessions costs one render per frame, not one per delta.
//
// Replay: each session-stream frame carries a per-session `sequence` (from 1). A session's `cursor` is the highest
// CONTIGUOUS sequence applied; a frame ahead of it is held until the gap fills (or the daemon says it was evicted).
// On connect the client asks no history (FIRST-SYNC-1: `historyOnConnect` "selected"): the session list is the snapshot, and a
// session's history is asked when it is opened. An unopened session takes live frames as a peek (record, status, last
// line) without moving its cursor, so opening it later replays from the start with no false gap. After a reconnect to
// the same daemon instance each opened session asks again from its cursor; a new instance (daemon restart) starts over.
import { createStore, type StoreApi } from "zustand/vanilla";
import { DaemonConnection, type LinkState, type TransportFactory } from "./connection";
import { applyFrames, type TranscriptItem } from "./transcript";
import { timedHop } from "./hops";
import {
  CLIENT_SEND_PREFIX, Frame, RUN_IN_FLIGHT, SESSION_GONE, STREAM_FRAMES, isRunning, json, parseReady,
  type AgentProfile, type Envelope, type FileWrite, type ProjectSummary, type ReadyInfo, type ReplayGap, type RunUsage, type SessionRecord,
  type StatusPayload, type TicketSummary,
} from "./wire";

/** Tunables. Each is a named knob with the cheapest sensible default (no hard-coded limits). */
export interface StoreSettings {
  /** `limits.chat.sendAckSeconds`: a prompt not acknowledged in this time shows "not sent". */
  sendAckSeconds: number;
  /** Frames held ahead of a gap before the client gives up on the gap and moves on. */
  maxHeldFrames: number;
  /** How long frames held ahead of a gap wait for the missing ones before the client moves on and marks "output missing", ms. */
  gapWaitMs: number;
  /** Latest-flush fallback when animation frames are throttled (a hidden window), ms. */
  flushFallbackMs: number;
  /** Ask every session's history on connect ("all") or only the one the user opens ("selected"). */
  historyOnConnect: "all" | "selected";
  /** `limits.chat.replayTurns` (ANDROID-REPLAY-1): turns a chat shows when opened, and per "older" page. 0 = all kept. */
  replayTurns: number;
  /** A replay with no `replay_done` (an older daemon) is applied this long after its last frame, ms. */
  replayDoneWaitMs: number;
}

export const DEFAULT_SETTINGS: StoreSettings = { sendAckSeconds: 45, maxHeldFrames: 512, gapWaitMs: 2000, flushFallbackMs: 250, historyOnConnect: "selected", replayTurns: 50, replayDoneWaitMs: 3000 };

export interface PendingSend {
  clientId: string;
  sessionId: string;
  text: string;
  at: number;
  state: "sending" | "accepted" | "failed";
  reason?: string;
}

/**
 * CHAT-GONE-1: a session this client knew (or sent to) that the daemon no longer has: "refused" = a send was refused
 * with `session_gone` (ended, unknown after a restart), "removed" = it left the daemon's sessions list. [text] is the
 * refused message, so the chat can put it back in the box (taken once with `takeGoneText`).
 */
export interface GoneSession {
  reason: "refused" | "removed";
  text?: string;
}

export interface ActivityEntry {
  at: number;
  label: string;
  /** A file write: the Activity box draws it as the same change box the chat does. */
  write?: FileWrite;
}

/** How many Activity box lines a session keeps. */
export const MAX_ACTIVITY_LOG = 200;
/** Frames arriving this soon after a history request are replay, not live activity (ms). */
const REPLAY_WINDOW_MS = 2000;
const replayUntil = new Map<string, number>();

export interface SessionView {
  record: SessionRecord;
  items: TranscriptItem[];
  /** Highest contiguous sequence applied. */
  cursor: number;
  status: StatusPayload | null;
  usage: Record<string, RunUsage>;
  /** The last line of output, for the sidebar card. */
  lastLine: string;
  /** When the agent last made any event (ms): the daemon's lastEventAt, bumped by every live frame. */
  activityAt: number;
  /** Tool activity lines, oldest first, for the Activity box. */
  activityLog: ActivityEntry[];
  /** History was asked from this daemon instance. */
  historyAsked: boolean;
  /** ANDROID-REPLAY-1: a history answer is on its way; the chat shows "Loading chat…" and is drawn once it ends. */
  loadingHistory?: boolean;
  /** The oldest sequence shown, and whether the daemon keeps older turns (scrolling up asks them). */
  oldestSequence?: number;
  hasOlder?: boolean;
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
  /** FIRST-SYNC-1: the project list has arrived since the last READY; until then sessions are not grouped and lists show "Syncing". */
  projectsLoaded: boolean;
  pending: Record<string, PendingSend>;
  /** CHAT-GONE-1: sessions known to be gone, by id. */
  gone: Record<string, GoneSession>;
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
  /** CHAT-GONE-1: the refused text of a gone session, once (then cleared); undefined when there is none. */
  takeGoneText(sessionId: string): string | undefined;
  requestHistory(sessionId: string): void;
  /** ANDROID-REPLAY-1: the page of turns before the oldest one shown (lazy-load when the chat is scrolled to the top). */
  requestOlder(sessionId: string): void;
  /** Feed frames as if the daemon sent them (stress mode, tests). */
  ingest(env: Envelope): void;
  /** Send any frame (the board and settings stores use it); false when the link is not open. */
  sendFrame(env: Envelope): boolean;
  /** Watch every inbound frame, synchronously, before it is queued. Returns the unsubscribe. */
  onFrame(listener: (env: Envelope) => void): () => void;
}

export type DaemonStore = StoreApi<DaemonState & DaemonActions>;

interface Internal {
  queue: Envelope[];
  held: Map<string, Map<number, Envelope>>;
  instance?: string;
  raf: number | null;
  fallback: ReturnType<typeof setTimeout> | null;
  ackTimers: Map<string, ReturnType<typeof setTimeout>>;
  gapTimers: Map<string, ReturnType<typeof setTimeout>>;
  /** The last text sent to each session (a `queue_add` has no requestId), for a `session_gone` refusal. */
  lastText: Map<string, string>;
  /** CHAT-GONE-2: the first sessions list after a READY does not mark missing sessions removed; it asks again. */
  listUnconfirmed: boolean;
  /** CHAT-GONE-2: a refused session's `lastHeartbeat` at the refusal; a listed record newer than it is alive. */
  goneBeat: Map<string, number>;
  /** ANDROID-REPLAY-1: frames of a history answer, held until its `replay_done` so the chat is drawn once. */
  replays: Map<string, { frames: Envelope[]; older: boolean; timer?: ReturnType<typeof setTimeout> }>;
}

export function createDaemonStore(opts: { url: string; transport: TransportFactory; settings?: Partial<StoreSettings>; offline?: boolean }): DaemonStore {
  const cfg: StoreSettings = { ...DEFAULT_SETTINGS, ...opts.settings };
  const io: Internal = { queue: [], held: new Map(), raf: null, fallback: null, ackTimers: new Map(), gapTimers: new Map(), lastText: new Map(), listUnconfirmed: false, goneBeat: new Map(), replays: new Map() };
  let conn: DaemonConnection | null = null;
  const taps = new Set<(env: Envelope) => void>();

  const store = createStore<DaemonState & DaemonActions>()((set, get) => {
    const out = (env: Envelope) => conn?.send(env) ?? false;

    /** A history answer that never says `replay_done` (an older daemon) still lands, once, after a quiet spell. */
    const armReplay = (sid: string) => {
      const r = io.replays.get(sid);
      if (!r) return;
      if (r.timer) clearTimeout(r.timer);
      r.timer = setTimeout(() => {
        if (io.replays.get(sid) !== r) return;
        io.queue.push({ type: Frame.REPLAY_DONE, sessionId: sid, data: JSON.stringify({ hasMore: false }) });
        schedule();
      }, cfg.replayDoneWaitMs);
    };
    /** Ask a history answer: the latest turns on a first open, everything after the cursor on a resync, or an older page. */
    const askReplay = (sid: string, ask: { lastSequence: number; beforeSequence?: number }) => {
      const prev = io.replays.get(sid);
      if (prev?.timer) clearTimeout(prev.timer);
      io.replays.set(sid, { frames: [], older: ask.beforeSequence != null });
      armReplay(sid);
      replayUntil.set(sid, Date.now() + REPLAY_WINDOW_MS);
      out({ type: Frame.REPLAY_REQUEST, data: JSON.stringify({ ...ask, turns: cfg.replayTurns }), sessionId: sid });
    };

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
      timedHop("store.flush", () => {
        const next = reduce(s, batch);
        set({ ...next, stats: { lastBatch: batch.length, flushes: s.stats.flushes + 1, frames: s.stats.frames + batch.length } });
      }, { frames: batch.length });
      watchGaps();
    };

    /** Frames held ahead of a gap that never fills (a range lost on the daemon's disk) must not stall the chat: after
     *  `gapWaitMs` the client moves past the gap and shows what it has, marked as missing output. */
    const watchGaps = () => {
      for (const [sid, held] of io.held) {
        if (held.size === 0 || io.gapTimers.has(sid)) continue;
        io.gapTimers.set(sid, setTimeout(() => {
          io.gapTimers.delete(sid);
          const h = io.held.get(sid);
          if (!h || h.size === 0) return;
          const cursor = get().sessions[sid]?.cursor ?? 0;
          const lowest = Math.min(...h.keys());
          if (lowest <= cursor + 1) return schedule();
          const data = JSON.stringify({ requestedAfterSequence: cursor, oldestAvailableSequence: lowest, latestAvailableSequence: Math.max(...h.keys()), evictedCount: lowest - cursor - 1 });
          io.queue.push({ type: Frame.REPLAY_GAP, sessionId: sid, data });
          schedule();
        }, cfg.gapWaitMs));
      }
    };

    /** The whole batch against the current state: plain objects, one copy per touched session. */
    const reduce = (s: DaemonState, batch: Envelope[]): Partial<DaemonState> => {
      let sessions = s.sessions;
      let touched = false;
      const streams = new Map<string, Envelope[]>();
      /** Each session's cursor within this batch (frames collected but not yet applied count). */
      const cursors = new Map<string, number>();
      let agents = s.agents, tickets = s.tickets, projects = s.projects, projectsLoaded = s.projectsLoaded, pending = s.pending, connection = s.connection, gone = s.gone;
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
        askReplay(sid, { lastSequence: from });
        const v = sessions[sid];
        if (v && !v.loadingHistory) { writeSessions(); sessions[sid] = { ...v, loadingHistory: true }; }
      };
      /** ANDROID-REPLAY-1: the whole history answer in one update. The card keeps its CURRENT record, status and
       *  activity (the sessions list is the truth); only the transcript, usage and last line come from history. */
      const finishReplay = (sid: string, done: { fromSequence?: number | null; hasMore?: boolean }) => {
        const r = io.replays.get(sid);
        io.replays.delete(sid);
        if (r?.timer) clearTimeout(r.timer);
        const v = sessions[sid];
        if (!r || !v) return;
        writeSessions();
        const seen = new Set<number>();
        // In sequence order (a daemon gap marker sits just before the frame it names), then each sequence once.
        const key = (f: Envelope) => f.sequence ?? ((json<ReplayGap>(f.data)?.oldestAvailableSequence ?? 0) - 0.5);
        const frames = r.frames.map((f, i) => ({ f, i })).sort((a, b) => key(a.f) - key(b.f) || a.i - b.i).map((x) => x.f).filter((f) => f.sequence == null || (!seen.has(f.sequence) && seen.add(f.sequence)));
        const from = done.fromSequence ?? frames.find((f) => f.sequence != null)?.sequence ?? undefined;
        if (r.older) {
          const floor = v.oldestSequence ?? Infinity;
          const older = frames.filter((f) => f.sequence == null || f.sequence < floor);
          sessions[sid] = { ...v, items: [...applyFrames([], older), ...v.items], oldestSequence: from ?? v.oldestSequence, hasOlder: !!done.hasMore, loadingHistory: false };
          return;
        }
        const fresh = markHoles(sid, frames.filter((f) => f.sequence == null || f.sequence > v.cursor), v.cursor, done.fromSequence ?? undefined);
        const top = fresh.reduce((m, f) => Math.max(m, f.sequence ?? 0), v.cursor);
        const applied = applyToView({ ...v, cursor: top }, fresh);
        io.held.delete(sid);
        const first = v.cursor === 0;
        sessions[sid] = {
          ...applied, record: v.record, status: v.status, activityAt: v.activityAt, activityLog: v.activityLog, cursor: top, loadingHistory: false,
          oldestSequence: first ? from : v.oldestSequence ?? from, hasOlder: first ? !!done.hasMore : v.hasOlder,
        };
      };

      for (const env of batch) {
        const sid = env.sessionId ?? undefined;
        // CHAT-GONE-1: a send refused because the session is gone: drop its pending send, keep the text for the box,
        // and stop showing the session as working (the daemon has no such session to run).
        if (env.type === Frame.ERROR && env.data.startsWith(`${SESSION_GONE} `)) {
          const id = sid ?? env.data.slice(SESSION_GONE.length + 1).split(":")[0];
          const p = env.requestId ? pending[env.requestId] : undefined;
          if (p) {
            const { [p.clientId]: _, ...rest } = pending;
            pending = rest;
            const t = io.ackTimers.get(p.clientId);
            if (t) clearTimeout(t);
            io.ackTimers.delete(p.clientId);
          }
          gone = { ...gone, [id]: { reason: "refused", text: p?.text ?? io.lastText.get(id) } };
          io.goneBeat.set(id, sessions[id]?.record.lastHeartbeat ?? 0);
          io.lastText.delete(id);
          const v = sessions[id];
          if (v) {
            writeSessions();
            sessions[id] = { ...v, record: { ...v.record, state: "EXITED", turnActive: false } };
          }
          continue;
        }
        // CHAT-GONE-2: a stream frame of a session is a sign of life: a "refused" mark is lifted, and the last text
        // sent to it can no longer be refused (a queue_add has no ack of its own; its answer is the session's frames).
        if (sid && STREAM_FRAMES.has(env.type)) {
          io.lastText.delete(sid);
          if (gone[sid]?.reason === "refused") {
            const { [sid]: _, ...rest } = gone;
            gone = rest;
          }
        }
        // ANDROID-REPLAY-1: while a history answer is on its way, its frames (and live ones) wait for `replay_done`.
        const pendingReplay = sid ? io.replays.get(sid) : undefined;
        // An "older" page holds only frames below the oldest one shown; live frames go on as usual.
        const olderFloor = sid && pendingReplay?.older ? sessions[sid]?.oldestSequence ?? Infinity : Infinity;
        if (sid && pendingReplay && (env.type === Frame.REPLAY_GAP || (STREAM_FRAMES.has(env.type) && (env.sequence ?? 0) < olderFloor))) {
          io.replays.get(sid)!.frames.push(env);
          armReplay(sid);
          continue;
        }
        if (sid && env.type === Frame.REPLAY_DONE) {
          flushStreams();
          finishReplay(sid, json<{ fromSequence?: number | null; hasMore?: boolean }>(env.data) ?? {});
          continue;
        }
        if (sid && STREAM_FRAMES.has(env.type) && env.sequence != null) {
          if (!sessions[sid] && env.type === Frame.SESSION) {
            const r = json<SessionRecord>(env.data);
            if (r) { writeSessions(); sessions[sid] = newView(r, true); }
          }
          const v = sessions[sid];
          if (!v) continue;
          if (!v.historyAsked) {
            flushStreams();
            writeSessions();
            sessions[sid] = { ...applyToView(v, [env]), cursor: v.cursor, items: v.items };
            continue;
          }
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
            io.listUnconfirmed = true;
            // CHAT-GONE-2: a new daemon instance: what the old one refused or removed says nothing about this one.
            if (restarted && Object.keys(gone).length) gone = {};
            if (restarted) {
              io.held.clear();
              writeSessions();
              for (const id of Object.keys(sessions)) sessions[id] = newView(sessions[id].record, false);
            }
            // FIRST-SYNC-1: projects first, so sessions land under their own project, never a default one.
            out({ type: Frame.PROJECT_LIST, data: "" });
            out({ type: Frame.AGENT_LIST, data: "" });
            out({ type: Frame.TICKET_LIST, data: JSON.stringify({ compact: true }) }); // summaries only
            break;
          }
          case Frame.SESSIONS: {
            const list = json<SessionRecord[]>(env.data) ?? [];
            writeSessions();
            const keep: Record<string, SessionView> = {};
            // CHAT-GONE-1: a session that left the list is gone; one that is listed again is not.
            const listed = new Set(list.map((r) => r.id));
            const byId = new Map(list.map((r) => [r.id, r]));
            // CHAT-GONE-2: the first list after a READY may be empty or partial (a store still loading, a relayed
            // backend): what is missing from it is kept as it was and the list is asked again; the next list decides.
            const unconfirmed = io.listUnconfirmed;
            io.listUnconfirmed = false;
            const missing = Object.keys(sessions).filter((id) => !listed.has(id) && !gone[id]);
            const left = unconfirmed ? [] : missing;
            if (unconfirmed && missing.length) out({ type: Frame.SESSIONS, data: "" });
            const back = Object.keys(gone).filter((id) => {
              const r = byId.get(id);
              if (!r) return false;
              // a "refused" session stays gone while it is only listed; a newer heartbeat than at the refusal is life
              return gone[id].reason === "removed" || r.lastHeartbeat > (io.goneBeat.get(id) ?? Infinity);
            });
            if (left.length || back.length) {
              gone = { ...gone };
              for (const id of left) gone[id] = { reason: "removed" };
              for (const id of back) delete gone[id];
            }
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
            if (unconfirmed) for (const id of missing) keep[id] = sessions[id];
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
            projectsLoaded = true;
            break;
          }
          case Frame.ERROR: {
            // 1.96.0: a send that met a turn already running (another client's, or a queued message) is refused typed;
            // it goes to the daemon's queue instead (CHAT-QUEUE-1), as the Kotlin client does.
            const p = env.requestId ? pending[env.requestId] : undefined;
            if (p && env.data.startsWith(`${RUN_IN_FLIGHT} `)) {
              const { [p.clientId]: _, ...rest } = pending;
              pending = rest;
              const t = io.ackTimers.get(p.clientId);
              if (t) clearTimeout(t);
              io.ackTimers.delete(p.clientId);
              out({ type: Frame.QUEUE_ADD, data: p.text, sessionId: p.sessionId });
            }
            break;
          }
          case Frame.PROMPT_ACK: {
            const p = env.requestId ? pending[env.requestId] : undefined;
            if (p) pending = { ...pending, [p.clientId]: { ...p, state: "accepted" } };
            // CHAT-GONE-2: an acknowledged send can no longer be refused: its text never comes back into the box.
            if (p) io.lastText.delete(p.sessionId);
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
      return { sessions, agents, tickets, projects, projectsLoaded, pending, connection, gone };
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
      projectsLoaded: false,
      pending: {},
      gone: {},
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
        io.lastText.set(sessionId, t);
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
      takeGoneText(sessionId) {
        const g = get().gone[sessionId];
        if (!g?.text) return undefined;
        set({ gone: { ...get().gone, [sessionId]: { reason: g.reason } } });
        return g.text;
      },
      requestHistory(sessionId) {
        const v = get().sessions[sessionId];
        if (!v || v.historyAsked) return;
        set({ sessions: { ...get().sessions, [sessionId]: { ...v, historyAsked: true, loadingHistory: true } } });
        askReplay(sessionId, { lastSequence: v.cursor });
      },
      requestOlder(sessionId) {
        const v = get().sessions[sessionId];
        if (!v || !v.hasOlder || v.oldestSequence == null || io.replays.has(sessionId)) return;
        set({ sessions: { ...get().sessions, [sessionId]: { ...v, loadingHistory: true } } });
        askReplay(sessionId, { lastSequence: 0, beforeSequence: v.oldestSequence });
      },
      sendFrame(env) {
        return out(env);
      },
      onFrame(listener) {
        taps.add(listener);
        return () => void taps.delete(listener);
      },
      ingest(env) {
        for (const t of taps) t(env);
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
  return { record, items: [], cursor: 0, status: null, usage: {}, lastLine: "", activityAt: record.lastEventAt ?? record.lastHeartbeat, activityLog: [], historyAsked };
}

/** A history answer's frames with a `replay_gap` put where the sequence jumps and the daemon marked nothing (a range lost
 *  on its disk), so the chat shows "output missing" there. A first open of a window starts at `from`: no gap before it. */
function markHoles(sid: string, frames: Envelope[], cursor: number, from?: number): Envelope[] {
  const out: Envelope[] = [];
  let prev = cursor === 0 && from != null ? from - 1 : cursor;
  let marked = false;
  for (const f of frames) {
    if (f.type === Frame.REPLAY_GAP) { marked = true; out.push(f); continue; }
    if (f.sequence != null) {
      if (f.sequence > prev + 1 && !marked && !(prev === 0 && cursor === 0 && from == null)) {
        const data = JSON.stringify({ requestedAfterSequence: prev, oldestAvailableSequence: f.sequence, latestAvailableSequence: f.sequence, evictedCount: f.sequence - prev - 1 });
        out.push({ type: Frame.REPLAY_GAP, sessionId: sid, data });
      }
      prev = Math.max(prev, f.sequence);
      marked = false;
    }
    out.push(f);
  }
  return out;
}

/** Apply in-order frames to one session view (items, record, status, usage, last line). */
export function applyToView(v: SessionView, frames: readonly Envelope[]): SessionView {
  let record = v.record, status = v.status, usage = v.usage, lastLine = v.lastLine;
  let activityAt = v.activityAt ?? record.lastEventAt ?? record.lastHeartbeat, activityLog = v.activityLog ?? [];
  const now = Date.now();
  const live = now > (replayUntil.get(record.id) ?? 0);
  let cursor = v.cursor;
  for (const f of frames) {
    if (f.sequence != null && f.sequence > cursor) cursor = f.sequence;
    if (live && f.type !== Frame.SESSION) activityAt = now;
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
          if (live && s.write) {
            activityLog = [...activityLog, { at: now, label: `Edit: ${s.write.path}`, write: s.write }].slice(-MAX_ACTIVITY_LOG);
          } else if (live && s.tool && s.activity) {
            activityLog = [...activityLog, { at: now, label: `${s.tool}: ${s.activity}` }].slice(-MAX_ACTIVITY_LOG);
          }
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
  return { ...v, record, status, usage, lastLine, activityAt, activityLog, cursor, items: applyFrames(v.items, frames) };
}
