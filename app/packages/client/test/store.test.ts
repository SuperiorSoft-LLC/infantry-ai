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
