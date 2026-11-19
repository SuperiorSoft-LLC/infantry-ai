// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import { describe, expect, it } from "vitest";
import { searchItems, type TranscriptItem } from "../src";

describe("transcript search", () => {
  it("finds items by text, tool and path, ignoring case", () => {
    const items = [
      { kind: "user", id: "a", seq: 1, at: 0, text: "Fix the Login bug" },
      { kind: "text", id: "b", seq: 2, text: "done" },
      { kind: "edit", id: "c", seq: 3, path: "src/login.ts", added: 1, removed: 0 },
    ] as unknown as TranscriptItem[];
    expect(searchItems(items, "login")).toEqual([0, 2]);
    expect(searchItems(items, "  ")).toEqual([]);
    expect(searchItems(items, "nope")).toEqual([]);
  });
});
