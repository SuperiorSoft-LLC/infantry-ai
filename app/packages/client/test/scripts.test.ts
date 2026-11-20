// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import { describe, expect, it } from "vitest";
import { allTags, createScriptsStore, filterScripts, type Envelope, type ScriptRow } from "../src";

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

const row = (name: string, over: Partial<ScriptRow> = {}): ScriptRow => ({
  id: `id-${name}`, scope: "project", projectId: "p1", name, ext: "lua", title: name.toUpperCase(), description: `does ${name}`, tags: [], author: "pm",
  createdAt: 1, updatedAt: 2, enabled: true, version: 1, flag: null, size: 10,
  stats: { runs: 0, failures: 0, avgMs: null, lastRunAt: null, lastOk: null, lastError: null }, status: "never-run", hooks: [], ...over,
});

describe("scripts store", () => {
  it("loads the project's list, opens a script and refreshes on a change", () => {
    const l = link();
    const s = createScriptsStore(l);
    s.getState().setProject("p1");
    expect(l.sent[0].type).toBe("scripts_list");
    expect(JSON.parse(l.sent[0].data)).toEqual({ projectId: "p1" });
    l.push({ type: "scripts", data: JSON.stringify({ projectId: "other", scripts: [row("x")] }) });
    expect(s.getState().loaded).toBe(false);
    l.push({ type: "scripts", data: JSON.stringify({ projectId: "p1", scripts: [row("a"), row("b")] }) });
    expect(s.getState().scripts).toHaveLength(2);

    s.getState().open("id-a");
    l.push({ type: "scripts_item", data: JSON.stringify({ ...row("a"), body: "return 1", versions: [] }), requestId: l.sent[1].requestId });
    expect(s.getState().detail?.body).toBe("return 1");

    l.push({ type: "scripts_changed", data: JSON.stringify({ projectId: "p1" }) });
    expect(l.sent.slice(2).map((e) => e.type)).toEqual(["scripts_list", "scripts_get"]);
  });

  it("keeps a refusal and a run result for the requester only", () => {
    const l = link();
    const s = createScriptsStore(l);
    s.getState().setProject("p1");
    s.getState().save({ name: "n", title: "", description: "", body: "x", tags: [] });
    l.push({ type: "error", data: "scripts_save refused: a title is required", requestId: l.sent[1].requestId });
    expect(s.getState().refusal).toContain("title is required");
    l.push({ type: "error", data: "someone else", requestId: "zzz" });
    expect(s.getState().refusal).toContain("title is required");
    s.getState().run("n", "", true);
    expect(JSON.parse(l.sent[2].data)).toMatchObject({ name: "n", args: "{}", dryRun: true });
    l.push({ type: "scripts_result", data: JSON.stringify({ ok: true, text: "done", request: "scripts_run" }), requestId: l.sent[2].requestId });
    expect(s.getState().result?.text).toBe("done");
  });

  it("searches, filters and sorts", () => {
    const rows = [
      row("done-protocol", { tags: ["lanes"], stats: { runs: 9, failures: 0, avgMs: 5, lastRunAt: 100, lastOk: true, lastError: null }, status: "ok", hooks: [{ id: "h", on: "lane enter done", enabled: true, scope: "project" }] }),
      row("done-protocol-4", { flag: "likely a versioned copy of 'done-protocol'" }),
      row("flaky", { stats: { runs: 3, failures: 2, avgMs: 9, lastRunAt: 200, lastOk: false, lastError: "boom" }, status: "failing" }),
      row("off", { enabled: false, status: "disabled" }),
    ];
    const names = (o: Parameters<typeof filterScripts>[1]) => filterScripts(rows, o).map((r) => r.name);
    expect(names({ query: "lanes" })).toEqual(["done-protocol"]);
    expect(names({ query: "does flaky" })).toEqual(["flaky"]);
    expect(names({ filter: "failing" })).toEqual(["flaky"]);
    expect(names({ filter: "unused" })).toEqual(["DONE-PROTOCOL-4", "OFF"].map((n) => n.toLowerCase()));
    expect(names({ filter: "flagged" })).toEqual(["done-protocol-4"]);
    expect(names({ filter: "disabled" })).toEqual(["off"]);
    expect(names({ filter: "hooked" })).toEqual(["done-protocol"]);
    expect(names({ sort: "used" })[0]).toBe("done-protocol");
    expect(names({ sort: "recent" })[0]).toBe("flaky");
    expect(names({ sort: "failing" })[0]).toBe("flaky");
    expect(allTags(rows)).toEqual(["lanes"]);
  });
});
