// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import { describe, expect, it } from "vitest";
import { parseInline, parseMarkdown } from "../src";

describe("chat markdown", () => {
  it("reads headings, fenced code, lists, quotes, tables and paragraphs", () => {
    const md = "# Title\n\nSome **bold** and `code`.\n\n```ts\nconst a = 1;\n```\n\n- one\n- two\n\n3. three\n4. four\n\n> quoted\n\n| a | b |\n|---|--:|\n| 1 | 2 |";
    const kinds = parseMarkdown(md).map((b) => b.kind);
    expect(kinds).toEqual(["heading", "paragraph", "code", "list", "list", "quote", "table"]);
    const blocks = parseMarkdown(md);
    expect(blocks[2]).toEqual({ kind: "code", lang: "ts", text: "const a = 1;" });
    expect(blocks[4]).toMatchObject({ ordered: true, start: 3 });
    expect(blocks[6]).toMatchObject({ align: ["left", "right"] });
  });

  it("keeps unsafe links and unclosed markers as text", () => {
    expect(parseInline("[x](javascript:alert(1))")).toEqual([{ kind: "text", text: "[x](javascript:alert(1))" }]);
    expect(parseInline("[docs](https://a.b)")).toEqual([{ kind: "link", href: "https://a.b", spans: [{ kind: "text", text: "docs" }] }]);
    expect(parseInline("a * b and `open")).toEqual([{ kind: "text", text: "a * b and `open" }]);
    expect(parseInline("snake_case_name")).toEqual([{ kind: "text", text: "snake_case_name" }]);
  });
});
