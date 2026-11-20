// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// A ticket's comments in the ticket dialog, after the Kotlin ticket view: each comment's kind/state, author and local
// stamp over its body, then a box to add one. Both go through the board's tools (`infantry_ticket_comments` with
// which=all and detail=full, `infantry_ticket_comment`) over `plugin_tool_call`.
import { useCallback, useEffect, useState } from "react";
import { callTool, commentsOf, type TicketComment } from "@infantry/client";
import { Button, Chip, Label, Textarea } from "@infantry/ui";
import { daemonStore, useDaemon } from "@/daemon/daemon";
import { Markdown } from "@/components/session/markdown";

export function TicketComments({ ticketId }: { ticketId: string }) {
  const state = useDaemon((s) => s.connection.state);
  const [list, setList] = useState<TicketComment[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [body, setBody] = useState("");
  const [sending, setSending] = useState(false);
  const load = useCallback(async () => {
    const r = await callTool(daemonStore().getState(), "infantry_ticket_comments", { ticketId, which: "all", detail: "full" });
    if (r.isError) setError(r.text);
    else (setError(null), setList(commentsOf(r.text)));
  }, [ticketId]);
  useEffect(() => {
    if (state === "open") void load();
  }, [state, load]);
  const add = async () => {
    if (!body.trim()) return;
    setSending(true);
    const r = await callTool(daemonStore().getState(), "infantry_ticket_comment", { ticketId, body: body.trim() });
    setSending(false);
    if (r.isError) return setError(r.text);
    setBody("");
    void load();
  };
  return (
    <div className="space-y-2">
      <Label htmlFor="ticket-comment">Comments{list ? ` (${list.length})` : ""}</Label>
      {list && list.length === 0 && <p className="text-xs text-muted-foreground">No comments yet.</p>}
      {list && list.length > 0 && (
        <ol className="max-h-[50vh] space-y-2 overflow-auto">
          {list.map((c) => (
            <li key={c.id} className="rounded-md border p-2">
              <div className="mb-1 flex flex-wrap items-center gap-2 text-[11px] text-muted-foreground">
                <Chip tone={c.state === "open" ? "amber" : "neutral"}>
                  {c.kind}/{c.state}
                </Chip>
                <span className="font-medium text-foreground">{c.author}</span>
                <span className="ml-auto tabular-nums">{c.stamp}</span>
              </div>
              <div className="text-xs break-words">
                <Markdown text={c.body} />
              </div>
            </li>
          ))}
        </ol>
      )}
      {error && (
        <p role="alert" className="text-xs text-danger">
          {error}
        </p>
      )}
      <Textarea id="ticket-comment" rows={3} className="w-full" placeholder="Add a comment" value={body} onChange={(e) => setBody(e.target.value)} />
      <div className="flex justify-end">
        <Button size="sm" variant="outline" disabled={!body.trim() || sending} onClick={() => void add()}>
          Add comment
        </Button>
      </div>
    </div>
  );
}
