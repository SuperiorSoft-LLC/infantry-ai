// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import { afterEach, describe, expect, it } from "vitest";
import { createDaemonStore, type DaemonStore, type Envelope, type TransportHandlers } from "../src";

/** A transport the test drives: it records what the client sent and plays daemon frames back. */
function fakeDaemon() {
  const sent: Envelope[] = [];
  let h: TransportHandlers | null = null;
  const factory = () => ({
    open(handlers: TransportHandlers) { h = handlers; },
    send(text: string) { sent.push(JSON.parse(text)); },
    close() {},
  });
  return {
    factory,
    sent,
    open: () => h?.onOpen(),
    drop: () => h?.onClose("test drop"),
    push: (env: Envelope) => h?.onText(JSON.stringify(env)),
  };
}

const tick = () => new Promise((r) => setTimeout(r, 5));
const rec = (id: string, state = "IDLE") => ({ id, provider: "stub", workingDir: "w", spawnedAt: 1, lastHeartbeat: 10, state });
let store: DaemonStore | null = null;
afterEach(() => store?.getState().disconnect());

function connected() {
  const d = fakeDaemon();
  store = createDaemonStore({ url: "ws://test/ws", transport: d.factory, settings: { flushFallbackMs: 1, sendAckSeconds: 1 } });
  store.getState().connect();
  d.open();
  return { d, s: store };
}

