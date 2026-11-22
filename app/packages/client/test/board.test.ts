// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import { describe, expect, it } from "vitest";
import { createBoardStore, createSettingsStore, laneOf, matchesFilter, NO_FILTER, searchGroups, settingKey, type Envelope } from "../src";

/** A recorded link: what the client sent, and a way to play daemon frames back. */
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
const t = (id: string, status: string, position: number, extra = {}) => ({ id, title: `T ${id}`, status, position, ...extra });
const board = [t("a", "todo", 0), t("b", "todo", 1), t("c", "todo", 2), t("d", "done", 0, { epicId: "e1", role: "backend" })];

function loaded() {
  const l = link();
  const s = createBoardStore(l);
  l.push({ type: "tickets", data: JSON.stringify(board) });
  return { l, s };
}

describe("board store", () => {
  it("loads the board from a tickets frame and groups it by lane in order", () => {
    const { s } = loaded();
    expect(s.getState().loaded).toBe(true);
    expect(laneOf(s.getState().tickets, "todo").map((x) => x.id)).toEqual(["a", "b", "c"]);
  });

  it("asks for the board once", () => {
    const l = link();
    const s = createBoardStore(l);
    s.getState().ensureLoaded();
    expect(l.sent.map((e) => e.type)).toEqual(["ticket_list"]);
  });

  it("asks once however many dialogs open before the answer, and not at all after READY asked", () => {
    const l = link();
    const s = createBoardStore(l);
    s.getState().ensureLoaded();
    s.getState().ensureLoaded();
    s.getState().ensureLoaded();
    expect(l.sent.filter((e) => e.type === "ticket_list")).toHaveLength(1);
    const l2 = link();
    const s2 = createBoardStore(l2);
    l2.push({ type: "ready", data: "{}" });
    s2.getState().ensureLoaded();
    expect(l2.sent).toHaveLength(0);
  });

  it("moves a ticket at once, renumbers both lanes, and keeps it when the daemon answers", () => {
    const { l, s } = loaded();
    s.getState().move("a", "done", 0);
    expect(laneOf(s.getState().tickets, "done").map((x) => x.id)).toEqual(["a", "d"]);
    expect(laneOf(s.getState().tickets, "todo").map((x) => [x.id, x.position])).toEqual([["b", 0], ["c", 1]]);
    const req = l.sent[0];
    expect(req.type).toBe("ticket_move");
    expect(JSON.parse(req.data)).toEqual({ id: "a", status: "done", position: 0 });
    l.push({ type: "ticket", requestId: req.requestId, data: JSON.stringify(t("a", "done", 0)) });
    expect(Object.keys(s.getState().inflight)).toEqual([]);
    expect(s.getState().refusal).toBeNull();
  });

  it("rolls a move back when the daemon refuses it", () => {
    const { l, s } = loaded();
    s.getState().move("a", "in_progress");
    expect(s.getState().tickets.a.status).toBe("in_progress");
    l.push({ type: "error", requestId: l.sent[0].requestId, data: "ticket_move refused: to start a ticket use start" });
    expect(s.getState().tickets.a).toEqual({ ...t("a", "todo", 0), partial: true });
    expect(laneOf(s.getState().tickets, "todo").map((x) => x.id)).toEqual(["a", "b", "c"]);
    expect(s.getState().refusal).toContain("use start");
  });

  it("asks for the compact list and loads the full ticket once", () => {
    const { l, s } = loaded();
    s.getState().loadFull("a");
    expect(l.sent.at(-1)).toMatchObject({ type: "ticket_get", data: "a" });
    l.push({ type: "ticket", data: JSON.stringify({ ...t("a", "todo", 0), description: "full" }) });
    expect(s.getState().tickets.a.partial).toBeUndefined();
    const n = l.sent.length;
    s.getState().loadFull("a");
    expect(l.sent.length).toBe(n);
  });

  it("reorders a lane and rolls back on refusal", () => {
    const { l, s } = loaded();
    s.getState().reorder("todo", ["c", "a", "b"]);
    expect(laneOf(s.getState().tickets, "todo").map((x) => x.id)).toEqual(["c", "a", "b"]);
    expect(JSON.parse(l.sent[0].data)).toEqual({ status: "todo", ids: ["c", "a", "b"] });
    l.push({ type: "error", requestId: l.sent[0].requestId, data: "ticket_reorder refused" });
    expect(laneOf(s.getState().tickets, "todo").map((x) => x.id)).toEqual(["a", "b", "c"]);
  });

  it("saves an edit at once and deletes with rollback", () => {
    const { l, s } = loaded();
    s.getState().save({ id: "b", title: "Renamed" });
    expect(s.getState().tickets.b.title).toBe("Renamed");
    l.push({ type: "error", requestId: l.sent[0].requestId, data: "ticket_save refused: nope" });
    expect(s.getState().tickets.b.title).toBe("T b");
    s.getState().remove("c");
    expect(s.getState().tickets.c).toBeUndefined();
    l.push({ type: "error", requestId: l.sent[1].requestId, data: "ticket_delete refused" });
    expect(s.getState().tickets.c).toBeDefined();
  });

  it("takes another client's push and filters", () => {
    const { l, s } = loaded();
    l.push({ type: "ticket", data: JSON.stringify(t("z", "backlog", 0, { epicId: "e1" })) });
    expect(s.getState().tickets.z.status).toBe("backlog");
    const all = Object.values(s.getState().tickets);
    expect(all.filter((x) => matchesFilter(x, { ...NO_FILTER, epic: "e1" })).map((x) => x.id).sort()).toEqual(["d", "z"]);
    expect(all.filter((x) => matchesFilter(x, { ...NO_FILTER, text: "T b" })).map((x) => x.id)).toEqual(["b"]);
  });

  it("rolls back when the link is down", () => {
    const l = link();
    l.sendFrame = () => false;
    const s = createBoardStore(l);
    l.push({ type: "tickets", data: JSON.stringify(board) });
    s.getState().move("a", "done");
    expect(s.getState().tickets.a.status).toBe("todo");
    expect(s.getState().refusal).toContain("link is down");
  });
});

