// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// PARITY-AUDIT-1 #9: waiting chip, runner badge, related block, multi-select Move to epic.
import { describe, expect, it } from "vitest";
import { createElement } from "react";
import { renderToString } from "react-dom/server";
import type { BoardTicket, TicketDraft } from "@infantry/client";
import { RunnerBadgeView, WaitingChipView, runnerBadgeOf, waitingLabel } from "./card-chips";
import { RELATED_BEGIN, RELATED_END, RelatedBlock, relatedItems } from "./related-block";
import { inBoardOrder, isSelectClick, moveToEpic, selectionStore } from "./selection";

const t = (id: string, status: string, position: number, extra: Partial<BoardTicket> = {}): BoardTicket => ({ id, title: id, status, position, ...extra });
const MIN = 60_000;

describe("waiting chip", () => {
  const w = { reason: "owner reply", since: 0, untilAt: 10 * MIN, expiresAt: 15 * MIN, clearOnOutput: true, outputGraceMs: 0 };
  it("counts the wait while active and flags it once expired", () => {
    expect(waitingLabel(w, 3 * MIN)).toEqual({ text: "active, waiting 3m: owner reply", expired: false });
    expect(waitingLabel(w, 17 * MIN)).toEqual({ text: "wait over 7m ago? owner reply", expired: true });
    expect(renderToString(createElement(WaitingChipView, { w, now: 17 * MIN }))).toContain('data-waiting="expired"');
  });
});

describe("runner badge", () => {
  it("is absent on this machine and shows up, degraded or offline otherwise", () => {
    expect(runnerBadgeOf({ runner: null }, [])).toBeNull();
    expect(runnerBadgeOf({ runner: "local" }, ["box"])).toBeNull();
    expect(runnerBadgeOf({ runner: "box" }, ["box"], "BUSY")).toEqual({ runner: "box", health: "up" });
    expect(runnerBadgeOf({ runner: "box" }, ["box"], "FAILED")?.health).toBe("degraded");
    expect(runnerBadgeOf({ runner: "box" }, [], "BUSY")?.health).toBe("offline");
    expect(runnerBadgeOf({ runner: "any" }, [])?.health).toBe("up");
    const html = renderToString(createElement(RunnerBadgeView, { runner: "box", health: "degraded" }));
    expect(html).toContain('data-runner-health="degraded"');
    expect(html).toContain("box");
  });
});

describe("related block", () => {
  const desc = `${RELATED_BEGIN}\nRELATED (auto, 2 tickets; ...):\n- ticket-aaaaaaaa "First one" [done] (series) files: a.ts, b.ts\n- ticket-bbbbbbbb "Second" [backlog] (same files)\n${RELATED_END}\n\nhuman text`;
  it("reads the auto block and adds links not already listed, each ticket once", () => {
    const items = relatedItems(desc, [{ type: "relates_to", ticketId: "ticket-aaaaaaaa" }, { type: "blocked_by", ticketId: "ticket-cccccccc" }], { "ticket-cccccccc": t("ticket-cccccccc", "todo", 0) });
    expect(items.map((i) => i.ticketId)).toEqual(["ticket-aaaaaaaa", "ticket-bbbbbbbb", "ticket-cccccccc"]);
    expect(items[0]).toMatchObject({ title: "First one", status: "done", reason: "series", files: "a.ts, b.ts" });
    expect(items[2]).toMatchObject({ status: "todo", reason: "blocked by" });
    const html = renderToString(createElement(RelatedBlock, { items, onOpen: () => {} }));
    expect(html).toContain("Related tickets (3)");
    expect(html).toContain("aaaaaaaa");
  });
  it("renders nothing without related tickets", () => {
    expect(relatedItems("plain", undefined)).toEqual([]);
    expect(renderToString(createElement(RelatedBlock, { items: [] }))).toBe("");
  });
});

describe("multi-select Move to epic", () => {
  it("a modifier click selects; a plain click does not", () => {
    expect(isSelectClick({ ctrlKey: true, metaKey: false })).toBe(true);
    expect(isSelectClick({ ctrlKey: false, metaKey: false })).toBe(false);
    selectionStore.getState().toggle("x");
    selectionStore.getState().toggle("y");
    selectionStore.getState().toggle("x");
    expect(selectionStore.getState().ids).toEqual(["y"]);
    selectionStore.getState().clear();
  });
  it("moves all three selected tickets, in board order, with one epicId-only save each", () => {
    const tickets = Object.fromEntries(
      [t("c", "todo", 1), t("a", "backlog", 0), t("b", "todo", 0), t("z", "todo", 2, { epicId: "epic-1" })].map((x) => [x.id, x]),
    );
    const saves: TicketDraft[] = [];
    expect(moveToEpic(tickets, ["c", "b", "a"], "epic-1", (d) => saves.push(d))).toEqual(["a", "b", "c"]);
    expect(saves).toEqual([{ id: "a", epicId: "epic-1" }, { id: "b", epicId: "epic-1" }, { id: "c", epicId: "epic-1" }]);
    expect(inBoardOrder(tickets, ["gone", "z"])).toEqual(["z"]);
    expect(moveToEpic(tickets, ["z"], "epic-1", (d) => saves.push(d))).toEqual([]);
  });
});
