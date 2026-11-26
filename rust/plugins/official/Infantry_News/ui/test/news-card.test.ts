// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import { describe, expect, it } from "vitest";
import { headlines } from "../src/headlines";

describe("card:news body", () => {
  it("keeps the items with a title and a web link, trimmed, and drops the rest", () => {
    const list = headlines({
      items: [
        { title: " A ", url: "https://a.example/1", source: "Reuters", time: "2026-11-26T09:00:00Z", image: "https://i.example/a.jpg" },
        { title: "no link" },
        { title: "bad link", url: "javascript:alert(1)" },
        "text",
        null,
      ],
    });
    expect(list).toEqual([{ title: "A", url: "https://a.example/1", source: "Reuters", time: "2026-11-26T09:00:00Z", image: "https://i.example/a.jpg", icon: undefined }]);
    expect(headlines({})).toEqual([]);
  });
});
