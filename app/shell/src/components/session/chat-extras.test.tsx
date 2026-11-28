// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// PARITY-AUDIT-1 #8: to-do box, report-back chip, Start / Resume in the empty pane, `chat.message.actions` slot.
import { describe, expect, it, vi } from "vitest";
import { createElement, type ReactElement } from "react";
import { renderToString } from "react-dom/server";
import { applyToView, createSessionActions, type Envelope, type SessionRecord, type SessionView } from "@infantry/client";

const fills = vi.hoisted(() => ({ list: [] as unknown[] }));
vi.mock("@/plugins/web-plugins", () => ({
  useSlotFills: (slot: string) => (slot === "chat.message.actions" ? fills.list : []),
  // The test plugin's view: one button per message it is handed.
  PluginSlot: ({ props }: { props: { message: { id: string } } }) => createElement("button", { "data-plugin-action": props.message.id }, "Pin"),
}));

const { TodoBox, ReportChip, EmptyPaneActions, MessageActions, paneActionOf, todoSummary } = await import("./chat-extras");

const rec = (over: Partial<SessionRecord> = {}): SessionRecord =>
  ({ id: "s1", provider: "claude", workingDir: ".", spawnedAt: 0, lastHeartbeat: 0, state: "IDLE", ...over }) as SessionRecord;
const view = (): SessionView => ({ record: rec(), items: [], cursor: 0, status: null, usage: {}, lastLine: "", activityAt: 0, activityLog: [], historyAsked: false });
const status = (seq: number, body: object): Envelope => ({ type: "status", sessionId: "s1", sequence: seq, data: JSON.stringify(body) });

describe("to-do box", () => {
  it("draws nothing without todos", () => {
    expect(renderToString(createElement(TodoBox, { sessionId: "s1", todos: undefined }))).toBe("");
    expect(renderToString(createElement(TodoBox, { sessionId: "s1", todos: [] }))).toBe("");
  });

  it("renders the todos and updates from the status frames", () => {
    let v = applyToView(view(), [status(1, { state: "BUSY", todos: [{ text: "Read", status: "completed" }, { text: "Fix", status: "in_progress", activeText: "Fixing" }] })]);
    let html = renderToString(createElement(TodoBox, { sessionId: "s1", todos: v.todos }));
    expect(html).toContain("1/2 done");
    expect(html).toContain("Fixing");
    // A status without todos keeps the list; a later list replaces it.
    v = applyToView(v, [status(2, { state: "IDLE" })]);
    expect(v.todos).toHaveLength(2);
    v = applyToView(v, [status(3, { state: "BUSY", todos: [{ text: "Read", status: "completed" }, { text: "Fix", status: "completed" }, { text: "Test", status: "pending" }] })]);
    html = renderToString(createElement(TodoBox, { sessionId: "s1", todos: v.todos }));
    expect(html).toContain("2/3 done");
    expect(html).toContain("Test");
    expect(todoSummary(v.todos!)).toBe("2/3 done");
  });
});

describe("report-back chip", () => {
  it("shows for an unreported session, muted when reported, nothing otherwise", () => {
    const r = (status: "pending" | "reported" | "unreported") => ({ status, traitId: "t", reprompted: false, checkedRun: 0, reportMessageId: null, attentionId: null });
    expect(renderToString(createElement(ReportChip, { report: r("unreported") }))).toContain("unreported");
    expect(renderToString(createElement(ReportChip, { report: r("reported") }))).toContain("reported");
    expect(renderToString(createElement(ReportChip, { report: r("pending") }))).toBe("");
    expect(renderToString(createElement(ReportChip, { report: null }))).toBe("");
  });
});

describe("Start / Resume in the empty pane", () => {
  it("offers Start without a conversation, Resume with one (where resume runs), nothing mid-turn", () => {
    expect(paneActionOf(rec(), true)).toBe("start");
    expect(paneActionOf(rec({ providerSessionId: "c1" }), true)).toBe("resume");
    expect(paneActionOf(rec({ providerSessionId: "c1" }), false)).toBeNull();
    expect(paneActionOf(rec({ turnActive: true }), true)).toBeNull();
  });

  it("Start sends session_restart and Resume sends the resume `new` frame", () => {
    const sent: Envelope[] = [];
    const link = { sendFrame: (e: Envelope) => (sent.push(e), true), onFrame: () => () => {} };
    const onStart = () => void createSessionActions(link).restart("s1").catch(() => {});
    const onResume = () => link.sendFrame({ type: "new", data: JSON.stringify({ resume: "s1" }) });
    const start = EmptyPaneActions({ action: "start", onStart, onResume }) as ReactElement<{ onClick: () => void }>;
    start.props.onClick();
    const resume = EmptyPaneActions({ action: "resume", onStart, onResume }) as ReactElement<{ onClick: () => void }>;
    resume.props.onClick();
    expect(sent.map((e) => e.type)).toEqual(["session_restart", "new"]);
    expect(JSON.parse(sent[0].data)).toMatchObject({ sessionId: "s1" });
    expect(JSON.parse(sent[1].data)).toEqual({ resume: "s1" });
    expect(EmptyPaneActions({ action: null, onStart, onResume })).toBeNull();
  });
});

describe("chat.message.actions slot", () => {
  const text = { kind: "text" as const, id: "m1", seq: 1, text: "hello" };
  it("draws nothing while no plugin fills it", () => {
    fills.list = [];
    expect(renderToString(createElement(MessageActions, { sessionId: "s1", item: text }))).toBe("");
  });
  it("a test plugin's button shows on a message, not on a tool row", () => {
    fills.list = [{ plugin: "test-plugin", contribution: { id: "pin", slot: "chat.message.actions" } }];
    expect(renderToString(createElement(MessageActions, { sessionId: "s1", item: text }))).toContain('data-plugin-action="m1"');
    const tools = { kind: "tools" as const, id: "t1", seq: 2, count: 1, tool: "Read", activity: "x" };
    expect(renderToString(createElement(MessageActions, { sessionId: "s1", item: tools }))).toBe("");
  });
});
