// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import { describe, expect, it } from "vitest";
import { callTool, commentsOf, matchSlash, slashCatalog, slashPrefix, type Envelope } from "../src";

describe("ticket comments", () => {
  it("reads the Kotlin comment lines, multi-line bodies included", () => {
    const text = "a1b2c3d4 note/open by pm 2026-11-03 20:41 -05:00: first\nsecond line\ne5f6a7b8 question/resolved by owner 2026-11-04 09:00 +00:00: ok?";
    expect(commentsOf(text)).toEqual([
      { id: "a1b2c3d4", kind: "note", state: "open", author: "pm", stamp: "2026-11-03 20:41 -05:00", body: "first\nsecond line" },
      { id: "e5f6a7b8", kind: "question", state: "resolved", author: "owner", stamp: "2026-11-04 09:00 +00:00", body: "ok?" },
    ]);
    expect(commentsOf("no comments")).toEqual([]);
  });

  it("calls a tool and resolves with its answer", async () => {
    const taps = new Set<(e: Envelope) => void>();
    const sent: Envelope[] = [];
    const link = { sendFrame: (e: Envelope) => (sent.push(e), true), onFrame: (l: (e: Envelope) => void) => (taps.add(l), () => void taps.delete(l)) };
    const p = callTool(link, "infantry_ticket_comments", { ticketId: "t1" });
    expect(JSON.parse(sent[0].data)).toEqual({ name: "infantry_ticket_comments", arguments: { ticketId: "t1" } });
    taps.forEach((t) => t({ type: "plugin_tool_result", requestId: sent[0].requestId, data: '{"text":"no comments","isError":false}' }));
    expect(await p).toEqual({ text: "no comments", isError: false });
    expect(taps.size).toBe(0);
  });
});

describe("slash commands", () => {
  it("completes by prefix from the session's catalog", async () => {
    expect(slashPrefix("/mo")).toBe("mo");
    expect(slashPrefix("/")).toBe("");
    expect(slashPrefix("/model x")).toBeNull();
    expect(slashPrefix("hi /mo")).toBeNull();
    const taps = new Set<(e: Envelope) => void>();
    const sent: Envelope[] = [];
    const link = { sendFrame: (e: Envelope) => (sent.push(e), true), onFrame: (l: (e: Envelope) => void) => (taps.add(l), () => void taps.delete(l)) };
    const p = slashCatalog(link, "s1");
    expect(sent[0]).toMatchObject({ type: "command_catalog", sessionId: "s1" });
    const commands = [{ name: "model", action: "SET_MODEL" }, { name: "effort", action: "SET_EFFORT" }, { name: "Mcp", action: "NATIVE" }];
    taps.forEach((t) => t({ type: "command_catalog", requestId: sent[0].requestId, data: JSON.stringify({ provider: "claude", prefix: "", commands }) }));
    const got = await p;
    expect(matchSlash(got, "m").map((c) => c.name)).toEqual(["model", "Mcp"]);
  });
});
