// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// PARITY-AUDIT-1 #13: the chat settings cards change the chat (defaults = the behaviour before the cards).
import { beforeEach, describe, expect, it, vi } from "vitest";
import { createElement } from "react";
import { renderToString } from "react-dom/server";
import { settingKey, type TranscriptItem } from "@infantry/client";

const st = vi.hoisted(() => ({ values: {} as Record<string, unknown>, turns: [] as number[] }));
vi.mock("@/components/settings/use-settings", () => ({
  useSettings: (sel: (s: { values: Record<string, unknown> }) => unknown) => sel({ values: st.values }),
  settingsStore: () => ({ getState: () => ({ ensureLoaded() {} }) }),
}));
vi.mock("@/daemon/daemon", () => ({
  useDaemon: (sel: (s: unknown) => unknown) => sel({ sessions: { s1: { activityLog: [{ at: 0, label: "ran ls" }] } } }),
  daemonStore: () => ({ getState: () => ({ setReplayTurns: (n: number) => st.turns.push(n) }) }),
}));

vi.mock("@/plugins/web-plugins", () => ({ useSlotFills: () => [], PluginView: () => null }));

const { Markdown } = await import("./markdown");
const { ActivityBox } = await import("./activity-box");
const { ReasoningPanel } = await import("./transcript");
const { CHAT_NODE } = await import("./chat-settings");

const set = (key: string, v: unknown) => (st.values = { ...st.values, [settingKey(CHAT_NODE, key)]: v });
beforeEach(() => (st.values = {}));

const card = "```card:note id=a\n{\"title\":\"Hello card\"}\n```\n\n```card:note id=a\n{\"title\":\"Hello again\"}\n```";

describe("chat cards", () => {
  it("draws cards by default and the raw block when off", () => {
    expect(renderToString(createElement(Markdown, { text: card }))).not.toContain("card:note");
    set("chat.cards", false);
    expect(renderToString(createElement(Markdown, { text: card }))).toContain("Hello card");
    expect(renderToString(createElement(Markdown, { text: card }))).not.toContain("Updated the");
  });
  it("the update line follows its setting", () => {
    expect(renderToString(createElement(Markdown, { text: card }))).toContain("Updated the");
    set("chat.cardUpdateLine", false);
    expect(renderToString(createElement(Markdown, { text: card }))).not.toContain("Updated the");
  });
});

describe("activity box", () => {
  it("starts collapsed by default, open when set", () => {
    expect(renderToString(createElement(ActivityBox, { sessionId: "s1", rowless: true }))).not.toContain("ran ls");
    set("chat.activityBox.open", true);
    expect(renderToString(createElement(ActivityBox, { sessionId: "s1", rowless: true }))).toContain("ran ls");
  });
});

describe("reasoning", () => {
  it("the side panel lists the thinking", () => {
    const t = { id: "t1", kind: "thinking", count: 1, text: "pondering" } as unknown as TranscriptItem;
    expect(renderToString(createElement(ReasoningPanel, { thoughts: [t] }))).toContain("Thinking (");
    expect(renderToString(createElement(ReasoningPanel, { thoughts: [] }))).toContain("No reasoning yet");
  });
});
