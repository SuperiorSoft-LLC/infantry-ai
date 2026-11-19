// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Calling a daemon tool from the UI: `plugin_tool_call` {name, arguments} answers `plugin_tool_result` {text, isError}
// under the same request id. Ticket comments go this way (`infantry_ticket_comments` lists them in the Kotlin line
// format, `infantry_ticket_comment` adds or resolves one); `commentsOf` reads those lines.
import type { Envelope } from "./wire";
import type { FrameLink } from "./stores/link";

/** How long a tool call waits for its answer (ms). */
export const TOOL_CALL_TIMEOUT_MS = 15_000;

export interface ToolResult {
  text: string;
  isError: boolean;
}

let calls = 0;

/** Calls daemon tool [name]; resolves with its text, or isError when it was refused, unsent or timed out. */
export function callTool(link: FrameLink, name: string, args: Record<string, unknown>, timeoutMs = TOOL_CALL_TIMEOUT_MS): Promise<ToolResult> {
  calls += 1;
  const requestId = `tool:${Date.now().toString(36)}-${calls.toString(36)}`;
  return new Promise((resolve) => {
    let off = () => {};
    const done = (r: ToolResult) => (off(), clearTimeout(timer), resolve(r));
    const timer = setTimeout(() => done({ text: `${name}: no answer from the daemon`, isError: true }), timeoutMs);
    off = link.onFrame((env: Envelope) => {
      if (env.requestId !== requestId) return;
      if (env.type === "plugin_tool_result") {
        try {
          const r = JSON.parse(env.data) as Partial<ToolResult>;
          done({ text: r.text ?? "", isError: !!r.isError });
        } catch {
          done({ text: env.data, isError: true });
        }
      } else if (env.type === "error") done({ text: env.data, isError: true });
    });
    if (!link.sendFrame({ type: "plugin_tool_call", data: JSON.stringify({ name, arguments: args }), requestId })) {
      done({ text: `${name} not sent: the daemon link is down`, isError: true });
    }
  });
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
