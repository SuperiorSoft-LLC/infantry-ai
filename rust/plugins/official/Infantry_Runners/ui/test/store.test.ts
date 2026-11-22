// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// RR-9: Add runner sends `runner_admin add` with the two values and the new row shows; an offline runner shows its reason.
import { describe, expect, it } from "vitest";
import type { Envelope } from "@infantry/ui-plugin-api";
import { createRunnersStore, joinHarnesses, plainText, pingLine, statusOf, summarizeProviders, summaryLine } from "../src/store";

function link(up = true) {
  const sent: Envelope[] = [];
  const taps = new Set<(e: Envelope) => void>();
  return {
    sent,
    sendFrame: (e: Envelope) => (up ? (sent.push(e), true) : false),
    onFrame: (l: (e: Envelope) => void) => (taps.add(l), () => void taps.delete(l)),
    push: (e: Envelope) => taps.forEach((t) => t(e)),
  };
}
const answer = (l: ReturnType<typeof link>, i: number, body: unknown) => l.push({ type: "runner_admin", requestId: l.sent[i].requestId, data: JSON.stringify(body) });
const LOCAL = { name: "local", kind: "embedded", state: "online", url: "", contract: "1.0" };

describe("the Runners store", () => {
  it("Add runner sends runner_admin add with the two values, then lists the new row", () => {
    const l = link();
    const s = createRunnersStore(l);
    s.getState().refresh();
    answer(l, 0, { ok: true, runners: [LOCAL] });
    s.getState().add(" wss://10.0.0.5:18991 ", " tok.abc ");
    expect(s.getState().adding).toBe(true);
    expect(l.sent[1]).toMatchObject({ type: "runner_admin" });
    expect(JSON.parse(l.sent[1].data)).toEqual({ op: "add", endpoint: "wss://10.0.0.5:18991", bearer: "tok.abc" });
    answer(l, 1, { ok: true, name: "box", url: "wss://10.0.0.5:18991", contract: "1.0" });
    expect(s.getState().adding).toBe(false);
    expect(JSON.parse(l.sent[2].data)).toEqual({ op: "list" });
    answer(l, 2, { ok: true, runners: [LOCAL, { name: "box", url: "wss://10.0.0.5:18991", state: "online", rttMs: 12, contract: "1.0" }] });
    expect(s.getState().runners.map((r) => r.name)).toEqual(["local", "box"]);
    expect(pingLine(s.getState().runners[1])).toBe("contract 1.0 · 12 ms");
    expect(JSON.stringify(s.getState())).not.toContain("tok.abc");
  });

  it("shows the daemon's refusal of an add as written, inside the dialog", () => {
    const l = link();
    const s = createRunnersStore(l);
    s.getState().add("http://x", "b");
    answer(l, 0, { ok: false, error: "the endpoint must start with wss://" });
    expect(s.getState().addError).toBe("the endpoint must start with wss://");
    expect(s.getState().adding).toBe(false);
    expect(s.getState().refusal).toBeNull();
  });

  it("an offline runner shows its reason; an outdated one too", () => {
    expect(statusOf({ name: "box", url: "wss://x", state: "offline", reason: "connection refused" })).toEqual({ dot: "error", word: "offline", reason: "connection refused" });
    expect(statusOf({ name: "box", url: "wss://x", state: "outdated", reason: "the runner moved: contract 2" }).reason).toBe("the runner moved: contract 2");
    expect(statusOf({ name: "local", url: "", state: "online" }).reason).toBeNull();
    expect(statusOf({ name: "box", url: "wss://x", state: "offline" }).reason).toBe("no answer");
  });

  it("check keeps the rows (pong included) and detach drops them", () => {
    const l = link();
    const s = createRunnersStore(l);
    s.getState().check("box");
    expect(JSON.parse(l.sent[0].data)).toEqual({ op: "check", name: "box" });
    answer(l, 0, { ok: true, name: "box", state: "online", rows: [{ id: "link", ok: true, detail: "pong from 'box' in 9 ms" }] });
    expect(s.getState().checks.box[0].detail).toContain("pong");
    expect(s.getState().checking).toBeNull();
    s.getState().detach("box");
    expect(JSON.parse(l.sent[2].data)).toEqual({ op: "detach", name: "box" });
    answer(l, 2, { ok: true, name: "box", remote: true });
    expect(s.getState().checks.box).toBeUndefined();
  });

  it("RR-11: Update runner sends runner_admin update, keeps the answer text and re-reads the list", () => {
    const l = link();
    const s = createRunnersStore(l);
    s.getState().update("box");
    expect(JSON.parse(l.sent[0].data)).toEqual({ op: "update", name: "box" });
    answer(l, 0, { ok: true, name: "box", text: "updating runner 'box' to img:0.4.7: started" });
    expect(s.getState().updateNote.box).toContain("started");
    expect(JSON.parse(l.sent[1].data)).toEqual({ op: "list" });
    answer(l, 1, { ok: true, runners: [{ name: "box", url: "wss://x", state: "outdated", outdated: true, needs: "1.11.0", update: { state: "running", steps: ["pulling img"], detail: "" } }] });
    expect(s.getState().runners[0].update?.state).toBe("running");
  });

  it("a down link is a refusal, not a silent no-op", () => {
    const s = createRunnersStore(link(false));
    s.getState().add("wss://a", "b");
    expect(s.getState().adding).toBe(false);
    expect(s.getState().refusal).toMatch(/link is down/);
  });
});

