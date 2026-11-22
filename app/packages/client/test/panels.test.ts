// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import { describe, expect, it } from "vitest";
import { byContributor, createNoticeKindsStore, heldKind, heldNotices, noticeBody, turnsSaved, createEpicsStore, createMcpStore, createScopedSettingsStore, isUnserved, reviewQueue, scopedBody, foldPod, type Envelope } from "../src";

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

describe("panel query stores", () => {
  it("takes only answers to its own request and folds a pushed epic", () => {
    const l = link();
    const s = createEpicsStore(l);
    l.push({ type: "epics", data: JSON.stringify([{ id: "x", projectId: "p", name: "other client's" }]), requestId: "someone-else" });
    expect(s.getState().loaded).toBe(false);
    s.getState().refresh("p");
    expect(l.sent[0]).toMatchObject({ type: "epic_list", data: "p" });
    l.push({ type: "epics", data: JSON.stringify([{ id: "e1", projectId: "p", name: "One" }]), requestId: l.sent[0].requestId });
    l.push({ type: "epic", data: JSON.stringify({ id: "e2", projectId: "p", name: "Two" }) });
    expect(s.getState().data.map((e) => e.id)).toEqual(["e1", "e2"]);
  });

  it("shows an unserved frame as a refusal, never as data", () => {
    const l = link();
    const s = createMcpStore(l);
    s.getState().refresh();
    l.push({ type: "error", data: "mcp_list refused: no unit serves this frame", requestId: l.sent[0].requestId });
    expect(s.getState().refusal).toContain("no unit serves");
    expect(isUnserved(s.getState().refusal)).toBe(true);
    expect(isUnserved("epic_save refused: a name is required")).toBe(false);
    expect(s.getState().data.servers).toEqual([]);
  });

  it("re-reads MCP on mcp_changed once loaded", () => {
    const l = link();
    const s = createMcpStore(l);
    s.getState().refresh();
    l.push({ type: "mcp_servers", data: JSON.stringify({ servers: [{ name: "a", source: "infantry", target: "claude", transport: "stdio", enabled: true, state: "ok" }] }), requestId: l.sent[0].requestId });
    expect(s.getState().data.servers).toHaveLength(1);
    l.push({ type: "mcp_changed", data: "{}" });
    expect(l.sent.filter((e) => e.type === "mcp_list")).toHaveLength(2);
  });

  it("keeps scoped values by key with their layers, and builds bodies without blank targets", () => {
    const l = link();
    const s = createScopedSettingsStore(l);
    expect(scopedBody("a.b", { project: "p", role: " " })).toBe(JSON.stringify({ key: "a.b", project: "p" }));
    expect(JSON.parse(scopedBody("a.b", { project: "p" }, { scope: "project", value: null }))).toEqual({ key: "a.b", project: "p", scope: "project", value: null });
    s.getState().send("settings_get", scopedBody("a.b", { project: "p" }));
    l.push({ type: "settings", data: JSON.stringify({ key: "a.b", value: 7, scope: "project", layers: [{ scope: "project", target: "p", value: 7 }] }), requestId: l.sent[0].requestId });
    expect(s.getState().data["a.b"]).toMatchObject({ value: 7, scope: "project" });
  });
});

describe("panel helpers", () => {
  it("groups the registry roles first and lists review nodes from known pods", () => {
    const a = (id: string, contributor: string) => ({ definition: { id }, contributor, source: "x", enabled: true });
    expect(byContributor([a("z", "Plug"), a("pm", "roles")]).map((g) => g.contributor)).toEqual(["roles", "Plug"]);
    const pods = foldPod({}, { pod: { id: "p1", goal: "Ship" }, nodes: [{ id: "n1", name: "api", state: "IN_REVIEW" }, { id: "n2", state: "RUNNING" }] })!;
    expect(reviewQueue(pods)).toEqual([{ podId: "p1", podGoal: "Ship", nodeName: "api", branch: undefined, workingDir: undefined, sessionId: undefined }]);
  });

  it("reads the daemon's notice kinds and writes one toggle as a scoped body", () => {
    const l = link();
    const s = createNoticeKindsStore(l);
    s.getState().refresh(noticeBody({ project: "p", role: "pm" }));
    expect(l.sent[0]).toMatchObject({ type: "notify_kinds", data: '{"project":"p","role":"pm"}' });
    const kinds = [{ kind: "build.success", description: "A green build", source: "builds", wake: false, checks: 4, wakes: 0, inbox: 3, duplicates: 1 }];
    l.push({ type: "notify_kinds_list", data: JSON.stringify({ setting: "notify.wake.kinds", value: null, effective: "x", from: "default", layers: [], kinds }), requestId: l.sent[0].requestId });
    expect(s.getState().data.kinds[0].kind).toBe("build.success");
    expect(turnsSaved(s.getState().data.kinds[0])).toBe(4);
    expect(JSON.parse(noticeBody({ project: "p" }, { kind: "build.failed", wake: true, scope: "project" }))).toEqual({ project: "p", kind: "build.failed", wake: true, scope: "project" });
  });

  it("filters held notices by kind and session and leaves the read ones out", () => {
    const item = (id: string, kind: string, outcome: string, sessionId: string | null, ackedAt: number | null, at: number) => ({ id, at, kind, triggerId: null, ticketId: null, podId: null, sessionId, outcome, detail: "", ackedAt });
    const items = Object.fromEntries(
      [item("a", "notice", "inbox:build.success", "s1", null, 1), item("b", "notice", "inbox:comment.note", "s2", null, 3), item("c", "notice", "inbox:build.success", "s1", 9, 2), item("d", "stalled", "x", "s1", null, 4)].map((i) => [i.id, i]),
    );
    expect(heldNotices(items).map((i) => i.id)).toEqual(["b", "a"]);
    expect(heldNotices(items, { kind: "build.success" }).map((i) => i.id)).toEqual(["a"]);
    expect(heldNotices(items, { session: "s2" }).map((i) => i.id)).toEqual(["b"]);
    expect(heldNotices(items, { read: true }).map((i) => i.id)).toEqual(["b", "c", "a"]);
    expect(heldKind(items.a)).toBe("build.success");
  });
});
