// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import { beforeEach, describe, expect, it } from "vitest";
import { chatViewOf, composerStatusLine, composerToolsThatFit, insertAt, patchChatView, resetChatViews, splitComposerTools, toggleExpanded, toggleMinimized } from "../src";

beforeEach(resetChatViews);

describe("chat composer view state", () => {
  it("minimize then restore keeps the draft", () => {
    patchChatView("main", { draft: "half-written prompt" });
    toggleMinimized("main");
    expect(chatViewOf("main")).toMatchObject({ minimized: true, draft: "half-written prompt" });
    toggleMinimized("main");
    expect(chatViewOf("main")).toMatchObject({ minimized: false, draft: "half-written prompt" });
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