describe("RR-14 row summary", () => {
  it("reads each reachable runner's providers (local plain, a paired one through runner_frame) and keeps one line", async () => {
    const l = link();
    const s = createRunnersStore(l);
    s.getState().refresh();
    answer(l, 0, { ok: true, runners: [LOCAL, { name: "box", url: "wss://x", state: "online" }] });
    s.getState().summarize("local");
    s.getState().summarize("box");
    const asks = l.sent.slice(1).map((e) => (e.type === "runner_frame" ? JSON.parse(e.data) : { plain: e.type }));
    expect(asks.slice(2)).toEqual([{ runner: "box", frame: "providers", data: JSON.stringify({ refresh: true }) }, { runner: "box", frame: "harness_install_status", data: "{}" }]);
    l.push({ type: "providers", requestId: l.sent[3].requestId, data: JSON.stringify([{ id: "pi", available: true }, { id: "claude", available: false }]) });
    l.push({ type: "harness_install_status", requestId: l.sent[4].requestId, data: JSON.stringify({ harnesses: [{ id: "pi", state: "installed", path: "/x/pi" }, { id: "claude", state: "missing", path: null }] }) });
    await new Promise((r) => setTimeout(r, 0));
    expect(summaryLine(s.getState().summaries.box)).toBe("Tools: pi · signed in: yes");
    expect(summarizeProviders([{ id: "pi", available: false }], { harnesses: [{ id: "pi", state: "installed", path: "/x" }] })).toEqual({ tools: ["pi"], signedIn: false });
    expect(summaryLine({ tools: [], signedIn: false })).toBe("Tools: none installed · signed in: no");
  });
});

describe("RR-VIS detail", () => {
  it("joins install and providers into one harness row, so credential and harness never disagree", () => {
    const rows = joinHarnesses({ harnesses: [{ id: "claude", state: "installed", path: "/usr/bin/claude", version: "2.1" }, { id: "pi", state: "missing", path: null }] }, [{ id: "claude", available: false, unavailableReason: "Claude is not signed in. Screen: raw dump", loginAt: "~/.claude/.credentials.json" }]);
    expect(rows[0]).toMatchObject({ id: "claude", installed: true, signedIn: false, why: "Claude is not signed in.", loginAt: "~/.claude/.credentials.json" });
    expect(rows[1]).toMatchObject({ id: "pi", installed: false, signedIn: false, why: null });
    expect(joinHarnesses({ harnesses: [{ id: "claude", state: "installed", path: "/c" }] }, null, [{ id: "claude", loggedIn: true }])[0].signedIn).toBe(true);
    expect(plainText("a. Screen: junk")).toBe("a.");
  });
  it("the list row shows the plain reason when the daemon sends one", () => {
    expect(statusOf({ name: "box", url: "wss://x", state: "offline", reason: "os error 10061", reasonPlain: "nothing is listening at that address: the runner is stopped" }).reason).toBe("nothing is listening at that address: the runner is stopped");
  });
  it("reads the version the daemon sends (installedVersion)", () => {
    const rows = joinHarnesses({ harnesses: [{ id: "opencode", state: "installed", path: "/data/harness-tools/bin/opencode", installedVersion: "1.19.6" }] }, null);
    expect(rows[0].version).toBe("1.19.6");
  });
  it("never prints a bare unknown for tools", () => {
    expect(summaryLine({ tools: ["pi"], signedIn: false, signedInUnknown: true })).toContain("Update runner");
    expect(summaryLine({ tools: [], signedIn: false, error: "no route to host" })).toBe("Tools: not readable: no route to host");
  });
});
