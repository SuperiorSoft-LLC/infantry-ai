// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import { describe, expect, it } from "vitest";
import { cardOf, foldCards, parseMarkdown } from "../src";

describe("chat cards", () => {
  it("reads a card fence and rejects bad ones", () => {
    const [b] = parseMarkdown('```card:news id=a1 op=set\n{"title":"Hi"}\n```');
    expect(b).toMatchObject({ kind: "code", lang: "card:news id=a1 op=set" });
    expect(b.kind === "code" && cardOf(b.lang, b.text)).toEqual({ type: "news", id: "a1", op: "set", body: { title: "Hi" } });
    expect(cardOf("card:news", "[1]")).toBeNull();
    expect(cardOf("card:news bad", "{}")).toBeNull();
    expect(cardOf("card:news op=drop", "{}")).toBeNull();
    expect(cardOf("ts", "{}")).toBeNull();
    const [open] = parseMarkdown('```card:news\n{"title":"Hi"}');
    expect(open.kind === "code" && open.open).toBe(true);
    expect(cardOf("card:news", '{"a":1}', true)).toBeNull();
  });

  it("folds later fences of one id into the first", () => {
    const a = cardOf("card:t id=x", '{"items":[1],"s":"a","k":1}');
    const b = cardOf("card:t id=x op=append", '{"items":[2],"s":"b"}');
    const c = cardOf("card:t id=x op=set", '{"k":2}');
    const d = cardOf("card:t", '{"z":1}');
    const out = foldCards([a, b, c, d]);
    expect(out[0]).toMatchObject({ body: { items: [1, 2], s: "ab", k: 2 } });
    expect(out.slice(1, 3)).toEqual(["update", "update"]);
    expect(out[3]).toEqual(d);
  });
});
