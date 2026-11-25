// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import { beforeEach, describe, expect, it } from "vitest";
import { chatKey, chatViewOf, composerStatusLine, draftOf, dropChatDraft, NEW_CHAT, setDraft, subscribeChatView, composerToolsThatFit, insertAt, resetChatViews, splitComposerTools, toggleExpanded, toggleMinimized } from "../src";

beforeEach(resetChatViews);

describe("chat composer view state", () => {
  it("minimize then restore keeps the draft", () => {
    setDraft("main", "s1", "half-written prompt");
    toggleMinimized("main");
    expect(chatViewOf("main").minimized).toBe(true);
    expect(draftOf("main", "s1")).toBe("half-written prompt");
    toggleMinimized("main");
    expect(draftOf("main", "s1")).toBe("half-written prompt");
  });

  it("expand toggles and is independent of minimize", () => {
    toggleExpanded("main");
    expect(chatViewOf("main").expanded).toBe(true);
    toggleMinimized("main");
    expect(chatViewOf("main")).toMatchObject({ expanded: true, minimized: true });
    toggleExpanded("main");
    expect(chatViewOf("main").expanded).toBe(false);
  });

  it("is remembered per window", () => {
    toggleMinimized("main");
    expect(chatViewOf("popout-1").minimized).toBe(false);
  });
});

describe("CHAT-DRAFT-1: a draft belongs to one chat in one window", () => {
  it("does not follow into another chat, and is there again on the way back", () => {
    setDraft("main", chatKey("A"), "for worker A");
    expect(draftOf("main", chatKey("B"))).toBe("");
    setDraft("main", chatKey("B"), "for B");
    expect(draftOf("main", chatKey("A"))).toBe("for worker A");
    expect(draftOf("main", chatKey("B"))).toBe("for B");
  });

  it("new agent has its own draft, apart from every session", () => {
    expect(chatKey(null)).toBe(NEW_CHAT);
    setDraft("main", chatKey("gone-1"), "words put back for a gone worker");
    expect(draftOf("main", chatKey(null))).toBe("");
    setDraft("main", chatKey(null), "first task");
    expect(draftOf("main", "gone-1")).toBe("words put back for a gone worker");
  });

  it("is per window: a popout of the same chat has its own", () => {
    setDraft("main", "A", "main text");
    expect(draftOf("popout-1", "A")).toBe("");
  });

  it("goes when sent (emptied) or when its chat is removed for good, in every window", () => {
    setDraft("main", "A", "x");
    setDraft("main", "A", "");
    expect(draftOf("main", "A")).toBe("");
    setDraft("main", "A", "y");
    setDraft("popout-1", "A", "z");
    setDraft("main", "B", "keep");
    dropChatDraft("A");
    expect(draftOf("main", "A")).toBe("");
    expect(draftOf("popout-1", "A")).toBe("");
    expect(draftOf("main", "B")).toBe("keep");
  });

  it("notifies only on a real change", () => {
    let n = 0;
    const off = subscribeChatView(() => n++);
    setDraft("main", "A", "a");
    setDraft("main", "A", "a");
    setDraft("main", "B", "");
    dropChatDraft("nope");
    off();
    expect(n).toBe(1);
  });
});

describe("composer layout rules (Kotlin CHAT-MINIMIZE-5)", () => {
  it("counts the 48px tools that fit beside the send group", () => {
    // 600 - 52 inset - 24 gap - 96 send = 428 -> 8 tools
    expect(composerToolsThatFit(600, 2)).toBe(8);
    expect(composerToolsThatFit(100, 2)).toBe(0);
    expect(composerToolsThatFit(600, 2, 100)).toBe(6);
  });

  it("puts what does not fit behind More, which takes the last slot", () => {
    expect(splitComposerTools(6, 8)).toEqual({ inline: 6, overflow: 0 });
    expect(splitComposerTools(6, 3)).toEqual({ inline: 2, overflow: 4 });
    expect(splitComposerTools(6, 0)).toEqual({ inline: 0, overflow: 6 });
  });

  it("writes the status line, leaving the mode out with no agents", () => {
    expect(composerStatusLine("bypass", 2, "idle")).toBe("permissions: bypass · 2 agents · idle");
    expect(composerStatusLine("bypass", 0, null)).toBe("0 agents");
    expect(composerStatusLine(null, 1, "working")).toBe("1 agent · working");
  });

  it("inserts at the caret", () => {
    expect(insertAt("ab", 1, "X")).toEqual({ text: "aXb", caret: 2 });
    expect(insertAt("ab", 9, "X")).toEqual({ text: "abX", caret: 3 });
  });
});
