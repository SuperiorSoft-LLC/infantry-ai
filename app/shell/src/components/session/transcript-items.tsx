// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The transcript building blocks: thinking, tool calls, file edit + diff, comms, the agent's reply, finished line, note,
// spinner. Each renders one immutable item from @infantry/client.
import { useState, type ReactNode } from "react";
import { Brain, ChevronRight, Loader2, Mail, Wrench, RotateCw } from "lucide-react";
import { cn } from "@infantry/ui";
import { imageRefs, type TranscriptItem } from "@infantry/client";
import type { CommsBody } from "@/mock/types";
import { Markdown } from "./markdown";
import { ImageThumbs } from "./chat-images";
import { ChangeBox } from "./change-box";

export function ThinkingBlock({ count, text }: { count: number; text: string }) {
  const [open, setOpen] = useState(false);
  return (
    <div>
      <button
        type="button"
        aria-expanded={open}
        onClick={() => setOpen((o) => !o)}
        className="flex items-center gap-1.5 text-[13px] text-muted-foreground hover:text-foreground"
      >
        <ChevronRight className={cn("size-3.5 transition-transform", open && "rotate-90")} />
        <Brain className="size-3.5" /> Thinking ({count})
      </button>
      {open && <p className="mt-1.5 border-l-2 pl-3 text-[13px] whitespace-pre-wrap text-muted-foreground italic">{text}</p>}
    </div>
  );
}

export function ToolLine({ count, tool, activity }: { count: number; tool: string; activity: string }) {
  return (
    <div className="flex min-w-0 items-center gap-1.5 text-[13px] text-muted-foreground">
      <Wrench className="size-3.5 shrink-0" />
      <span className="shrink-0 font-medium text-foreground">{tool}</span>
      <span className="min-w-0 truncate font-mono text-xs">{activity}</span>
      {count > 1 && <span className="shrink-0 text-xs">({count} calls)</span>}
    </div>
  );
}

function CommsText({ body }: { body: CommsBody }) {
  return (
    <div className="space-y-2 text-[13.5px] leading-6 break-words">
      {body.paragraphs.map((p, i) => {
        const { text, refs } = imageRefs(p);
        return (
          <div key={i} className="space-y-2">
            {text && <Markdown text={text} />}
            <ImageThumbs refs={refs} />
          </div>
        );
      })}
      {body.bullets && (
        <ul className="list-disc space-y-0.5 pl-5">
          {body.bullets.map((b) => (
            <li key={b.lead}>
              <strong>{b.lead}</strong> {b.text}
            </li>
          ))}
        </ul>
      )}
      {body.outro && <p>{body.outro}</p>}
    </div>
  );
}

/** One message as one markdown body (markdown.tsx splits it into blocks). */
const paragraphs = (text: string): CommsBody => ({ paragraphs: text.trim() ? [text] : [] });

export function CommsCard({
  time,
  owner,
  from,
  body,
  sending,
  onRetry,
}: {
  time: string;
  owner?: boolean;
  from?: string;
  body: CommsBody;
  sending?: { state: "sending" | "accepted" | "failed"; reason?: string };
  onRetry?: () => void;
}) {
  return (
    <div className={cn("overflow-hidden rounded-lg border", owner ? "border-danger/50 bg-danger/15" : "bg-card", sending?.state === "sending" && "opacity-70")}>
      <div className={cn("flex items-center gap-2 px-3 py-2 text-xs font-semibold", owner ? "bg-danger/25" : "border-b")}>
        <Mail className="size-3.5" /> Comms
        {from && <span className="min-w-0 truncate font-normal text-muted-foreground">from {from}</span>}
        <span className="flex-1" />
        {sending?.state === "sending" && <span className="font-normal text-muted-foreground">sending...</span>}
        {sending?.state === "accepted" && <span className="font-normal text-muted-foreground">sent</span>}
        {sending?.state === "failed" && (
          <button type="button" onClick={onRetry} className="flex items-center gap-1 font-normal text-danger hover:underline" title={sending.reason}>
            <RotateCw className="size-3" /> not sent - retry
          </button>
        )}
        <span className="font-mono font-normal text-muted-foreground">{time}</span>
      </div>
      <div className="px-3 py-2.5">
        <CommsText body={body} />
      </div>
    </div>
  );
}

/** The agent's own reply: plain text in the gutter column, no card. */
export function AssistantText({ text }: { text: string }) {
  return <CommsText body={paragraphs(text)} />;
}

export function FinishedLine({ outcome, exitCode }: { outcome: string; exitCode: number | null }) {
  const ok = outcome === "SUCCEEDED" || outcome === "succeeded";
  return (
    <div className={cn("text-xs", ok ? "text-info" : "text-danger")}>
      — finished: {outcome.toLowerCase().replace(/_/g, " ")}
      {exitCode != null && ` (exit ${exitCode})`}
    </div>
  );
}

export function TranscriptNote({ text, danger }: { text: string; danger?: boolean }) {
  return <div className={cn("font-mono text-xs whitespace-pre-wrap break-words", danger ? "text-danger" : "text-muted-foreground")}>{text}</div>;
}

export function ThinkingSpinner({ label, elapsed }: { label: string; elapsed: ReactNode }) {
  return (
    <div role="status" className="flex items-center gap-2 font-mono text-xs text-muted-foreground">
      <Loader2 className="size-3.5 animate-spin text-danger" /> {label} {elapsed}
    </div>
  );
}

export const timeOf = (at: number) => new Date(at).toLocaleTimeString([], { hour: "numeric", minute: "2-digit", second: "2-digit" });

export function TranscriptItemView({ item }: { item: TranscriptItem }) {
  switch (item.kind) {
    case "user":
      return <CommsCard time={timeOf(item.at)} owner body={paragraphs(item.text)} />;
    case "comms":
      return <CommsCard time={timeOf(item.at)} from={item.from} body={paragraphs(item.text)} />;
    case "text":
      return <AssistantText text={item.text} />;
    case "thinking":
      return <ThinkingBlock count={item.count} text={item.text} />;
    case "tools":
      return <ToolLine count={item.count} tool={item.tool} activity={item.activity} />;
    case "edit":
      return <ChangeBox path={item.path} created={item.created} added={item.added} removed={item.removed} lines={item.lines} truncated={item.truncated} />;
    case "finished":
      return <FinishedLine outcome={item.outcome} exitCode={item.exitCode} />;
    case "note":
      return <TranscriptNote text={item.text} danger={item.tone === "danger"} />;
    case "gap":
      return <TranscriptNote text={`[infantry] ${item.evicted} earlier frames are no longer kept by the daemon`} />;
  }
}