describe("daemon store", () => {
  it("asks history for every session after the burst and applies frames in sequence", async () => {
    const { d, s } = connected();
    d.push({ type: "ready", data: "provider=stub instance=i1 since=0" });
    d.push({ type: "sessions", data: JSON.stringify([rec("s1")]) });
    await tick();
    expect(d.sent.find((e) => e.type === "replay_request")).toMatchObject({ sessionId: "s1", data: '{"lastSequence":0}' });
    // Frames out of order: 2 is held until 1 arrives.
    d.push({ type: "text_delta", data: "b", sessionId: "s1", sequence: 2 });
    d.push({ type: "text_delta", data: "a", sessionId: "s1", sequence: 1 });
    d.push({ type: "text_delta", data: "a", sessionId: "s1", sequence: 1 }); // a replay overlap is dropped
    await tick();
    const v = s.getState().sessions.s1;
    expect(v.cursor).toBe(2);
    expect(v.items).toHaveLength(1);
    expect(v.items[0]).toMatchObject({ kind: "text", text: "ab" });
  });

  it("moves the cursor past an evicted range on replay_gap", async () => {
    const { d, s } = connected();
    d.push({ type: "ready", data: "instance=i1 since=0" });
    d.push({ type: "sessions", data: JSON.stringify([rec("s1")]) });
    d.push({ type: "replay_gap", sessionId: "s1", data: JSON.stringify({ requestedAfterSequence: 0, oldestAvailableSequence: 41, latestAvailableSequence: 296, evictedCount: 40 }) });
    d.push({ type: "text_delta", data: "late", sessionId: "s1", sequence: 41 });
    await tick();
    const v = s.getState().sessions.s1;
    expect(v.cursor).toBe(41);
    expect(v.items.map((i) => i.kind)).toEqual(["gap", "text"]);
  });

  it("resyncs from the cursor after a reconnect to the same instance, and starts over on a new one", async () => {
    const { d, s } = connected();
    d.push({ type: "ready", data: "instance=i1 since=0" });
    d.push({ type: "sessions", data: JSON.stringify([rec("s1")]) });
    d.push({ type: "text_delta", data: "x", sessionId: "s1", sequence: 1 });
    await tick();
    d.drop();
    await new Promise((r) => setTimeout(r, 300)); // first backoff is 250 ms
    d.open();
    d.sent.length = 0;
    d.push({ type: "ready", data: "instance=i1 since=0" });
    d.push({ type: "sessions", data: JSON.stringify([rec("s1")]) });
    await tick();
    expect(d.sent.find((e) => e.type === "replay_request")?.data).toBe('{"lastSequence":1}');
    d.push({ type: "ready", data: "instance=i2 since=0" });
    await tick();
    expect(s.getState().sessions.s1.cursor).toBe(0);
    expect(s.getState().sessions.s1.items).toHaveLength(0);
  });

  it("sends an idle session a prompt with a client id and queues for a busy one", async () => {
    const { d, s } = connected();
    d.push({ type: "ready", data: "instance=i1 since=0" });
    d.push({ type: "sessions", data: JSON.stringify([rec("idle"), rec("busy", "BUSY")]) });
    await tick();
    s.getState().send("idle", "hello");
    s.getState().send("busy", "later");
    const prompt = d.sent.find((e) => e.type === "prompt");
    expect(prompt).toMatchObject({ sessionId: "idle", data: "hello" });
    expect(prompt?.requestId).toMatch(/^send:/);
    expect(d.sent.find((e) => e.type === "queue_add")).toMatchObject({ sessionId: "busy", data: "later" });
    const id = prompt!.requestId!;
    expect(s.getState().pending[id].state).toBe("sending");
    d.push({ type: "prompt_ack", data: "accepted", requestId: id });
    await tick();
    expect(s.getState().pending[id].state).toBe("accepted");
    d.push({ type: "turn", data: "hello", sessionId: "idle", sequence: 1 });
    await tick();
    expect(s.getState().pending[id]).toBeUndefined();
  });

  it("queues a send the daemon refused as run_in_flight", async () => {
    const { d, s } = connected();
    d.push({ type: "ready", data: "instance=i1 since=0" });
    d.push({ type: "sessions", data: JSON.stringify([rec("s1")]) });
    await tick();
    s.getState().send("s1", "racing");
    const id = d.sent.find((e) => e.type === "prompt")!.requestId!;
    d.push({ type: "error", data: "run_in_flight s1: session s1 already has a run in flight", requestId: id });
    await tick();
    expect(s.getState().pending[id]).toBeUndefined();
    expect(d.sent.find((e) => e.type === "queue_add")).toMatchObject({ sessionId: "s1", data: "racing" });
  });

  it("CHAT-GONE-1: a send refused as session_gone marks the session gone, not working, and keeps the text", async () => {
    const { d, s } = connected();
    d.push({ type: "ready", data: "instance=i1 since=0" });
    d.push({ type: "sessions", data: JSON.stringify([rec("idle"), rec("ghost", "BUSY")]) });
    await tick();
    s.getState().send("idle", "hello there");
    const id = d.sent.find((e) => e.type === "prompt")!.requestId!;
    d.push({ type: "error", data: "session_gone idle: prompt refused: unknown session idle", sessionId: "idle", requestId: id });
    // a queue_add (busy ghost) has no requestId: the last text sent to it is kept
    s.getState().send("ghost", "are you there");
    d.push({ type: "error", data: "session_gone ghost: queue_add refused: unknown session ghost", sessionId: "ghost" });
    await tick();
    const st = s.getState();
    expect(st.pending[id]).toBeUndefined();
    expect(st.gone.idle).toEqual({ reason: "refused", text: "hello there" });
    expect(st.gone.ghost).toEqual({ reason: "refused", text: "are you there" });
    expect(st.sessions.ghost.record.state).toBe("EXITED");
    expect(st.sessions.ghost.record.turnActive).toBe(false);
    expect(st.takeGoneText("ghost")).toBe("are you there");
    expect(s.getState().takeGoneText("ghost")).toBeUndefined();
    expect(s.getState().gone.ghost).toEqual({ reason: "refused" });
  });

  it("CHAT-GONE-1: a session that leaves the list is gone, and not gone once listed again", async () => {
    const { d, s } = connected();
    d.push({ type: "ready", data: "instance=i1 since=0" });
    d.push({ type: "sessions", data: JSON.stringify([rec("a"), rec("b")]) });
    await tick();
    expect(s.getState().gone).toEqual({});
    d.push({ type: "sessions", data: JSON.stringify([rec("a")]) });
    await tick();
    expect(s.getState().gone).toEqual({ b: { reason: "removed" } });
    d.push({ type: "sessions", data: JSON.stringify([rec("a"), rec("b")]) });
    await tick();
    expect(s.getState().gone).toEqual({});
  });

  it("CHAT-GONE-2: a new daemon instance clears every gone mark", async () => {
    const { d, s } = connected();
    d.push({ type: "ready", data: "instance=i1 since=0" });
    d.push({ type: "sessions", data: JSON.stringify([rec("a"), rec("b")]) });
    await tick();
    s.getState().send("a", "too early");
    const id = d.sent.find((e) => e.type === "prompt")!.requestId!;
    d.push({ type: "error", data: "session_gone a: prompt refused: unknown session a", sessionId: "a", requestId: id });
    d.push({ type: "sessions", data: JSON.stringify([rec("a")]) });
    await tick();
    expect(Object.keys(s.getState().gone).sort()).toEqual(["a", "b"]);
    d.push({ type: "ready", data: "instance=i2 since=0" });
    d.push({ type: "sessions", data: JSON.stringify([rec("a"), rec("b")]) });
    await tick();
    expect(s.getState().gone).toEqual({});
  });

  it("CHAT-GONE-2: on the same instance a refused session stays gone while only listed; a sign of life clears it", async () => {
    const { d, s } = connected();
    d.push({ type: "ready", data: "instance=i1 since=0" });
    d.push({ type: "sessions", data: JSON.stringify([rec("a"), rec("b")]) });
    await tick();
    s.getState().send("a", "x");
    s.getState().send("b", "y");
    for (const e of d.sent.filter((e) => e.type === "prompt"))
      d.push({ type: "error", data: `session_gone ${e.sessionId}: prompt refused`, sessionId: e.sessionId, requestId: e.requestId });
    await tick();
    d.push({ type: "sessions", data: JSON.stringify([rec("a"), rec("b")]) }); // only listed: still gone
    await tick();
    expect(s.getState().gone.a?.reason).toBe("refused");
    expect(s.getState().gone.b?.reason).toBe("refused");
    d.push({ type: "sessions", data: JSON.stringify([{ ...rec("a"), lastHeartbeat: 11 }, rec("b")]) }); // newer heartbeat
    d.push({ type: "text_delta", data: "hi", sessionId: "b", sequence: 1 }); // a stream frame
    await tick();
    expect(s.getState().gone).toEqual({});
  });

  it("CHAT-GONE-2: the first list after READY (empty or partial) does not flash removed; it asks again and the next decides", async () => {
    const { d, s } = connected();
    d.push({ type: "ready", data: "instance=i1 since=0" });
    d.push({ type: "policy", data: "{}" });
    d.push({ type: "sessions", data: JSON.stringify([rec("a"), rec("b")]) });
    await tick();
    // reconnect to the same instance; the daemon sends ready, policy, sessions in that order
    d.push({ type: "ready", data: "instance=i1 since=0" });
    d.push({ type: "policy", data: "{}" });
    d.push({ type: "sessions", data: JSON.stringify([]) });
    await tick();
    expect(s.getState().gone).toEqual({});
    expect(Object.keys(s.getState().sessions).sort()).toEqual(["a", "b"]);
    expect(d.sent.filter((e) => e.type === "sessions").length).toBe(1);
    d.push({ type: "sessions", data: JSON.stringify([rec("a")]) });
    await tick();
    expect(s.getState().gone).toEqual({ b: { reason: "removed" } });
  });

  it("CHAT-GONE-2: an acknowledged send is never put back in the box by a later refusal", async () => {
    const { d, s } = connected();
    d.push({ type: "ready", data: "instance=i1 since=0" });
    d.push({ type: "sessions", data: JSON.stringify([rec("a"), rec("q", "BUSY")]) });
    await tick();
    s.getState().send("a", "hours ago");
    const id = d.sent.find((e) => e.type === "prompt")!.requestId!;
    d.push({ type: "prompt_ack", data: "", sessionId: "a", requestId: id });
    s.getState().send("q", "queued long ago");
    d.push({ type: "session", data: JSON.stringify(rec("q", "BUSY")), sessionId: "q", sequence: 1 }); // queue_add answered
    await tick();
    // much later the sessions are removed, and a queue_add (no requestId) is refused
    d.push({ type: "error", data: "session_gone a: queue_add refused: unknown session a", sessionId: "a" });
    d.push({ type: "error", data: "session_gone q: queue_add refused: unknown session q", sessionId: "q" });
    await tick();
    expect(s.getState().gone.a).toMatchObject({ reason: "refused", text: undefined });
    expect(s.getState().gone.q).toMatchObject({ reason: "refused", text: undefined });
    expect(s.getState().takeGoneText("a")).toBeUndefined();
  });

  it("marks a send with no answer as failed after sendAckSeconds", async () => {
    const { d, s } = connected();
    d.push({ type: "ready", data: "instance=i1 since=0" });
    d.push({ type: "sessions", data: JSON.stringify([rec("s1")]) });
    await tick();
    s.getState().send("s1", "anyone?");
    const id = d.sent.find((e) => e.type === "prompt")!.requestId!;
    await new Promise((r) => setTimeout(r, 1100));
    expect(s.getState().pending[id]).toMatchObject({ state: "failed" });
  });

  it("applies many frames in one state update", async () => {
    const { d, s } = connected();
    d.push({ type: "ready", data: "instance=i1 since=0" });
    d.push({ type: "sessions", data: JSON.stringify([rec("s1")]) });
    await tick();
    let updates = 0;
    const off = s.subscribe(() => updates++);
    for (let i = 1; i <= 500; i++) d.push({ type: "text_delta", data: "w ", sessionId: "s1", sequence: i });
    await tick();
    off();
    expect(updates).toBe(1);
    expect(s.getState().sessions.s1.items[0]).toMatchObject({ kind: "text" });
    expect((s.getState().sessions.s1.items[0] as { text: string }).text.length).toBe(1000);
  });
});

describe("a hole in the sequence", () => {
  it("is skipped after gapWaitMs, marked as missing, and the later frames show (also with no more frames coming)", async () => {
    const d = fakeDaemon();
    store = createDaemonStore({ url: "ws://test/ws", transport: d.factory, settings: { flushFallbackMs: 1, gapWaitMs: 30 } });
    store.getState().connect();
    d.open();
    d.push({ type: "ready", data: "instance=i1 since=0" });
    d.push({ type: "sessions", data: JSON.stringify([rec("s1")]) });
    d.push({ type: "text_delta", data: "a", sessionId: "s1", sequence: 1 });
    d.push({ type: "text_delta", data: "z", sessionId: "s1", sequence: 5777 }); // 2..5776 were lost on the daemon's disk
    await tick();
    expect(store.getState().sessions.s1.cursor).toBe(1);
    await new Promise((r) => setTimeout(r, 120));
    const v = store.getState().sessions.s1;
    expect(v.cursor).toBe(5777);
    expect(v.items.map((i) => i.kind)).toEqual(["text", "gap", "text"]);
  });
});
