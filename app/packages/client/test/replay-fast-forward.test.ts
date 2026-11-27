// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// ANDROID-REPLAY-1 reproduction (fails on dev 95702f229, the fix turns it green). A phone opens a chat: the daemon answers
// replay_request with every kept frame as its own WS message (sessions.rs replay_frames, server.rs send loop), with no end
// marker, and on a phone link they arrive spread over many animation frames. The shapes below are the frame mix measured
// on a test daemon and a real long chat: old `session` records and `status` frames are part of the history.
import { afterEach, describe, expect, it } from "vitest";
import { createDaemonStore, type DaemonStore, type Envelope, type TransportHandlers } from "../src";

function fakeDaemon() {
  let h: TransportHandlers | null = null;
  const factory = () => ({ open(handlers: TransportHandlers) { h = handlers; }, send() {}, close() {} });
  return { factory, open: () => h?.onOpen(), push: (env: Envelope) => h?.onText(JSON.stringify(env)) };
}

const tick = () => new Promise((r) => setTimeout(r, 5));
const rec = (id: string, state: string, name: string) => ({ id, provider: "stub", workingDir: "w", spawnedAt: 1, lastHeartbeat: 10, state, name });
let store: DaemonStore | null = null;
afterEach(() => store?.getState().disconnect());

/** A long chat's history: 20 turns, each an old session record, a RUNNING status, text and a finish. */
function history(sid: string): Envelope[] {
  const out: Envelope[] = [];
  let seq = 0;
  for (let t = 0; t < 20; t++) {
    out.push({ type: "session", sessionId: sid, sequence: ++seq, data: JSON.stringify(rec(sid, "RUNNING", `old title ${t}`)) });
    out.push({ type: "status", sessionId: sid, sequence: ++seq, data: JSON.stringify({ state: "RUNNING" }) });
    out.push({ type: "text_delta", sessionId: sid, sequence: ++seq, data: `answer ${t}. ` });
    out.push({ type: "finished", sessionId: sid, sequence: ++seq, data: JSON.stringify({ outcome: "SUCCEEDED" }) });
  }
  return out;
}

async function openChatOverSlowLink(chunks: number) {
  const d = fakeDaemon();
  store = createDaemonStore({ url: "ws://test/ws", transport: d.factory, settings: { flushFallbackMs: 1, historyOnConnect: "selected" } });
  const s = store;
  s.getState().connect();
  d.open();
  d.push({ type: "ready", data: "provider=stub instance=i1 since=0" });
  d.push({ type: "sessions", data: JSON.stringify([rec("s1", "IDLE", "current title")]) });
  await tick();
  s.getState().requestHistory("s1");
  const frames = history("s1");
  const size = Math.ceil(frames.length / chunks);
  const seen: { state: string; title: string; items: number }[] = [];
  const flushesBefore = s.getState().stats.flushes;
  for (let i = 0; i < frames.length; i += size) {
    for (const f of frames.slice(i, i + size)) d.push(f);
    await tick(); // the next part of the replay lands in a later animation frame
    const v = s.getState().sessions.s1;
    seen.push({ state: v.record.state, title: (v.record as { name?: string }).name ?? "", items: v.items.length });
  }
  // The daemon ends every history answer with replay_done (sessions.rs replay_answer).
  d.push({ type: "replay_done", sessionId: "s1", data: JSON.stringify({ fromSequence: 1, toSequence: frames.length, hasMore: false }) });
  await tick();
  const v = s.getState().sessions.s1;
  seen.push({ state: v.record.state, title: (v.record as { name?: string }).name ?? "", items: v.items.length });
  return { seen, renders: s.getState().stats.flushes - flushesBefore, final: s.getState().sessions.s1 };
}

describe("ANDROID-REPLAY-1: opening a long chat", () => {
  it("never shows the session list card an old state or title from the replayed history", async () => {
    const { seen } = await openChatOverSlowLink(8);
    // Today: the card shows RUNNING / "old title N" while the replay walks through history (store.ts applyToView).
    expect(seen.map((x) => `${x.state} ${x.title}`)).toEqual(seen.map(() => "IDLE current title"));
  });

  it("shows the chat once, complete, not rebuilt over many renders", async () => {
    const { seen, final } = await openChatOverSlowLink(8);
    // Today: the transcript grows part by part (8 renders of a partial chat): the "fast forward".
    const partial = seen.filter((x) => x.items > 0 && x.items < final.items.length);
    expect(partial).toEqual([]);
    expect(final.items.length).toBeGreaterThan(0); // and it is shown in full in the end
    expect(final.cursor).toBe(80);
  });
});

describe("ANDROID-REPLAY-1: older turns on demand", () => {
  it("asks the latest turns first, then the page before the oldest shown, and puts it above", async () => {
    const sent: Envelope[] = [];
    let h: TransportHandlers | null = null;
    const factory = () => ({ open(x: TransportHandlers) { h = x; }, send(t: string) { sent.push(JSON.parse(t)); }, close() {} });
    store = createDaemonStore({ url: "ws://test/ws", transport: factory, settings: { flushFallbackMs: 1, replayTurns: 1 } });
    const s = store;
    s.getState().connect();
    h!.onOpen();
    const push = (e: Envelope) => h!.onText(JSON.stringify(e));
    push({ type: "ready", data: "instance=i1 since=0" });
    push({ type: "sessions", data: JSON.stringify([rec("s1", "IDLE", "t")]) });
    await tick();
    s.getState().requestHistory("s1");
    expect(JSON.parse(sent.at(-1)!.data)).toEqual({ lastSequence: 0, turns: 1 });
    expect(s.getState().sessions.s1.loadingHistory).toBe(true);
    push({ type: "turn", sessionId: "s1", sequence: 3, data: "second" });
    push({ type: "text_delta", sessionId: "s1", sequence: 4, data: "b" });
    push({ type: "replay_done", sessionId: "s1", data: JSON.stringify({ fromSequence: 3, toSequence: 4, hasMore: true }) });
    await tick();
    let v = s.getState().sessions.s1;
    expect([v.loadingHistory, v.hasOlder, v.oldestSequence, v.cursor]).toEqual([false, true, 3, 4]);
    expect(v.items.map((i) => i.kind)).toEqual(["user", "text"]); // no "output missing" above a window
    s.getState().requestOlder("s1");
    expect(JSON.parse(sent.at(-1)!.data)).toEqual({ lastSequence: 0, beforeSequence: 3, turns: 1 });
    push({ type: "turn", sessionId: "s1", sequence: 1, data: "first" });
    push({ type: "text_delta", sessionId: "s1", sequence: 5, data: "c" }); // live, while the page loads
    push({ type: "text_delta", sessionId: "s1", sequence: 2, data: "a" });
    push({ type: "replay_done", sessionId: "s1", data: JSON.stringify({ fromSequence: 1, toSequence: 2, hasMore: false }) });
    await tick();
    v = s.getState().sessions.s1;
    expect(v.items.map((i) => (i as { text?: string }).text)).toEqual(["first", "a", "second", "bc"]);
    expect([v.hasOlder, v.oldestSequence, v.cursor]).toEqual([false, 1, 5]);
  });
});
