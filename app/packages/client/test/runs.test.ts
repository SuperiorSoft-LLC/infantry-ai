// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import { describe, expect, it } from "vitest";
import { createRunsStore, createSessionActions, encodeBase64, runFeed, type Envelope } from "../src";

function link() {
  const sent: Envelope[] = [];
  const taps = new Set<(e: Envelope) => void>();
  return {
    sent,
    sendFrame: (e: Envelope) => (sent.push(e), true),
    onFrame: (l: (e: Envelope) => void) => (taps.add(l), () => void taps.delete(l)),
    push: (e: Envelope) => taps.forEach((t) => t(e)),
  };
}
const text = (b: Uint8Array) => new TextDecoder().decode(b);

describe("run feed", () => {
  it("resets on replay and skips live chunks already in it", () => {
    const f = runFeed();
    expect(f.accept({ runId: "r", sequence: 1, data: encodeBase64("early"), replay: false }).kind).toBe("write");
    const r = f.accept({ runId: "r", sequence: 5, data: encodeBase64("kept"), replay: true });
    expect(r.kind).toBe("reset");
    expect(r.kind !== "skip" && text(r.bytes)).toBe("kept");
    expect(f.accept({ runId: "r", sequence: 5, data: encodeBase64("dup"), replay: false }).kind).toBe("skip");
    const w = f.accept({ runId: "r", sequence: 6, data: encodeBase64("é live"), replay: false });
    expect(w.kind !== "skip" && text(w.bytes)).toBe("é live");
  });
});

describe("runs store", () => {
  it("lists commands and runs, folds run frames and shows refusals", () => {
    const l = link();
    const s = createRunsStore(l);
    s.getState().refresh();
    expect(l.sent.map((e) => e.type)).toEqual(["command_list", "run_list"]);
    l.push({ type: "commands", data: JSON.stringify([{ id: "build", name: "Build", argv: ["make"], rooted: false, detached: false, branch: "" }]) });
    expect(s.getState().commands[0].id).toBe("build");
    s.getState().start("build");
    expect(l.sent[2]).toMatchObject({ type: "run_start", data: '{"commandId":"build"}' });
    l.push({ type: "run", data: JSON.stringify({ runId: "r1", commandId: "build", state: "RUNNING", startedAt: 1, lastSequence: 0, terminal: true, interactive: false }) });
    expect(s.getState().runs[0]).toMatchObject({ runId: "r1", state: "RUNNING" });
    l.push({ type: "run_finished", data: JSON.stringify({ runId: "r1", exitCode: 0, outcome: "SUCCEEDED", endedAt: 2 }) });
    expect(s.getState().runs[0]).toMatchObject({ state: "FINISHED", exitCode: 0 });
    s.getState().stop("r1", true);
    expect(l.sent.at(-1)).toMatchObject({ type: "run_stop", data: "r1 force" });
    const got: number[] = [];
    s.getState().onBytes("r1", (b) => got.push(b.sequence));
    l.push({ type: "run_bytes", data: JSON.stringify({ runId: "r1", sequence: 3, data: "", replay: false }) });
    expect(got).toEqual([3]);
    l.push({ type: "error", data: "command_save refused: no argv" });
    expect(s.getState().refusal).toBe("command_save refused: no argv");
  });

  it("asks and keeps a project's branches", () => {
    const l = link();
    const s = createRunsStore(l);
    s.getState().listBranches("p1");
    expect(l.sent[0]).toMatchObject({ type: "run_branches", data: '{"project":"p1"}' });
    l.push({ type: "run_branches", data: JSON.stringify({ project: "p1", branches: [{ name: "main", worktree: null }, { name: "ticket/x", worktree: "/w/x" }] }) });
    expect(s.getState().branches.p1.map((b) => b.name)).toEqual(["main", "ticket/x"]);
  });
});

describe("session actions", () => {
  it("answers stats and rejects a mid-turn restart with the daemon text", async () => {
    const l = link();
    const a = createSessionActions(l);
    const st = a.stats("s1");
    l.push({ type: "session_stats", data: JSON.stringify({ sessionId: "s1", harness: "claude", toolUses: 4 }), requestId: l.sent[0].requestId });
    expect((await st).toolUses).toBe(4);
    const r = a.restart("s1");
    expect(JSON.parse(l.sent[1].data)).toEqual({ sessionId: "s1", force: "false" });
    l.push({ type: "error", data: "session_restart refused: s1 is mid-turn" });
    await expect(r).rejects.toContain("mid-turn");
  });
});