const schema = {
  rev: 1,
  declarations: [
    { node: "Infantry_Notes", title: "Notes", tab: "Plugins", keywords: ["memory"], fields: [{ key: "notes.auto", type: "checkbox", label: "Auto save", default: true }] },
    { node: "shell", title: "Shell", fields: [{ key: "shell.zoom", type: "number", label: "Zoom", default: 1, min: 1, max: 3 }] },
  ],
};

describe("settings store", () => {
  it("loads schema then values and resolves defaults", () => {
    const l = link();
    const s = createSettingsStore(l);
    s.getState().ensureLoaded();
    expect(l.sent[0].type).toBe("config_get");
    l.push({ type: "config_schema", data: JSON.stringify(schema) });
    l.push({ type: "config_values", data: JSON.stringify({ rev: 2, rows: [{ nodeId: "shell", key: "shell.zoom", value: 2 }] }) });
    expect(s.getState().values[settingKey("shell", "shell.zoom")]).toBe(2);
    expect(searchGroups(s.getState().groups, "memory").map((g) => g.node)).toEqual(["Infantry_Notes"]);
  });

  it("writes optimistically and rolls back on a refusal", () => {
    const l = link();
    const s = createSettingsStore(l);
    l.push({ type: "config_schema", data: JSON.stringify(schema) });
    s.getState().set("shell", "shell.zoom", 9);
    expect(s.getState().values[settingKey("shell", "shell.zoom")]).toBe(9);
    expect(JSON.parse(l.sent[0].data)).toEqual({ node: "shell", key: "shell.zoom", value: 9 });
    l.push({ type: "error", data: "config_set refused: shell.zoom above max" });
    expect(settingKey("shell", "shell.zoom") in s.getState().values).toBe(false);
    expect(s.getState().refusal).toContain("above max");
  });

  it("keeps a write the daemon confirms with config_changed", () => {
    const l = link();
    const s = createSettingsStore(l);
    s.getState().set("shell", "shell.zoom", 2);
    l.push({ type: "config_changed", data: JSON.stringify({ node: "shell", key: "shell.zoom", value: 2, by: "owner", rev: 3 }) });
    expect(s.getState().inflight).toEqual({});
    expect(s.getState().values[settingKey("shell", "shell.zoom")]).toBe(2);
  });
});
