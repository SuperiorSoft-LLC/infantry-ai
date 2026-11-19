// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Calling a daemon tool from the UI: `plugin_tool_call` {name, arguments} answers `plugin_tool_result` {text, isError}
// under the same request id. Ticket comments go this way (`infantry_ticket_comments` lists them in the Kotlin line
// format, `infantry_ticket_comment` adds or resolves one); `commentsOf` reads those lines. `askFrame` is the generic
// request/answer wait; the composer's `/` completion reads `command_catalog` through it.
import type { Envelope } from "./wire";
import type { FrameLink } from "./stores/link";

/** How long a tool call waits for its answer (ms). */
export const TOOL_CALL_TIMEOUT_MS = 15_000;

export interface ToolResult {
  text: string;
  isError: boolean;
}

let calls = 0;

/** One request's answer: the first frame under its request id (an `error` frame is a refusal). */
export type FrameAnswer = { ok: true; env: Envelope } | { ok: false; text: string };

/** Sends [req] with a fresh request id and resolves with the first frame answering it (or a refusal / timeout). */
export function askFrame(link: FrameLink, req: Omit<Envelope, "requestId">, timeoutMs = TOOL_CALL_TIMEOUT_MS): Promise<FrameAnswer> {
  calls += 1;
  const requestId = `tool:${Date.now().toString(36)}-${calls.toString(36)}`;
  return new Promise((resolve) => {
    let off = () => {};
    const done = (a: FrameAnswer) => (off(), clearTimeout(timer), resolve(a));
    const timer = setTimeout(() => done({ ok: false, text: `${req.type}: no answer from the daemon` }), timeoutMs);
    off = link.onFrame((env: Envelope) => {
      if (env.requestId !== requestId) return;
      done(env.type === "error" ? { ok: false, text: env.data } : { ok: true, env });
    });
    if (!link.sendFrame({ ...req, requestId })) done({ ok: false, text: `${req.type} not sent: the daemon link is down` });
  });
}

/** Calls daemon tool [name]; resolves with its text, or isError when it was refused, unsent or timed out. */
export async function callTool(link: FrameLink, name: string, args: Record<string, unknown>, timeoutMs = TOOL_CALL_TIMEOUT_MS): Promise<ToolResult> {
  const a = await askFrame(link, { type: "plugin_tool_call", data: JSON.stringify({ name, arguments: args }) }, timeoutMs);
  if (!a.ok) return { text: a.text, isError: true };
  try {
    const r = JSON.parse(a.env.data) as Partial<ToolResult>;
    return { text: r.text ?? "", isError: !!r.isError };
  } catch {
    return { text: a.env.data, isError: true };
  }
}

/** One `/` command a harness offers (`command_catalog`, Kotlin `HarnessCommand`). */
export interface SlashCommand {
  name: string;
  description?: string;
  /** SET_MODEL, SET_EFFORT or NATIVE. */
  action?: string;
  argument?: string | null;
  values?: string[];
}

/** [sessionId]'s provider's `/` commands (`command_catalog` {} on the session); none when refused. */
export async function slashCatalog(link: FrameLink, sessionId: string): Promise<SlashCommand[]> {
  const a = await askFrame(link, { type: "command_catalog", data: "{}", sessionId });
  if (!a.ok) return [];
  try {
    const list = (JSON.parse(a.env.data) as { commands?: SlashCommand[] }).commands;
    return Array.isArray(list) ? list.filter((c) => typeof c?.name === "string") : [];
  } catch {
    return [];
  }
}

/** The word after a leading `/` while the composer holds only that (no space, one line), else null. */
export function slashPrefix(text: string): string | null {
  const m = /^\/([\w-]*)$/.exec(text);
  return m ? m[1] : null;
}

/** [commands] whose name starts with [prefix] (case-insensitive), in the catalog's order. */
export function matchSlash(commands: readonly SlashCommand[], prefix: string): SlashCommand[] {
  const p = prefix.toLowerCase();
  return commands.filter((c) => c.name.toLowerCase().startsWith(p));
}

export interface TicketComment {
  id: string;
  kind: string;
  state: string;
  author: string;
  /** Local time with its offset, as the daemon wrote it ("2026-11-03 20:41 -05:00"). */
  stamp: string;
  body: string;
}

const COMMENT_LINE = /^(\S+) ([\w-]+)\/([\w-]+) by (.+?) (\d{4}-\d{2}-\d{2} \d{2}:\d{2} [+-]\d{2}:\d{2}): ?(.*)$/;

/** The comments in an `infantry_ticket_comments` answer; a line that does not start a comment continues the body. */
export function commentsOf(text: string): TicketComment[] {
  const out: TicketComment[] = [];
  for (const line of text.split("\n")) {
    const m = COMMENT_LINE.exec(line);
    if (m) out.push({ id: m[1], kind: m[2], state: m[3], author: m[4], stamp: m[5], body: m[6] });
    else if (out.length) out[out.length - 1].body += `\n${line}`;
  }
  return out;
}
