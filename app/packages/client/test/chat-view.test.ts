// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import { beforeEach, describe, expect, it } from "vitest";
import { chatViewOf, patchChatView, resetChatViews, toggleExpanded, toggleMinimized } from "../src";

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
